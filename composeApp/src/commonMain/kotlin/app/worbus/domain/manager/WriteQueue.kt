package app.worbus.domain.manager

import app.worbus.data.database.dao.PendingWriteDao
import app.worbus.data.database.dao.SyncActionDao
import app.worbus.data.database.entities.AlbumEntity
import app.worbus.data.database.entities.ArtistEntity
import app.worbus.data.database.entities.PendingWriteEntity
import app.worbus.data.database.entities.SongEntity
import app.worbus.data.database.entities.SyncActionType
import app.worbus.util.Logger
import dev.zt64.subsonic.api.model.SubsonicErrorCode
import dev.zt64.subsonic.api.model.SubsonicException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/*
 * The offline write queue (B-373). Its rules are shared with the desktop (I-070) and written out in
 * navi-connect/RESULTS-phase-b-android-2026-11-10.md, "The write-queue rules":
 *  1. kept outside any cache: `writes.db`, its own file, never dropped; "Rebuild database" does not touch it;
 *  2. sent in the order it was made;
 *  3. a later star/unstar of one item replaces the earlier one, a later rating the earlier rating;
 *  4. a scrobble is never merged and is sent with the time it was played;
 *  5. one write the server refuses never holds back the others; nothing answering ends the pass;
 *  6. a pull from the server never overwrites a write it has not had yet ([PendingOverlay]).
 */

/** One write waiting for the server: a star, a rating, a scrobble or a playlist delete. */
data class QueuedWrite(
	val id: Long,
	val type: SyncActionType,
	val itemId: String,
	val time: Instant,
	val refusals: Int = 0,
	val lastError: String? = null
)

/** Where the queue is kept. Oldest first; ids only grow. */
interface WriteQueueStore {
	suspend fun pending(): List<QueuedWrite>

	/** Add a write, removing in the same step the pending ones on [itemId] whose type is in [supersedes]. */
	suspend fun add(type: SyncActionType, itemId: String, time: Instant, supersedes: Set<SyncActionType>)
	suspend fun remove(id: Long)
	suspend fun markRefused(id: Long, reason: String)
	suspend fun clear()

	/** The queue as it stands, re-emitted on every change, for the Pending actions page. */
	fun observe(): Flow<List<QueuedWrite>>

	/** Rows a cache database still holds from before B-373, moved once; 0 when there were none. */
	suspend fun moveLegacy(): Int = 0
}

/** Which writes a new one of [type] replaces on the same item (rule 3). */
fun supersededBy(type: SyncActionType): Set<SyncActionType> = when (type) {
	SyncActionType.STAR, SyncActionType.UNSTAR -> STAR_FAMILY
	in RATING_FAMILY -> RATING_FAMILY
	// Deleting a playlist twice is deleting it once.
	SyncActionType.DELETE_PLAYLIST -> setOf(SyncActionType.DELETE_PLAYLIST)
	// Rule 4: every play is its own listen.
	else -> emptySet()
}

private val STAR_FAMILY = setOf(SyncActionType.STAR, SyncActionType.UNSTAR)
private val RATING_FAMILY = setOf(
	SyncActionType.STAR_0, SyncActionType.STAR_1, SyncActionType.STAR_2,
	SyncActionType.STAR_3, SyncActionType.STAR_4, SyncActionType.STAR_5
)

/** The rating a `STAR_n` write sets, or null for any other type. */
fun ratingOf(type: SyncActionType): Int? = when (type) {
	SyncActionType.STAR_0 -> 0
	SyncActionType.STAR_1 -> 1
	SyncActionType.STAR_2 -> 2
	SyncActionType.STAR_3 -> 3
	SyncActionType.STAR_4 -> 4
	SyncActionType.STAR_5 -> 5
	else -> null
}

/** What a failed send means for the rest of the pass (rule 5). */
enum class WriteFailure {
	/** Nothing answered, or the session itself was refused: every other write would fail the same way. */
	STOP,

	/** The server answered and refused this one write: it stays, the others go on. */
	REFUSED,

	/** The server does not have the item any more: the write is moot and leaves the queue. */
	GONE
}

