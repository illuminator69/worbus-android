package app.worbus.data.database

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import app.worbus.data.database.dao.DownloadDao
import app.worbus.data.database.dao.PendingWriteDao
import app.worbus.data.database.entities.DownloadEntity
import app.worbus.data.database.entities.PendingWriteEntity

/**
 * The schema version of `downloads.db`, for Room's annotation and for the first-open check, which
 * moves aside a file a newer build wrote (Room has no path down and this database no destructive
 * fallback). A bump needs a migration in [DOWNLOAD_MIGRATIONS] (`DownloadMigrationChainTest`).
 */
const val DOWNLOAD_DB_VERSION = 6

@Database(
	// v4 adds the download-center columns. Migrated, NOT dropped — see [MIGRATION_DOWNLOAD_3_4].
	// v5 adds the (status, songId) index — [MIGRATION_DOWNLOAD_4_5], additive.
	// v6 adds the offline write queue (B-373) — [MIGRATION_DOWNLOAD_5_6], a new table.
	version = DOWNLOAD_DB_VERSION,
	entities = [DownloadEntity::class, PendingWriteEntity::class]
)
@ConstructedBy(DownloadDatabaseConstructor::class)
abstract class DownloadDatabase : RoomDatabase() {
	abstract fun downloadDao(): DownloadDao
	abstract fun pendingWriteDao(): PendingWriteDao
}

@Suppress("KotlinNoActualForExpect")
expect object DownloadDatabaseConstructor : RoomDatabaseConstructor<DownloadDatabase> {
	override fun initialize(): DownloadDatabase
}

