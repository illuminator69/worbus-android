package app.worbus.domain.manager

import app.worbus.data.database.dao.PendingWriteDao
import app.worbus.data.database.dao.SyncActionDao
import app.worbus.data.database.entities.MovedLegacyWrite
import app.worbus.data.database.entities.PendingWriteEntity
import app.worbus.data.database.entities.SyncActionEntity
import app.worbus.data.database.entities.SyncActionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/*
 * B-373: an upgrade finds the writes still in the cache database's SyncActionEntity table. They
 * are moved to writes.db on the queue's first use, in their order and with their times, and a
 * move cut short between "inserted here" and "removed there" never queues one twice. The fakes keep
 * what the real tables enforce: autoincrement ids, oldest first, the unique `legacyId` with IGNORE.
 */
class LegacyQueueMoveTest {
	private class LegacyDao(rows: List<SyncActionEntity>) : SyncActionDao {
		val rows = rows.toMutableList()
		var failRemoves = false
		override suspend fun enqueue(action: SyncActionEntity) { rows += action }
		override suspend fun getPendingActions(): List<SyncActionEntity> = rows.sortedBy { it.id }
		override fun observePendingActions(): Flow<List<SyncActionEntity>> = MutableStateFlow(rows.toList())
		override suspend fun removeAction(id: Int) {
			if (failRemoves) error("disk full")
			rows.removeAll { it.id == id }
		}
		override suspend fun clearAllActions() = rows.clear()
	}

	private class WriteDao : PendingWriteDao {
		val rows = mutableListOf<PendingWriteEntity>()
		private var next = 1L
		override suspend fun pending() = rows.sortedBy { it.id }
		override fun observePending(): Flow<List<PendingWriteEntity>> = MutableStateFlow(rows.toList())
		override suspend fun insert(row: PendingWriteEntity): Long {
			val id = next++
			rows += row.copy(id = id)
			return id
		}
		/** `writes.db`'s MovedLegacyWrite table: the move's record, never deleted. */
		val moved = mutableSetOf<MovedLegacyWrite>()
		override suspend fun insertMovedRow(row: PendingWriteEntity): Long =
			if (row.legacyId == null || rows.none { it.legacyId == row.legacyId }) insert(row) else -1L
		override suspend fun movedBefore(legacyId: Long, actionType: String, itemId: String, timeMs: Long): Int =
			if (MovedLegacyWrite(legacyId, actionType, itemId, timeMs) in moved) 1 else 0
		override suspend fun recordMoved(moved: MovedLegacyWrite) {
			this.moved += moved
		}
		// insertMoved is the DAO's own (a default method): the test runs the real move logic.
		override suspend fun removeFor(itemId: String, actionTypes: List<String>) {
			rows.removeAll { it.itemId == itemId && it.actionType in actionTypes }
		}
		override suspend fun remove(id: Long) { rows.removeAll { it.id == id } }
		override suspend fun markRefused(id: Long, error: String) {}
		override suspend fun clear() = rows.clear()
	}

	private val t1 = Instant.fromEpochMilliseconds(1_700_000_000_123)
	private val t2 = Instant.fromEpochMilliseconds(1_700_000_300_456)
	private val legacyRows = listOf(
		SyncActionEntity(7, SyncActionType.SCROBBLE, "s-1", t1),
		SyncActionEntity(8, SyncActionType.STAR, "al-1", t1),
		SyncActionEntity(9, SyncActionType.UNSTAR, "al-1", t2)
	)

	@Test
	fun theCacheRowsMoveInOrderWithTheirTimes() = runBlocking {
		val legacy = LegacyDao(legacyRows)
		val q = WriteQueue(RoomWriteQueueStore(WriteDao(), legacy), log = { _, _ -> })
		assertEquals(
			listOf(Triple(SyncActionType.SCROBBLE, "s-1", t1), Triple(SyncActionType.STAR, "al-1", t1), Triple(SyncActionType.UNSTAR, "al-1", t2)),
			q.pending().map { Triple(it.type, it.itemId, it.time) }
		)
		assertEquals(emptyList(), legacy.rows, "nothing is left in the cache database")
	}