fun writeFailure(t: Throwable): WriteFailure {
	val subsonic = causeChain(t).firstNotNullOfOrNull { it as? SubsonicException }
	if (subsonic != null) return when (subsonic.code) {
		SubsonicErrorCode.DATA_NOT_FOUND -> WriteFailure.GONE
		SubsonicErrorCode.WRONG_USERNAME_OR_PASSWORD,
		SubsonicErrorCode.TOKEN_AUTH_NOT_SUPPORTED_LDAP,
		SubsonicErrorCode.AUTH_METHOD_NOT_SUPPORTED,
		SubsonicErrorCode.MULTIPLE_CONFLICTING_AUTH,
		SubsonicErrorCode.INVALID_API_KEY,
		SubsonicErrorCode.INCOMPATIBLE_CLIENT_VERSION,
		SubsonicErrorCode.INCOMPATIBLE_SERVER_VERSION,
		SubsonicErrorCode.TRIAL_EXPIRED -> WriteFailure.STOP
		else -> WriteFailure.REFUSED
	}
	if (causeChain(t).any { it is SubsonicStatusException && it.code == 70 }) return WriteFailure.GONE
	return when (loginFailureKind(t)) {
		LoginFailureKind.UNREACHABLE, LoginFailureKind.TLS_TO_PLAIN_HTTP, LoginFailureKind.WRONG_CREDENTIALS -> WriteFailure.STOP
		// Something answered that is not Subsonic (a proxy's page): this write failed, try the rest.
		else -> WriteFailure.REFUSED
	}
}

/**
 * What the pending writes say an item's star and rating are (rule 6). A pull applies it to the
 * rows it is about to write, so the server's older answer never replaces a change it has not had.
 * An item is a song, an album or an artist (Navidrome's ids do not collide), and a write does not
 * say which, so every kind of row is looked up by the same id.
 */
class PendingOverlay(writes: List<QueuedWrite>) {
	/** Present: the last star-family write; the value is the star's time, or null for an unstar. */
	private val starred = HashMap<String, Instant?>()
	private val rating = HashMap<String, Int>()

	init {
		for (w in writes.sortedBy { it.id }) {
			when {
				w.type == SyncActionType.STAR -> starred[w.itemId] = w.time
				w.type == SyncActionType.UNSTAR -> starred[w.itemId] = null
				else -> ratingOf(w.type)?.let { rating[w.itemId] = it }
			}
		}
	}

	val isEmpty: Boolean get() = starred.isEmpty() && rating.isEmpty()

	fun album(row: AlbumEntity): AlbumEntity = if (isEmpty) row else row.copy(
		starredAt = if (row.albumId in starred) starred[row.albumId] else row.starredAt,
		userRating = rating[row.albumId] ?: row.userRating
	)

	fun song(row: SongEntity): SongEntity = if (isEmpty) row else row.copy(
		starredAt = if (row.songId in starred) starred[row.songId] else row.starredAt,
		userRating = rating[row.songId] ?: row.userRating
	)

	fun artist(row: ArtistEntity): ArtistEntity = if (isEmpty) row else row.copy(
		starredAt = if (row.artistId in starred) starred[row.artistId] else row.starredAt,
		userRating = rating[row.artistId] ?: row.userRating
	)

	companion object {
		val NONE = PendingOverlay(emptyList())
	}
}

/** The queue in `writes.db`, and the one-time move of what the cache database held before (B-373). */
class RoomWriteQueueStore(
	private val dao: PendingWriteDao,
	private val legacy: SyncActionDao?
) : WriteQueueStore {
	override suspend fun pending(): List<QueuedWrite> = dao.pending().mapNotNull { it.toQueued() }

	override suspend fun add(type: SyncActionType, itemId: String, time: Instant, supersedes: Set<SyncActionType>) {
		dao.replace(
			PendingWriteEntity(actionType = type.name, itemId = itemId, timeMs = time.toEpochMilliseconds()),
			supersedes.map { it.name }
		)
	}

	override suspend fun remove(id: Long) = dao.remove(id)
	override suspend fun markRefused(id: Long, reason: String) = dao.markRefused(id, reason)
	override suspend fun clear() = dao.clear()

	/**
	 * Every row the cache's `SyncActionEntity` still holds, in its order and with its time, then
	 * those rows removed there. Not merged (a merge is for a new write): a star and an unstar both
	 * moved are both sent, in order, which ends where the user left it.
	 *
	 * Two databases, so two steps and no transaction across them: the insert into `writes.db` (one
	 * transaction there, which records each row as moved, [MovedLegacyWrite]), then the deletes in
	 * `cache.db`. A move cut short between them, or whose deletes the cache database refuses, is
	 * finished by the next one without queueing anything twice, even after a drain has sent and
	 * removed a moved row meanwhile (review minor 5): the record of the move outlives the row.
	 */
	override suspend fun moveLegacy(): Int {
		val dao = legacy ?: return 0
		val rows = dao.getPendingActions()
		if (rows.isEmpty()) return 0
		val queued = this.dao.insertMoved(rows.map {
			PendingWriteEntity(
				actionType = it.actionType.name,
				itemId = it.itemId,
				timeMs = it.time.toEpochMilliseconds(),
				legacyId = it.id.toLong()
			)
		})
		rows.forEach { dao.removeAction(it.id) }
		return queued
	}

	override fun observe(): Flow<List<QueuedWrite>> = dao.observePending().map { rows -> rows.mapNotNull { it.toQueued() } }

	private fun PendingWriteEntity.toQueued(): QueuedWrite? {
		// A type this build does not know (a newer build's row): kept in the table, not sent.
		val type = SyncActionType.entries.firstOrNull { it.name == actionType } ?: return null
		return QueuedWrite(id, type, itemId, Instant.fromEpochMilliseconds(timeMs), refusals, lastError)
	}
}

