package app.worbus.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow
import app.worbus.data.database.entities.PendingWriteEntity

/** The offline write queue (B-373), in `downloads.db`. Oldest first everywhere: the order is the rule. */
@Dao
interface PendingWriteDao {
	@Query("SELECT * FROM PendingWriteEntity ORDER BY id ASC")
	suspend fun pending(): List<PendingWriteEntity>

	@Query("SELECT * FROM PendingWriteEntity ORDER BY id ASC")
	fun observePending(): Flow<List<PendingWriteEntity>>

	@Insert
	suspend fun insert(row: PendingWriteEntity): Long

	/** A move from the cache database: a row already moved ([PendingWriteEntity.legacyId]) is skipped. */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertMoved(rows: List<PendingWriteEntity>)

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