	@Test
	fun aMoveCutShortIsFinishedWithoutQueueingAnyWriteTwice() = runBlocking {
		val legacy = LegacyDao(legacyRows).apply { failRemoves = true }
		val writes = WriteDao()
		val first = WriteQueue(RoomWriteQueueStore(writes, legacy), log = { _, _ -> })
		first.pending()
		assertEquals(3, writes.rows.size, "inserted here before the removes there failed")
		assertEquals(3, legacy.rows.size)

		legacy.failRemoves = false
		val next = WriteQueue(RoomWriteQueueStore(writes, legacy), log = { _, _ -> }) // the next launch
		assertEquals(listOf("s-1", "al-1", "al-1"), next.pending().map { it.itemId }, "no duplicate scrobble")
		assertEquals(emptyList(), legacy.rows)
	}

	@Test
	fun aWriteMadeBeforeTheFirstReadIsQueuedAfterTheMovedOnes() = runBlocking {
		val q = WriteQueue(RoomWriteQueueStore(WriteDao(), LegacyDao(legacyRows)), log = { _, _ -> })
		q.enqueue(SyncActionType.STAR, "al-1", t2)
		assertEquals(listOf(SyncActionType.SCROBBLE, SyncActionType.STAR), q.pending().map { it.type }, "the new star replaces both moved star-family writes, after the scrobble")
	}

	/**
	 * Review minor 5: the cache database refuses the deletes, so the move is not finished; a drain
	 * sends the moved scrobble and removes it here, and the next move must not queue it again.
	 * Uniqueness that lasts only while the row is pending cannot tell "moved and sent" from "never
	 * moved".
	 */
	@Test
	fun aWriteMovedAndSentIsNeverMovedAgain() = runBlocking {
		val legacy = LegacyDao(listOf(SyncActionEntity(7, SyncActionType.SCROBBLE, "s-1", t1))).apply { failRemoves = true }
		val writes = WriteDao()
		val sent = mutableListOf<String>()
		WriteQueue(RoomWriteQueueStore(writes, legacy), log = { _, _ -> }).drain { sent += "${it.type}:${it.itemId}" }
		assertEquals(1, legacy.rows.size, "the cache database still holds the row")

		legacy.failRemoves = false
		WriteQueue(RoomWriteQueueStore(writes, legacy), log = { _, _ -> }).drain { sent += "${it.type}:${it.itemId}" } // the next launch
		assertEquals(listOf("SCROBBLE:s-1"), sent, "one listen, sent once")
		assertEquals(emptyList(), legacy.rows, "and the move is finished")
	}

	/** A cache database rebuilt by an older build numbers its rows from 1 again: a new write there is not taken for one moved before. */
	@Test
	fun aReusedLegacyIdWithOtherContentIsStillMoved() = runBlocking {
		val writes = WriteDao()
		val sent = mutableListOf<String>()
		WriteQueue(RoomWriteQueueStore(writes, LegacyDao(listOf(SyncActionEntity(1, SyncActionType.SCROBBLE, "s-1", t1)))), log = { _, _ -> })
			.drain { sent += it.itemId }
		WriteQueue(RoomWriteQueueStore(writes, LegacyDao(listOf(SyncActionEntity(1, SyncActionType.SCROBBLE, "s-2", t2)))), log = { _, _ -> })
			.drain { sent += it.itemId }
		assertEquals(listOf("s-1", "s-2"), sent)
	}

	/**
	 * Review (fix round) nit 7: rows moved and left unsent, a rollback to 0.7.0 (its cache.db rebuilt,
	 * so ids start at 1 again), a new write there, forward again. The new row 1 collides with the
	 * still-pending moved row 1 on the unique `legacyId`; the IGNORE dropped it, it was recorded as
	 * moved all the same and then deleted from cache.db: the write was lost.
	 */
	@Test
	fun aLegacyIdStillPendingHereDoesNotSwallowTheNewRowWithThatId() = runBlocking {
		val writes = WriteDao()
		WriteQueue(RoomWriteQueueStore(writes, LegacyDao(listOf(SyncActionEntity(1, SyncActionType.SCROBBLE, "s-1", t1)))), log = { _, _ -> })
			.pending() // moved, not sent
		val rebuilt = LegacyDao(listOf(SyncActionEntity(1, SyncActionType.STAR, "al-9", t2)))
		val next = WriteQueue(RoomWriteQueueStore(writes, rebuilt), log = { _, _ -> })
		assertEquals(listOf("SCROBBLE:s-1", "STAR:al-9"), next.pending().map { "${it.type}:${it.itemId}" }, "both writes are queued")
		assertEquals(emptyList(), rebuilt.rows)
	}
}
