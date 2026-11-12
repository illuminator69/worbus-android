package app.worbus.domain.manager

import app.worbus.data.database.dao.DownloadOriginDao
import app.worbus.data.database.dao.SongDao
import app.worbus.data.database.entities.DownloadEntity
import app.worbus.data.database.entities.DownloadOriginEntity
import app.worbus.data.database.entities.DownloadStatus
import app.worbus.data.database.entities.SongEntity
import app.worbus.util.Logger
import app.worbus.util.idsAsJson
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/*
 * Q-165, the owner's ruling (2026-11-12): a downloaded song that CHANGED on the server (re-tagged, or
 * its file replaced) is fetched again in the background while online, the old copy playing until the
 * new one is whole; a song GONE from the server has its copy removed and its record dropped, and a
 * Navidrome saying "not found" for many songs at once wipes nothing.
 *
 * CHANGED is read from what Navidrome exposes per song, which is all a client has (no content hash,
 * no per-song change time in the Subsonic API): [songOrigin], the file's size, suffix, bit rate and
 * duration, and the tags the file carries (title, artists, album, track, disc, year, MusicBrainz id).
 * A download records it when it is fetched; after each pull Room's row is compared with it. Plays,
 * stars and ratings are Navidrome's, not the file's, and do not count.
 *
 * WHOSE (the follow-up's major 2): a download belongs to the Navidrome server and user it was fetched
 * (or first seen) under ([DownloadOwner], recorded in `DownloadOrigin`). Only the current session's are
 * compared or judged; another server's or user's are kept as they are, never asked about, never
 * removed. A download with no record, or a record from before `writes.db` 3, is placed under the current
 * session only when this session's library (Room) holds its song; a download nobody can place is kept,
 * and no record is ever made up for a song this session never described.
 *
 * A RE-KEY is a move (major 1): the same recording back under a new song id while the old one answers
 * 70. Measured 2026-11-12 on Navidrome 0.64.2 (a scratch instance): a track's or the album's MusicBrainz
 * ids retagged, a title changed, a file renamed, a rename with a retag, a full scan all KEPT the song id
 * (only the album id followed the release id), so a re-key comes from elsewhere (a library rebuilt, a
 * file moved to another library, another `PID` setting, another Navidrome version). Before any gone rule, a gone download whose one successor ([successorOf]) is in the same
 * complete pull is carried to it: its file renamed, its row and its record re-keyed. It removes
 * nothing, so it needs neither the storm rule nor the wait.
 *
 * GONE is then decided by five rules together, each for a way "not found" can be wrong:
 * 1. Only a COMPLETE and stable full pull (B-037: every album read, no scan before or after, the count
 *    unchanged) can say a song is missing, and only of a download it did not list. A partial pull, a
 *    changed-albums sweep or a pull during a rescan says nothing.
 * 2. Navidrome is then asked for that song by id, and only its own Subsonic error 70 counts
 *    ([songAnswerOf]). A 404 or 5xx from a proxy, a timeout or any other error is no answer, and one
 *    no-answer ends the gone half of the pass for every song: the server is in trouble.
 * 3. A pass whose candidates are a storm judges nothing at all and asks nothing: more than
 *    [GONE_PER_PULL_MAX] songs, or more than 3 and more than half of this device's downloads.
 * 4. A pull whose library SHRANK since the last complete one by more than max(25 songs, 2 %) judges
 *    nothing (minor 3: an unmounted share removes thousands of the library's songs and a few of the
 *    device's). After 30 days of the upkeep's clock at the smaller size it is taken as the size.
 * 5. The first word marks the song; it is removed only once THREE complete pulls have said so and 72 h
 *    have passed on the upkeep's own clock ([UpkeepMemory.clockMs], which a wall clock set forward or
 *    back moves by at most a step). Listed again in between, or found by id, the mark is cleared.
 *
 * A re-fetch is never of a song the local player is playing or has queued (its path is the one the
 * player reads; the swap waits), and at most [REFETCH_PER_PASS] a pass and [REFETCH_PER_DAY] a day are
 * asked for (minor 5: a Navidrome upgrade can change every song's tags at once), under the downloads'
 * own Wi-Fi-only and charging-only holds.
 *
 * Offline (the app's `isOnline`: a network AND Navidrome answering) a pass does nothing.
 */

