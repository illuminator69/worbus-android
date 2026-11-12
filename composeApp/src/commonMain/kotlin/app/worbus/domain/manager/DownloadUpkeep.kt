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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
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
 * GONE is decided by four rules together, each for a way "not found" can be wrong:
 * 1. Only a COMPLETE and stable full pull (B-037: every album read, no scan before or after, the count
 *    unchanged) can say a song is missing, and only of a download it did not list. A partial pull, a
 *    changed-albums sweep or a pull during a rescan says nothing.
 * 2. Navidrome is then asked for that song by id, and only its own Subsonic error 70 counts
 *    ([songAnswerOf]). A 404 or 5xx from a proxy, a timeout or any other error is no answer, and one
 *    no-answer ends the gone half of the pass for every song: the server is in trouble.
 * 3. A pass whose candidates are a storm judges nothing at all and asks nothing: more than
 *    [GONE_PER_PULL_MAX] songs, or more than 3 and more than half of this device's downloads.
 *    That is what an unmounted library, a new or wrong server or a library mid-rebuild looks like;
 *    a real deletion is a few albums. The cost: a big deletion is held (the files stay) until fewer
 *    remain to judge, or the user deletes them.
 * 4. The first such word marks the song ([DownloadOriginEntity.goneSeenAt]); it is removed only on a
 *    second one at least [GONE_CONFIRM_MS] (a day) later. Listed again in between, or found by id,
 *    the mark is cleared. A rescan, a share mounted late, a NAS rebooting overnight all end in a day.
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

class DownloadUpkeep(
	private val store: DownloadOriginStore,
	private val downloads: UpkeepDownloads,
	private val songsById: suspend (List<String>) -> List<SongEntity>,
	private val askSong: suspend (String) -> SongAnswer,
	private val isOnline: () -> Boolean,
	private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }
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

	/** One pass after a pull (the rules above). */
	suspend fun afterPull(pull: PullSeen): UpkeepReport = lock.withLock {
		if (!isOnline()) return@withLock UpkeepReport(skipped = "offline")
		val at = now()
		val rows = downloads.downloaded().filter { it.status == DownloadStatus.DOWNLOADED && it.filePath != null }
		val held = rows.associateBy { it.songId }
		val stored = store.all().associateBy { it.songId }
		// A record whose download is gone (deleted here, or by a remove below on an earlier pass).
		(stored.keys - held.keys).takeIf { it.isNotEmpty() }?.let { store.remove(it.toList()) }
		val records = stored.filterKeys { it in held }.toMutableMap()
		val dirty = mutableSetOf<String>()
		fun write(row: DownloadOriginEntity) {
			records[row.songId] = row
			dirty += row.songId
		}

		// CHANGED: Room's song against what the file was made from.
		val songs = held.keys.toList().chunked(500).flatMap { songsById(it) }.associateBy { it.songId }
		var baselined = 0
		val refetched = mutableListOf<String>()
		for (row in rows) {
			val song = songs[row.songId] ?: continue
			val origin = songOrigin(song)
			val record = records[row.songId]
			when {
				record == null || !record.origin.startsWith(ORIGIN_VERSION) -> {
					write(DownloadOriginEntity(row.songId, origin, at, record?.goneSeenAt ?: 0L))
					baselined++
				}
				record.origin != origin -> {
					downloads.refetch(song, row)
					refetched += row.songId
				}
			}
		}

		// GONE: only against a complete pull's list.
		val markedGone = mutableListOf<String>()
		val removed = mutableListOf<String>()
		var goneHeld = ""
		val listed = pull.listed
		if (listed != null) {
			fun clearMark(id: String) {
				records[id]?.takeIf { it.goneSeenAt > 0L }?.let { write(it.copy(goneSeenAt = 0L)) }
			}
			rows.filter { it.songId in listed }.forEach { clearMark(it.songId) }
			val candidates = rows.map { it.songId }.filter { it !in listed }
			if (candidates.isNotEmpty() && goneStorm(candidates.size, rows.size)) {
				goneHeld = "${candidates.size} of ${rows.size} downloads not listed at once: nothing judged"
			} else if (candidates.isNotEmpty()) {
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
					val record = records[id]
					val first = record?.goneSeenAt ?: 0L
					when {
						first <= 0L -> {
							write((record ?: DownloadOriginEntity(id, "", at)).copy(goneSeenAt = at))
							markedGone += id
						}
						at - first >= GONE_CONFIRM_MS -> {
							downloads.remove(id)
							removed += id
						}
					}
				}
			}
		}

		val writes = dirty.filter { it !in removed }.mapNotNull { records[it] }
		if (writes.isNotEmpty()) store.put(writes)
		if (removed.isNotEmpty()) store.remove(removed)
		UpkeepReport(baselined, refetched, markedGone, removed, goneHeld)
	}

	companion object {
		/** Rule 4: the second "gone" must come at least this long after the first. */
		val GONE_CONFIRM_MS: Long = 24.hours.inWholeMilliseconds

		/** Rule 3: more songs than this not listed by one complete pull is a storm, whatever the share. */
		const val GONE_PER_PULL_MAX = 25

		/** Rule 3: [candidates] of [downloads] is too many to judge in one pass. */
		fun goneStorm(candidates: Int, downloads: Int): Boolean =
			candidates > GONE_PER_PULL_MAX || (candidates > 3 && candidates * 2 > downloads)
	}
}

/**
 * The [DownloadOriginStore] over `writes.db`, and the one place a fetched file's origin is recorded:
 * from Room's row when it has one (what [DownloadUpkeep] compares with later), else from what was
 * fetched.
 */
class DownloadOrigins(private val dao: DownloadOriginDao, private val songDao: SongDao) : DownloadOriginStore {
	override suspend fun all(): List<DownloadOriginEntity> = dao.all()
	override suspend fun put(rows: List<DownloadOriginEntity>) {
		if (rows.isNotEmpty()) dao.upsert(rows)
	}

	override suspend fun remove(songIds: List<String>) {
		songIds.chunked(500).forEach { dao.delete(idsAsJson(it)) }
	}

	suspend fun recordFetched(songId: String, fetched: SongEntity?) {
		val song = songDao.getSongById(songId) ?: fetched ?: return
		dao.upsert(listOf(DownloadOriginEntity(songId, songOrigin(song), Clock.System.now().toEpochMilliseconds())))
	}
}

/** The file operations a download needs, so the swap can be run against real files in a host test. */
interface DownloadFiles {
	suspend fun save(path: String, channel: ByteReadChannel)
	fun size(path: String): Long

	/** Put [tempPath] over [path] in one rename; false, and [tempPath] gone, when it cannot. Never a copy. */
	fun replace(tempPath: String, path: String): Boolean
	fun delete(path: String)
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