/** The offline write queue: what is added, how a pass sends it, and what a pull must not overwrite. */
class WriteQueue(
	private val store: WriteQueueStore,
	/** Injectable: host tests have no android.util.Log. */
	private val log: (String, Throwable?) -> Unit = { msg, tr -> Logger.i("WriteQueue", msg, tr) }
) {
	private val lock = Mutex()
	private var moved = false

	/** Every read and write goes through here: the first one moves the cache's old rows over. */
	private suspend fun <T> locked(block: suspend () -> T): T = lock.withLock {
		if (!moved) {
			try {
				val n = store.moveLegacy()
				if (n > 0) log("moved $n pending write(s) out of the cache database", null)
				moved = true
			} catch (e: Exception) {
				if (e is CancellationException) throw e
				// Tried again on the next use; the rows stay where they are meanwhile.
				log("could not move the cache database's pending writes", e)
			}
		}
		block()
	}

	suspend fun enqueue(type: SyncActionType, itemId: String, time: Instant) =
		locked { store.add(type, itemId, time, supersededBy(type)) }

	suspend fun pending(): List<QueuedWrite> = locked { store.pending() }

	/** The queue observed; the first collection moves the cache's old rows over first. */
	fun observe(): Flow<List<QueuedWrite>> = flow {
		locked { }
		emitAll(store.observe())
	}

	suspend fun remove(id: Long) = locked { store.remove(id) }

	/** Logout: the writes belong to the account that made them, and must not go out as the next one. */
	suspend fun clear() = locked { store.clear() }

	/** The overlay a pull applies to what it writes (rule 6). Never fails a pull: unreadable, it is empty. */
	suspend fun overlay(): PendingOverlay = try {
		PendingOverlay(pending())
	} catch (e: Exception) {
		if (e is CancellationException) throw e
		log("pending writes unreadable for the pull's overlay", e)
		PendingOverlay.NONE
	}

	/**
	 * One pass, oldest first (rule 2). A write leaves the queue once [send] returned. A refusal
	 * marks that write and the pass goes on (rule 5); an item the server no longer has drops its
	 * write; nothing answering, or the session refused, ends the pass with everything kept.
	 *
	 * The store is not held while a write is in flight, so a new write can arrive meanwhile: one
	 * that replaces the write being sent removes it, and this pass's removal of the sent id is a
	 * no-op. The replacing write goes out next pass, and the item ends as the user left it.
	 */
	suspend fun drain(send: suspend (QueuedWrite) -> Unit) {
		for (write in pending()) {
			try {
				send(write)
			} catch (e: Exception) {
				if (e is CancellationException) throw e
				when (writeFailure(e)) {
					WriteFailure.STOP -> {
						log("server not reachable; ${write.type} for ${write.itemId} and the rest stay queued", e)
						return
					}
					WriteFailure.GONE -> {
						log("the server has no ${write.itemId}; its ${write.type} is dropped", e)
						locked { store.remove(write.id) }
					}
					WriteFailure.REFUSED -> {
						log("the server refused ${write.type} for ${write.itemId}; it stays, the rest go on", e)
						locked { store.markRefused(write.id, e.message ?: e::class.simpleName ?: "refused") }
					}
				}
				continue
			}
			locked { store.remove(write.id) }
			log("sent ${write.type} for ${write.itemId}", null)
		}
	}
}
