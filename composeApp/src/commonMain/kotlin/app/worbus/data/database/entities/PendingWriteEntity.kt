package app.worbus.data.database.entities

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * One write the server has not had yet (B-373): a star, a rating, a scrobble or a playlist delete.
 * In `downloads.db`, the database that is never dropped, because nothing else records it: the
 * cache database is rebuilt from the server, which by definition does not have these.
 *
 * Plain columns only (`downloads.db` has no type converters): [actionType] is a [SyncActionType]
 * name, [timeMs] the moment the user did it (a scrobble is sent with it). [legacyId] is the
 * `SyncActionEntity` row of the cache database it was moved from, unique, so a move cut short and
 * run again never queues one write twice.
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