/** What Navidrome answered `getSong` for one id. */
enum class SongAnswer {
	/** It has the song. */
	PRESENT,

	/** Subsonic error 70, "the requested data was not found": Navidrome's own word that it is gone. */
	GONE,

	/** Anything else: no answer, a 404 or a 5xx from something in front of it, a refusal of the session. */
	NO_ANSWER
}

/** What Navidrome's answer (or its failure) for one `getSong` means: [WriteQueue]'s rule 5 reading of a refusal. */
fun songAnswerOf(error: Throwable?): SongAnswer = when {
	error == null -> SongAnswer.PRESENT
	error is CancellationException -> throw error
	writeFailure(error) == WriteFailure.GONE -> SongAnswer.GONE
	else -> SongAnswer.NO_ANSWER
}

/** A library pull as the upkeep reads it: [listed] is the song ids of a COMPLETE and stable full pull (B-037), else null. */
data class PullSeen(val listed: Set<String>?)

/** What one upkeep pass did, for its log line and the tests. */
data class UpkeepReport(
	val baselined: Int = 0,
	val refetched: List<String> = emptyList(),
	val markedGone: List<String> = emptyList(),
	val removed: List<String> = emptyList(),
	/** old id to new id: a download carried to the song Navidrome re-keyed it as (major 1). */
	val moved: Map<String, String> = emptyMap(),
	/** Changed, but playing or queued here, or over the re-fetch budget: asked again next pass. */
	val deferred: List<String> = emptyList(),
	/** Records from before 3 placed under the current session (their song is in this session's library). */
	val adopted: Int = 0,
	/** The gone half held back: too many at once, or Navidrome did not answer. */
	val goneHeld: String = "",
	/** The pass did nothing at all, and why ("offline"). */
	val skipped: String = ""
)

/** This device's downloads, as the upkeep needs them. [DownloadManager] in the app. */
interface UpkeepDownloads {
	/** The DOWNLOADED rows that have a file. */
	suspend fun downloaded(): List<DownloadEntity>

	/** Fetch [song] again over [row]'s file, in the background (an atomic swap; the old copy stays until then). */
	fun refetch(song: SongEntity, row: DownloadEntity)

	/** Delete the file and the row. */
	suspend fun remove(songId: String)

	/** Carry the download of [fromId] to [to] (a re-key): its file renamed `<to>.<ext>`, its row re-keyed. */
	suspend fun move(fromId: String, to: SongEntity): Boolean
}

/** Whose a download is: the Navidrome server, by its address normalised, and the user (major 2). */
data class DownloadOwner(val server: String, val account: String) {
	companion object {
		fun of(instanceUrl: String, username: String) =
			DownloadOwner(instanceUrl.trim().trimEnd('/').lowercase(), username.trim())
	}
}

/**
 * What the upkeep keeps between passes (persisted; [UpkeepMemoryStore]). [clockMs] is its own clock:
 * each pass adds the wall time since the last one, never less than 0 and never more than
 * [DownloadUpkeep.CLOCK_STEP_MAX_MS], so a clock set forward (or back) moves it by at most a step.
 */
@kotlinx.serialization.Serializable
data class UpkeepMemory(
	val owner: String = "",
	val clockMs: Long = 0L,
	val lastWallMs: Long = 0L,
	/** The song count of the last complete pull that was not a shrink, for this [owner]; 0 = none yet. */
	val libraryRef: Int = 0,
	/** [clockMs] when the library first read as shrunk below [libraryRef]; 0 while it does not. */
	val shrunkSinceMs: Long = 0L,
	/** The re-fetch budget's day: when it started on [clockMs], and how many were asked since. */
	val refetchDayStartMs: Long = 0L,
	val refetchedInDay: Int = 0
)

