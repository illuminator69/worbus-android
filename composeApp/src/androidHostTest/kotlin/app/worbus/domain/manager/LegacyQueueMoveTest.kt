package app.worbus.domain.manager

import app.worbus.data.database.dao.PendingWriteDao
import app.worbus.data.database.dao.SyncActionDao
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
 * are moved to downloads.db on the queue's first use, in their order and with their times, and a
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
		override suspend fun insertMoved(rows: List<PendingWriteEntity>) {
			for (row in rows) if (row.legacyId == null || this.rows.none { it.legacyId == row.legacyId }) insert(row)
		}
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
}
