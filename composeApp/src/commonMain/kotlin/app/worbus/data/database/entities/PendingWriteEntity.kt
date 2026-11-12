package app.worbus.data.database.entities

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * One write the server has not had yet (B-373): a star, a rating, a scrobble or a playlist delete.
 * In `writes.db` ([app.worbus.data.database.WriteQueueDatabase]), a database never dropped, because
 * nothing else records it: the cache database is rebuilt from the server, which by definition does
 * not have these.
 *
 * Plain columns only: [actionType] is a [SyncActionType] name, [timeMs] the moment the user did it
 * (a scrobble is sent with it). [legacyId] is the `SyncActionEntity` row of the cache database it
 * was moved from, unique; whether a legacy row was ever moved is [MovedLegacyWrite]'s, which
 * outlives the row being sent.
 */
@Entity(
	indices = [
		Index(value = ["itemId"]),
		Index(value = ["legacyId"], unique = true)
	]
)
data class PendingWriteEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	val actionType: String,
	val itemId: String,
	val timeMs: Long,
	/** How many times the server refused it; it stays, and the others are sent past it. */
	val refusals: Int = 0,
	/** What the last refusal said, for the Pending actions page. */
	val lastError: String? = null,
	val legacyId: Long? = null
)

/**
 * A `SyncActionEntity` row of the cache database that has been moved into the queue (review minor 5),
 * written in the same transaction as the moved row and never deleted. It is what makes the move
 * idempotent: a move cut short after the insert (the cache database refused the delete) and run
 * again after a drain already sent and removed the moved row must not queue it a second time, and
 * [PendingWriteEntity.legacyId]'s uniqueness only holds while the row is still pending. Keyed by the
 * old row's whole identity, not its id alone: a cache database rebuilt by an older build numbers
 * its rows from 1 again, and a new write there must not be taken for one already moved.
 */
@Entity(primaryKeys = ["legacyId", "actionType", "itemId", "timeMs"])
data class MovedLegacyWrite(
	val legacyId: Long,
	val actionType: String,
	val itemId: String,
	val timeMs: Long
)