interface UpkeepMemoryStore {
	suspend fun load(): UpkeepMemory
	suspend fun save(memory: UpkeepMemory)
}

/** The `DownloadOrigin` table ([DownloadOriginEntity]). */
interface DownloadOriginStore {
	suspend fun all(): List<DownloadOriginEntity>
	suspend fun put(rows: List<DownloadOriginEntity>)
	suspend fun remove(songIds: List<String>)
}

/**
 * What a downloaded file was made from: the song as Navidrome describes it, versioned so that a later
 * build reading more fields takes what is recorded as it is ([ORIGIN_VERSION]) instead of fetching
 * every download again. The credit is the track's own (`artistName`, which Navidrome always sends;
 * the album's is only a fallback for a track that names none) and the OpenSubsonic `artists[]` names.
 */
fun songOrigin(song: SongEntity): String = ORIGIN_VERSION + JsonArray(
	listOf(
		song.fileSize.toString(),
		song.fileExtension.orEmpty(),
		(song.bitRate ?: 0).toString(),
		song.duration.inWholeSeconds.toString(),
		song.title,
		song.artistName.orEmpty(),
		song.artists.joinToString("\u0000") { it.name },
		song.albumTitle.orEmpty(),
		(song.trackNumber ?: 0).toString(),
		(song.discNumber ?: 0).toString(),
		(song.year ?: 0).toString(),
		song.musicBrainzId.orEmpty()
	).map(::JsonPrimitive)
).toString()

/** The fields [songOrigin] reads, and their order. A record of another version is re-recorded, never compared. */
const val ORIGIN_VERSION = "o1:"

/** The fields [songOrigin] recorded, read back (a successor is matched on them, major 1); null for another version. */
data class OriginFields(
	val size: Long, val suffix: String, val bitRate: Int, val seconds: Long, val title: String,
	val artist: String, val artists: String, val album: String, val track: Int, val disc: Int,
	val year: Int, val mbid: String
)

/** The fields [songOrigin] wrote, by position; null for a record of another version or none. */
fun originFields(origin: String): OriginFields? {
	if (!origin.startsWith(ORIGIN_VERSION)) return null
	val v = try {
		Json.parseToJsonElement(origin.removePrefix(ORIGIN_VERSION)).jsonArray.map { it.jsonPrimitive.content }
	} catch (e: Exception) {
		if (e is CancellationException) throw e
		return null
	}
	if (v.size != 12) return null
	return OriginFields(
		size = v[0].toLongOrNull() ?: 0L, suffix = v[1], bitRate = v[2].toIntOrNull() ?: 0,
		seconds = v[3].toLongOrNull() ?: 0L, title = v[4], artist = v[5], artists = v[6], album = v[7],
		track = v[8].toIntOrNull() ?: 0, disc = v[9].toIntOrNull() ?: 0, year = v[10].toIntOrNull() ?: 0, mbid = v[11]
	)
}

/**
 * Major 1: the one song a gone download was re-keyed as (the same recording under a new id, the old one
 * error 70; when that happens is in this file's header). A successor is in this complete pull ([listed]), not itself downloaded ([held]), at the same
 * disc and track, with the same title by [creditKey], a length within 2 s, and the same artist
 * ([sameArtist]). It must be the ONLY one; the album title only breaks a tie (an override can rename an
 * album, "X (Deluxe)" to "X"). None, or two, is no successor.
 */
fun successorOf(old: OriginFields, candidates: List<SongEntity>, listed: Set<String>, held: Set<String>): SongEntity? {
	val title = creditKey(old.title)
	if (title.isEmpty() || old.artist.isBlank()) return null
	val fits = candidates.filter { song ->
		song.songId in listed && song.songId !in held &&
			(song.trackNumber ?: 0) == old.track && (song.discNumber ?: 0) == old.disc &&
			creditKey(song.title) == title &&
			abs(song.duration.inWholeSeconds - old.seconds) <= SUCCESSOR_SECONDS &&
			sameArtist(song.artistName.orEmpty(), old.artist)
	}
	fits.singleOrNull()?.let { return it }
	val album = creditKey(old.album)
	return fits.filter { creditKey(it.albumTitle.orEmpty()) == album }.singleOrNull()
}

