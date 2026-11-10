package app.worbus.domain.manager

import app.worbus.data.database.dao.SyncActionDao
import app.worbus.data.database.entities.SyncActionEntity
import app.worbus.data.database.entities.SyncActionType
import app.worbus.util.Logger
import kotlin.time.Instant

/** One write waiting for the server: a star, a rating, a scrobble or a playlist delete. */
data class QueuedWrite(
	val id: Long,
	val type: SyncActionType,
	val itemId: String,
	val time: Instant
)

/** Where the queue is kept. */
interface WriteQueueStore {
	/** Oldest first. */
	suspend fun pending(): List<QueuedWrite>
	suspend fun add(type: SyncActionType, itemId: String, time: Instant)
	suspend fun remove(id: Long)
}

/** The queue as it was kept: the `SyncActionEntity` table of the cache database. */
class CacheWriteQueueStore(private val dao: SyncActionDao) : WriteQueueStore {
	override suspend fun pending(): List<QueuedWrite> = dao.getPendingActions().map {
		QueuedWrite(it.id.toLong(), it.actionType, it.itemId, it.time)
	}

	override suspend fun add(type: SyncActionType, itemId: String, time: Instant) {
		dao.enqueue(SyncActionEntity(actionType = type, itemId = itemId, time = time))
	}

	override suspend fun remove(id: Long) = dao.removeAction(id.toInt())
}

/** The offline write queue: what is added, and how a pass sends it. */
class WriteQueue(
	private val store: WriteQueueStore,
	/** Injectable: host tests have no android.util.Log. */
	private val log: (String, Throwable?) -> Unit = { msg, tr -> Logger.i("SyncManager", msg, tr) }
) {
	suspend fun enqueue(type: SyncActionType, itemId: String, time: Instant) = store.add(type, itemId, time)

	/** One pass, oldest first. A write leaves the queue once [send] returned. */
	suspend fun drain(send: suspend (QueuedWrite) -> Unit) {
		for (write in store.pending()) {
			try {
				send(write)
				store.remove(write.id)
				log("Successfully synced ${write.type} for ${write.itemId}", null)
			} catch (e: Exception) {
				log("Network failed. Action left in queue.", e)
				break
			}
		}
	}
}
