package app.worbus.data.database

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.worbus.data.database.dao.DownloadOriginDao
import app.worbus.data.database.dao.PendingWriteDao
import app.worbus.data.database.entities.DownloadOriginEntity
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
const val WRITES_DB_VERSION = 3

/*
 * Version 2 (Q-165, 2026-11-12): `DownloadOrigin` ([DownloadOriginEntity]), what each download was
 * made from. The device's own record like the queue, so it shares the file and its policy.
 * Version 3 (the Q-165 follow-up): whose each download is (`server`, `account`) and how many complete
 * pulls have called it gone (`gonePulls`); `goneSeenAt` is the upkeep's clock from 3 on.
 */
@Database(
	version = WRITES_DB_VERSION,
	entities = [PendingWriteEntity::class, MovedLegacyWrite::class, DownloadOriginEntity::class]
)
@ConstructedBy(WriteQueueDatabaseConstructor::class)
abstract class WriteQueueDatabase : RoomDatabase() {
	abstract fun pendingWriteDao(): PendingWriteDao
	abstract fun downloadOriginDao(): DownloadOriginDao
}

@Suppress("KotlinNoActualForExpect")
expect object WriteQueueDatabaseConstructor : RoomDatabaseConstructor<WriteQueueDatabase> {
	override fun initialize(): WriteQueueDatabase
}

/** 1 → 2 (Q-165): the `DownloadOrigin` table, empty; nothing else moves. Its SQL is held to `2.json`. */
val MIGRATION_WRITES_1_2 = object : Migration(1, 2) {
	override suspend fun migrate(connection: SQLiteConnection) {
		connection.execSQL(
			"CREATE TABLE IF NOT EXISTS `DownloadOrigin` (`songId` TEXT NOT NULL, `origin` TEXT NOT NULL, " +
				"`recordedAt` INTEGER NOT NULL, `goneSeenAt` INTEGER NOT NULL, PRIMARY KEY(`songId`))"
		)
	}
}

/**
 * 2 → 3 (the Q-165 follow-up): three columns on `DownloadOrigin`, every existing row "nobody's" ('' and '')
 * so it is never judged gone until the current session proves it; and every gone mark cleared, since 2
 * kept it on the wall clock and 3 keeps it on the upkeep's own. Its SQL is held to `3.json`.
 */
val MIGRATION_WRITES_2_3 = object : Migration(2, 3) {
	override suspend fun migrate(connection: SQLiteConnection) {
		connection.execSQL("ALTER TABLE `DownloadOrigin` ADD COLUMN `server` TEXT NOT NULL DEFAULT ''")
		connection.execSQL("ALTER TABLE `DownloadOrigin` ADD COLUMN `account` TEXT NOT NULL DEFAULT ''")
		connection.execSQL("ALTER TABLE `DownloadOrigin` ADD COLUMN `gonePulls` INTEGER NOT NULL DEFAULT 0")
		connection.execSQL("UPDATE `DownloadOrigin` SET `goneSeenAt` = 0")
	}
}

/** Every `writes.db` migration, oldest first: a chain from 1 to [WRITES_DB_VERSION] with no gap. */
val WRITES_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_WRITES_1_2, MIGRATION_WRITES_2_3)