/** How far a successor's length may be from the gone song's, in whole seconds. */
const val SUCCESSOR_SECONDS = 2

class DownloadUpkeep(
	private val store: DownloadOriginStore,
	private val downloads: UpkeepDownloads,
	private val songsById: suspend (List<String>) -> List<SongEntity>,
	private val askSong: suspend (String) -> SongAnswer,
	private val isOnline: () -> Boolean,
	private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
	/** The current session's owner ([DownloadOwner]); null with no session, and then nothing is done. */
	private val owner: () -> DownloadOwner?,
	private val memory: UpkeepMemoryStore,
	/** Song ids the LOCAL player is playing or has queued: their files are not swapped or renamed. */
	private val inUse: () -> Set<String>,
	/** Room's songs at this track and disc number (a successor's candidates). */
	private val songsAt: suspend (track: Int, disc: Int) -> List<SongEntity>
) {
	private val lock = Mutex()

	/** Run [afterPull] for each pull reported, one at a time, for the life of the app. */
	fun follow(pulls: Flow<PullSeen>, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
		scope.launch {
			pulls.collect { pull ->
				try {
					val report = afterPull(pull)
					if (report != UpkeepReport() && report.skipped.isEmpty()) Logger.i("DownloadUpkeep", report.toString())
				} catch (e: Exception) {
					if (e is CancellationException) throw e
					Logger.w("DownloadUpkeep", "the downloads' upkeep failed; tried again after the next pull", e)
				}
			}
		}
	}

	/** One pass after a pull (the rules in this file's header). */
	suspend fun afterPull(pull: PullSeen): UpkeepReport = lock.withLock {
		if (!isOnline()) return@withLock UpkeepReport(skipped = "offline")
		val me = owner() ?: return@withLock UpkeepReport(skipped = "no session")
		val wall = now()
		var mem = memory.load()
		val step = if (mem.lastWallMs == 0L) 0L else (wall - mem.lastWallMs).coerceIn(0L, CLOCK_STEP_MAX_MS)
		val key = "${me.server}\u0000${me.account}"
		if (mem.owner != key) mem = UpkeepMemory(owner = key, clockMs = mem.clockMs)
		val clock = mem.clockMs + step
		mem = mem.copy(clockMs = clock, lastWallMs = wall)

		val rows = downloads.downloaded().filter { it.status == DownloadStatus.DOWNLOADED && it.filePath != null }
		val held = rows.associateBy { it.songId }
		val stored = store.all().associateBy { it.songId }
		// A record whose download is gone (deleted here, or removed or moved on an earlier pass).
		(stored.keys - held.keys).takeIf { it.isNotEmpty() }?.let { store.remove(it.toList()) }
		val records = stored.filterKeys { it in held }.toMutableMap()
		val dirty = mutableSetOf<String>()
		fun write(row: DownloadOriginEntity) {
			records[row.songId] = row
			dirty += row.songId
		}
		fun mine(record: DownloadOriginEntity?) = record != null && record.server == me.server && record.account == me.account
		val playing = inUse()

		// CHANGED, and placing records: Room holds only this session's library, so a song it holds
		// proves a download (with no record, or one from before 3) is this session's.
		val songs = held.keys.toList().chunked(500).flatMap { songsById(it) }.associateBy { it.songId }
		var baselined = 0
		var adopted = 0
		val changed = mutableListOf<Pair<SongEntity, DownloadEntity>>()
		for (row in rows) {
			val song = songs[row.songId] ?: continue
			val origin = songOrigin(song)
			val record = records[row.songId]
			when {
				record == null -> {
					write(DownloadOriginEntity(row.songId, origin, wall, server = me.server, account = me.account))
					baselined++
				}
				record.server.isEmpty() && record.account.isEmpty() -> {
					val kept = record.origin.takeIf { it.startsWith(ORIGIN_VERSION) } ?: origin
					write(record.copy(origin = kept, server = me.server, account = me.account, goneSeenAt = 0L, gonePulls = 0))
					adopted++
					if (kept != origin) changed += song to row
				}
				!mine(record) -> Unit // another server's or user's: kept, never judged
				!record.origin.startsWith(ORIGIN_VERSION) -> {
					write(record.copy(origin = origin))
					baselined++
				}
				record.origin != origin -> changed += song to row
			}
		}

		// The re-fetch budget: [REFETCH_PER_PASS] a pass and [REFETCH_PER_DAY] a day of the upkeep's clock;
		// what is over it, or playing or queued here, waits for a later pass.
		if (clock - mem.refetchDayStartMs >= DAY_MS) mem = mem.copy(refetchDayStartMs = clock, refetchedInDay = 0)
		val room = minOf(REFETCH_PER_PASS, REFETCH_PER_DAY - mem.refetchedInDay).coerceAtLeast(0)
		val ready = changed.filter { (song, _) -> song.songId !in playing }.sortedBy { it.first.songId }
		val refetchNow = ready.take(room)
		refetchNow.forEach { (song, row) -> downloads.refetch(song, row) }
		mem = mem.copy(refetchedInDay = mem.refetchedInDay + refetchNow.size)
		val deferred = (changed.map { it.first.songId } - refetchNow.map { it.first.songId }.toSet()).sorted()

		// GONE: only this session's downloads with a real record, only against a complete pull's list.
		val markedGone = mutableListOf<String>()
		val removed = mutableListOf<String>()
		val moved = linkedMapOf<String, String>()
		var goneHeld = ""
		val listed = pull.listed
		if (listed != null) {
			val ours = rows.filter { mine(records[it.songId]) && records.getValue(it.songId).origin.startsWith(ORIGIN_VERSION) }
			fun clearMark(id: String) {
				records[id]?.takeIf { it.gonePulls > 0 || it.goneSeenAt > 0L }?.let { write(it.copy(goneSeenAt = 0L, gonePulls = 0)) }
			}
			ours.filter { it.songId in listed }.forEach { clearMark(it.songId) }
			var candidates = ours.map { it.songId }.filter { it !in listed }

			// Major 1, first: a re-key is a move. It removes nothing, so no storm or wait applies to it.
			val successors = mutableMapOf<String, SongEntity>()
			for (id in candidates) {
				if (id in playing) continue
				val fields = originFields(records.getValue(id).origin) ?: continue
				successorOf(fields, songsAt(fields.track, fields.disc), listed, held.keys)?.let { successors[id] = it }
			}
			val claimedTwice = successors.values.groupBy { it.songId }.filterValues { it.size > 1 }.keys
			for ((id, next) in successors) {
				if (next.songId in claimedTwice) continue
				if (!downloads.move(id, next)) continue
				moved[id] = next.songId
				// The record goes with it, origin and all: the move keeps the file as it was, so a retag
				// the re-key came with reads as CHANGED next pass and is fetched again.
				val record = records.remove(id)!!
				dirty -= id
				store.remove(listOf(id))
				write(record.copy(songId = next.songId, goneSeenAt = 0L, gonePulls = 0))
			}
			candidates = candidates.filter { it !in moved }

			// Minor 3: a library that shrank since the last complete pull (an unmounted share, a library
			// mid-rebuild) is not a place to judge from.
			val ref = mem.libraryRef
			val shrank = ref > 0 && ref - listed.size > maxOf(SHRINK_MIN_SONGS, ref * SHRINK_PERCENT / 100)
			mem = when {
				!shrank -> mem.copy(libraryRef = listed.size, shrunkSinceMs = 0L)
				mem.shrunkSinceMs == 0L -> mem.copy(shrunkSinceMs = clock)
				clock - mem.shrunkSinceMs >= SHRINK_ACCEPT_MS -> mem.copy(libraryRef = listed.size, shrunkSinceMs = 0L)
				else -> mem
			}

			when {
				candidates.isEmpty() -> Unit
				shrank -> goneHeld = "the library shrank from $ref to ${listed.size} songs: nothing judged"
				goneStorm(candidates.size, rows.size) ->
					goneHeld = "${candidates.size} of ${rows.size} downloads not listed at once: nothing judged"
				else -> {
					val gone = mutableListOf<String>()
					for (id in candidates) {
						when (askSong(id)) {
							SongAnswer.GONE -> gone += id
							SongAnswer.PRESENT -> clearMark(id)
							SongAnswer.NO_ANSWER -> {
								goneHeld = "Navidrome gave no answer for $id: nothing judged"
								gone.clear()
								break
							}
						}
					}
					for (id in gone) {
						val record = records.getValue(id)
						val pulls = record.gonePulls + 1
						when {
							record.gonePulls == 0 -> {
								write(record.copy(goneSeenAt = clock, gonePulls = 1))
								markedGone += id
							}
							pulls >= GONE_PULLS && clock - record.goneSeenAt >= GONE_CONFIRM_MS -> {
								downloads.remove(id)
								removed += id
							}
							else -> write(record.copy(gonePulls = pulls))
						}
					}
				}
			}
		}

		val writes = dirty.filter { it !in removed }.mapNotNull { records[it] }
		if (writes.isNotEmpty()) store.put(writes)
		if (removed.isNotEmpty()) store.remove(removed)
		memory.save(mem)
		UpkeepReport(
			baselined = baselined, refetched = refetchNow.map { it.first.songId }, markedGone = markedGone,
			removed = removed, moved = moved, deferred = deferred, adopted = adopted, goneHeld = goneHeld
		)
	}

	companion object {
		/** The most one pass adds to the upkeep's clock: a clock set forward, or a phone off for a week, counts this. */
		val CLOCK_STEP_MAX_MS: Long = 12.hours.inWholeMilliseconds

		/** Rule 4: the first word and the removal at least this far apart on the upkeep's clock... */
		val GONE_CONFIRM_MS: Long = 72.hours.inWholeMilliseconds

		/** ...and this many complete pulls saying so, the first included. */
		const val GONE_PULLS = 3

		/** Rule 3: more songs than this not listed by one complete pull is a storm, whatever the share. */
		const val GONE_PER_PULL_MAX = 25

		/** Minor 3: a pull this many songs, or this share, below the last one is a shrunk library. */
		const val SHRINK_MIN_SONGS = 25
		const val SHRINK_PERCENT = 2

		/** A library shrunk this long (upkeep clock) is taken as its size: the share is not coming back. */
		val SHRINK_ACCEPT_MS: Long = 30.days.inWholeMilliseconds

		/** Minor 5: re-fetches asked a pass, and a day of the upkeep's clock. */
		const val REFETCH_PER_PASS = 20
		const val REFETCH_PER_DAY = 100
		private val DAY_MS: Long = 24.hours.inWholeMilliseconds

		/** Rule 3: [candidates] of [downloads] is too many to judge in one pass. */
		fun goneStorm(candidates: Int, downloads: Int): Boolean =
			candidates > GONE_PER_PULL_MAX || (candidates > 3 && candidates * 2 > downloads)
	}
}

