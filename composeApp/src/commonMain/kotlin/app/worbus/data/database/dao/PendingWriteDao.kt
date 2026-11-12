package app.worbus.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow
import app.worbus.data.database.entities.MovedLegacyWrite
import app.worbus.data.database.entities.PendingWriteEntity

/** The offline write queue (B-373), in `writes.db`. Oldest first everywhere: the order is the rule. */
@Dao
interface PendingWriteDao {
	@Query("SELECT * FROM PendingWriteEntity ORDER BY id ASC")
	suspend fun pending(): List<PendingWriteEntity>

	@Query("SELECT * FROM PendingWriteEntity ORDER BY id ASC")
	fun observePending(): Flow<List<PendingWriteEntity>>

	@Insert
	suspend fun insert(row: PendingWriteEntity): Long

	/** The row's id, or -1 when the unique `legacyId` is already held by a moved row still pending. */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertMovedRow(row: PendingWriteEntity): Long

	@Query("SELECT COUNT(*) FROM MovedLegacyWrite WHERE legacyId = :legacyId AND actionType = :actionType AND itemId = :itemId AND timeMs = :timeMs")
	suspend fun movedBefore(legacyId: Long, actionType: String, itemId: String, timeMs: Long): Int

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun recordMoved(moved: MovedLegacyWrite)

	/**
	 * A move from the cache database, in one transaction of this database: each row not moved before
	 * ([MovedLegacyWrite]) is queued and recorded as moved; one moved before, whether it is still
	 * pending or was sent since, is skipped. Returns how many were queued now.
	 *
	 * Review nit 7: a row whose `legacyId` a moved row still pending holds (a cache.db rebuilt by an
	 * older build numbers from 1 again) is not that row ([movedBefore] compares the whole identity), so
	 * it is queued without the `legacyId`: never recorded as moved unless it was. [MovedLegacyWrite]
	 * is what keeps the move idempotent, not the unique index.
	 */
	@Transaction
	suspend fun insertMoved(rows: List<PendingWriteEntity>): Int {
		var queued = 0
		for (row in rows) {
			val legacyId = row.legacyId ?: continue
			if (movedBefore(legacyId, row.actionType, row.itemId, row.timeMs) > 0) continue
			if (insertMovedRow(row) == -1L) insert(row.copy(legacyId = null))
			recordMoved(MovedLegacyWrite(legacyId, row.actionType, row.itemId, row.timeMs))
			queued++
		}
		return queued
	}

	@Query("DELETE FROM PendingWriteEntity WHERE itemId = :itemId AND actionType IN (:actionTypes)")
	suspend fun removeFor(itemId: String, actionTypes: List<String>)

	/** The new write replaces the ones it [supersedes] on the same item, in one transaction. */
	@Transaction
	suspend fun replace(row: PendingWriteEntity, supersedes: List<String>): Long {
		if (supersedes.isNotEmpty()) removeFor(row.itemId, supersedes)
		return insert(row)
	}

	@Query("DELETE FROM PendingWriteEntity WHERE id = :id")
	suspend fun remove(id: Long)

	@Query("UPDATE PendingWriteEntity SET refusals = refusals + 1, lastError = :error WHERE id = :id")
	suspend fun markRefused(id: Long, error: String)

	@Query("DELETE FROM PendingWriteEntity")
	suspend fun clear()
}
