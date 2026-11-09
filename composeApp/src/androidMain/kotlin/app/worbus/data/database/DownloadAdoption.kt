package app.worbus.data.database

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import app.worbus.data.database.entities.DownloadSource
import app.worbus.data.database.entities.DownloadStatus
import app.worbus.util.Logger
import java.io.File

/**
 * A downloaded file on disk, as `StorageManager.getDownloadPath` names it: `<songId>.<extension>`
 * in the downloads directory.
 */
internal data class DownloadOnDisk(val songId: String, val path: String, val size: Long, val modifiedAt: Long)

/**
 * The finished downloads in [dir]: every non-empty file named `<songId>.<extension>`. A `.part` is a
 * transfer that never finished (`getTempDownloadPath`) and is not one.
 */
internal fun downloadsOnDisk(dir: File): List<DownloadOnDisk> =
	dir.listFiles().orEmpty()
		.filter { it.isFile && !it.name.endsWith(".part") && it.name.lastIndexOf('.') > 0 && it.length() > 0L }
		.map { DownloadOnDisk(it.name.substringBeforeLast('.'), it.absolutePath, it.length(), it.lastModified()) }
		.sortedBy { it.songId }

/**
 * One row per adopted file. Quality and source are not known any more, so they are the defaults a
 * manual download at original quality has (`maxBitRate` 0, no format, `manual`): a manual row is
 * never removed by a playlist policy, so nothing adopted is deleted on a guess.
 */
internal const val ADOPT_DOWNLOAD_SQL =
	"INSERT OR IGNORE INTO `DownloadEntity` (`songId`, `status`, `progress`, `filePath`, `maxBitRate`, " +
		"`format`, `fileSize`, `error`, `retryCount`, `sourcePolicy`, `createdAt`, `updatedAt`) " +
		"VALUES (?, ?, 1.0, ?, 0, NULL, ?, NULL, 0, ?, ?, ?)"

/** The values [ADOPT_DOWNLOAD_SQL] binds, in order, for [file] adopted at [nowMs]. */
internal fun adoptDownloadArgs(file: DownloadOnDisk, nowMs: Long): List<Any> = listOf(
	file.songId,
	DownloadStatus.DOWNLOADED.name,
	file.path,
	file.size,
	DownloadSource.MANUAL,
	file.modifiedAt.takeIf { it > 0L } ?: nowMs,
	nowMs
)

/**
 * A `downloads.db` that Room has just created adopts the files already on disk (B-306).
 *
 * Its rows are the only record of which files are downloads. When the database is new but the
 * downloads directory is not — the first-open check moved a corrupt file aside, or the file was
 * lost some other way — every file there would be invisible to the app and still on the user's
 * storage. On a first install the directory is empty or absent and this adds nothing.
 */
internal class AdoptDownloadsOnCreate(
	private val downloadsDir: () -> File,
	private val now: () -> Long = System::currentTimeMillis,
	private val warn: (String, Throwable?) -> Unit = { message, e -> Logger.w("DownloadsDb", message, e) }
) : RoomDatabase.Callback() {
	override suspend fun onCreate(connection: SQLiteConnection) {
		val files = try {
			downloadsOnDisk(downloadsDir())
		} catch (e: Exception) {
			warn("downloads.db is new; could not list the downloads directory", e)
			return
		}
		if (files.isEmpty()) return
		val nowMs = now()
		files.forEach { file ->
			connection.prepare(ADOPT_DOWNLOAD_SQL).use { statement ->
				adoptDownloadArgs(file, nowMs).forEachIndexed { i, value ->
					when (value) {
						is String -> statement.bindText(i + 1, value)
						is Long -> statement.bindLong(i + 1, value)
						else -> error("unexpected bind $value")
					}
				}
				statement.step()
			}
		}
		warn("downloads.db is new; adopted ${files.size} downloaded files already on disk", null)
	}
}