/**
 * The [DownloadOriginStore] over `writes.db`, and the one place a fetched file's origin is recorded:
 * from Room's row when it has one (what [DownloadUpkeep] compares with later), else from what was
 * fetched, under the session it was fetched in ([owner]); with no session nothing is recorded, and the
 * next pass places the download from Room.
 */
class DownloadOrigins(
	private val dao: DownloadOriginDao,
	private val songDao: SongDao,
	private val owner: () -> DownloadOwner?
) : DownloadOriginStore {
	override suspend fun all(): List<DownloadOriginEntity> = dao.all()
	override suspend fun put(rows: List<DownloadOriginEntity>) {
		if (rows.isNotEmpty()) dao.upsert(rows)
	}

	override suspend fun remove(songIds: List<String>) {
		songIds.chunked(500).forEach { dao.delete(idsAsJson(it)) }
	}

	suspend fun recordFetched(songId: String, fetched: SongEntity?) {
		val me = owner() ?: return
		val song = songDao.getSongById(songId) ?: fetched ?: return
		dao.upsert(listOf(DownloadOriginEntity(songId, songOrigin(song), Clock.System.now().toEpochMilliseconds(),
			server = me.server, account = me.account)))
	}
}

/** [UpkeepMemory] as one JSON preference. An unreadable value is a fresh memory (nothing is judged from it). */
class PreferenceUpkeepMemory(private val read: () -> String, private val write: (String) -> Unit) : UpkeepMemoryStore {
	private val json = Json { ignoreUnknownKeys = true }
	override suspend fun load(): UpkeepMemory = read().takeIf { it.isNotBlank() }?.let {
		try { json.decodeFromString(UpkeepMemory.serializer(), it) } catch (e: Exception) { if (e is CancellationException) throw e; null }
	} ?: UpkeepMemory()
	override suspend fun save(memory: UpkeepMemory) = write(json.encodeToString(UpkeepMemory.serializer(), memory))
}

