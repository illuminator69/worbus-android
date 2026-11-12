package app.worbus.data.database

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.migration.Migration
import app.worbus.data.database.dao.PendingWriteDao
import app.worbus.data.database.entities.MovedLegacyWrite
import app.worbus.data.database.entities.PendingWriteEntity

/**
 * The schema version of `writes.db`, the offline write queue's own database (B-373; review major 3).
 *
 * Why a file of its own: the queue is not cache, so it cannot live in `cache.db` (rebuilt from the
 * server, which by definition does not have these writes), and it must not change `downloads.db`
 * (Worbus 0.7.0 opens that at version 5 with a destructive fallback, so a version above 5 written
 * here would make a rollback to 0.7.0 drop every download record). A rollback now costs the queue
 * at most: 0.7.0 never opens this file.
 *
 * **Migration policy** (the rule a future change to [PendingWriteEntity] or [MovedLegacyWrite]
 * follows): never a destructive fallback, since what this file holds exists nowhere else; every
 * version bump adds one migration to [WRITES_MIGRATIONS] and exports its schema
 * (`WritesDbPolicyTest` fails first when either is missing); a file a NEWER build wrote is moved
 * aside whole by the first-open check (`writes.db.newer-<ms>`), kept for that build, never opened
 * and never dropped; a corrupt file is moved aside the same way.
 */
const val WRITES_DB_VERSION = 1

@Database(
	version = WRITES_DB_VERSION,
	entities = [PendingWriteEntity::class, MovedLegacyWrite::class]
)
@ConstructedBy(WriteQueueDatabaseConstructor::class)
abstract class WriteQueueDatabase : RoomDatabase() {
	abstract fun pendingWriteDao(): PendingWriteDao
}

@Suppress("KotlinNoActualForExpect")
expect object WriteQueueDatabaseConstructor : RoomDatabaseConstructor<WriteQueueDatabase> {
	override fun initialize(): WriteQueueDatabase
}

/** Every `writes.db` migration, oldest first: a chain from 1 to [WRITES_DB_VERSION] with no gap. None yet. */
val WRITES_MIGRATIONS: Array<Migration> = emptyArray()
