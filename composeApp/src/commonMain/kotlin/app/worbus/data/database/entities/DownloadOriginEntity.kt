package app.worbus.data.database.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Q-165: what a downloaded file was made from, and whether the server has said it is gone. One row per
 * downloaded song, in `writes.db` ([app.worbus.data.database.WriteQueueDatabase] 2), beside the write
 * queue: like the queue it is the device's own record, which the cache is rebuilt without; and not in
 * `downloads.db`, whose version 0.7.0 pins at 5 (a bump would cost every download record on a rollback).
 *
 * [origin] is `songOrigin` of the song as Navidrome described it when the file was fetched (or, for a
 * file fetched before this build, when it was first seen here); "" when the song was never seen. A
 * download whose song now reads differently was changed on the server and is fetched again.
 * [goneSeenAt] is when (on the upkeep's own clock, `UpkeepMemory.clockMs`, since 3) a complete pull and
 * Navidrome's own `getSong` first said the song is gone; 0 while it is not. [gonePulls] counts the
 * complete pulls that have said so since.
 *
 * Since 3 (the follow-up's major 2): [server] and [account] are WHOSE the download is, the Navidrome
 * server (its address, normalised) and the user it was fetched or first seen under. Only a record of the
 * current session is ever judged; "" for both is a record from 2 (or one nobody could place), never
 * judged gone, until a pull under the current session finds its song in Room.
 */
@Entity(tableName = "DownloadOrigin")
data class DownloadOriginEntity(
	@PrimaryKey val songId: String,
	val origin: String,
	val recordedAt: Long,
	val goneSeenAt: Long = 0L,
	@ColumnInfo(defaultValue = "''") val server: String = "",
	@ColumnInfo(defaultValue = "''") val account: String = "",
	@ColumnInfo(defaultValue = "0") val gonePulls: Int = 0
)