/** The file operations a download needs, so the swap can be run against real files in a host test. */
interface DownloadFiles {
	suspend fun save(path: String, channel: ByteReadChannel)
	fun size(path: String): Long

	/** Put [tempPath] over [path] in one rename; false, and [tempPath] gone, when it cannot. Never a copy. */
	fun replace(tempPath: String, path: String): Boolean
	fun delete(path: String)

	/** Rename [from] to [to] when [to] does not exist; false otherwise, and nothing moved. */
	fun move(from: String, to: String): Boolean
}

/**
 * Carry a download to the song Navidrome re-keyed it as (major 1): the file renamed to [newPath]
 * (`<new id>.<ext>`, the name adoption reads the id from), never over a file already there; then the
 * row under the new id, the same download in every other field; then the old row dropped. A crash
 * between the steps leaves the file under its new name with one row or two: adoption and the next
 * upkeep pass find it there.
 */
internal suspend fun moveDownload(
	row: DownloadEntity,
	toId: String,
	newPath: String,
	files: DownloadFiles,
	insertRow: suspend (DownloadEntity) -> Unit,
	deleteRow: suspend (String) -> Unit,
	now: Long
): Boolean {
	val from = row.filePath ?: return false
	if (!files.move(from, newPath)) return false
	insertRow(row.copy(songId = toId, filePath = newPath, updatedAt = now))
	deleteRow(row.songId)
	return true
}

/**
 * Fetch a changed song again over its download ([row]): into [tempPath] by [transfer] (which writes
 * and checks the file, and throws when it is not whole), then over [newPath] in one rename, then the
 * row. True when the new copy is in place.
 *
 * Until the rename the row and the old file are untouched, so the old copy plays throughout, and a
 * transfer that fails or is cut leaves it as it was, with no scratch file. A download deleted while
 * the new copy was on its way ([currentRow] no longer DOWNLOADED) is not brought back. A new suffix
 * is a new path: the old file goes only once the row names the new one.
 */
internal suspend fun refetchOver(
	row: DownloadEntity,
	newPath: String,
	tempPath: String,
	files: DownloadFiles,
	transfer: suspend (tempPath: String) -> Unit,
	currentRow: suspend () -> DownloadEntity?,
	writeRow: suspend (DownloadEntity) -> Unit,
	now: Long
): Boolean {
	try {
		transfer(tempPath)
	} catch (e: Throwable) {
		files.delete(tempPath)
		if (e is CancellationException) throw e
		return false
	}
	val current = currentRow()
	if (current == null || current.status != DownloadStatus.DOWNLOADED) {
		files.delete(tempPath)
		return false
	}
	if (!files.replace(tempPath, newPath)) return false
	writeRow(current.copy(filePath = newPath, fileSize = files.size(newPath), progress = 1f, error = null, updatedAt = now))
	val oldPath = current.filePath ?: row.filePath
	if (oldPath != null && oldPath != newPath) files.delete(oldPath)
	return true
}
