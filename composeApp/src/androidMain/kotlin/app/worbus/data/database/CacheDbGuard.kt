package app.worbus.data.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import app.worbus.util.Logger
import java.io.File

/**
 * A corrupt `cache.db` is dropped before Room opens it, never crashed on (B-167).
 *
 * The bundled SQLite driver bypasses the framework's `DatabaseErrorHandler` (which would delete a
 * corrupt file), and `fallbackToDestructiveMigration` covers a version change only. So a cache
 * left malformed by a kill mid-write threw `SQLiteException … (code 11)` from the first DAO read
 * (it was `LbBotManager`'s, in a coroutine nothing caught) on every launch.
 *
 * What it costs: the cache only. The library re-syncs, the lb-bot mirror re-pulls, and saved
 * queues come back from the hub; queued offline stars and scrobbles that never reached the
 * server are lost with it. `downloads.db` and the credentials are never touched here.
 */
internal sealed interface CacheDbCheck {
	/** No file yet (a new install, or the last one was moved aside). */
	data object Missing : CacheDbCheck

	/** `quick_check` said `ok`: the file is left exactly as it was. */
	data object Healthy : CacheDbCheck

	/**
	 * Not a database, malformed, or written by a newer build (`newestVersion`), and gone from where
	 * Room opens it: moved to [movedTo], or, when
	 * it could not be moved, deleted ([movedTo] null). Room will create a fresh one.
	 */
	data class Quarantined(val movedTo: File?, val why: String) : CacheDbCheck

	/**
	 * Not a database or malformed, and it could be neither moved nor deleted: Room opens it as it
	 * is, and fails on it as it did before this check (the review's finding 4).
	 */
	data class Stuck(val why: String) : CacheDbCheck

	/** The check itself failed for another reason (busy, I/O): the file is left for Room. */
	data class Unchecked(val why: String) : CacheDbCheck
}

/** SQLite's CORRUPT (11) and NOTADB (26), in the bundled driver's words, the framework's and sqlite-jdbc's. */
private val CORRUPTION = Regex(
	"""error code: (11|26)\b|\(code (11|26)\b|sqlite_(corrupt|notadb)|malformed|not a database""",
	RegexOption.IGNORE_CASE
)

/** Does [t] (or a cause of it) say the file is corrupt or not a database? */
internal fun isSqliteCorruption(t: Throwable): Boolean {
	var current: Throwable? = t
	var depth = 0
	while (current != null && depth < 16) {
		if (CORRUPTION.containsMatchIn(current.message ?: "")) return true
		current = current.cause
		depth++
	}
	return false
}

/**
 * Check [db] with [quickCheck] (which runs `PRAGMA quick_check(1)` and returns its first row), and
 * move it aside when it is not a sound database.
 *
 * Moved, not deleted: `cache.db` becomes `cache.db.corrupt-<nowMs>` (its `-wal` and `-shm` beside
 * it, named so SQLite would pair them), and only the newest such copy is kept, so a crash loop
 * cannot fill the disk. Deleted only when it will not move; [CacheDbCheck.Stuck] when it will do
 * neither. [move], [delete] and [warn] are the file system's and the log, replaceable for the test of that case.
 */
internal fun guardCacheDb(
	db: File,
	nowMs: Long,
	quickCheck: (File) -> String,
	move: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
	delete: (File) -> Boolean = { it.delete() },
	warn: (String) -> Unit = { Logger.w("CacheDb", it) },
	newestVersion: Int? = null,
	userVersion: (File) -> Int = ::bundledUserVersion
): CacheDbCheck {
	if (!db.exists()) return CacheDbCheck.Missing
	val why = try {
		val answer = quickCheck(db)
		if (answer.trim().equals("ok", ignoreCase = true)) return newerThanDeclared(db, nowMs, newestVersion, userVersion, move, delete, warn)
		"quick_check: ${answer.take(200)}"
	} catch (e: Exception) {
		if (!isSqliteCorruption(e)) return CacheDbCheck.Unchecked("${e::class.simpleName}: ${e.message}")
		"${e::class.simpleName}: ${e.message}"
	}
	return quarantined(quarantineCacheDb(db, nowMs, move, delete, warn), why)
}

private fun quarantined(moved: Quarantine, why: String): CacheDbCheck =
	when (moved) {
		Quarantine.Stuck -> CacheDbCheck.Stuck(why)
		Quarantine.Deleted -> CacheDbCheck.Quarantined(null, why)
		is Quarantine.Moved -> CacheDbCheck.Quarantined(moved.to, why)
	}

/**
 * A sound file whose `user_version` is above [newestVersion] was written by a newer build (a dev
 * build of a branch that bumped the schema, with a release installed over it: the versionCode is
 * pinned, so Android takes it). Room has no path down, and a database with no destructive fallback
 * (`downloads.db`, B-365) then throws at its first open, at every launch (the review of B-306/365).
 * Moved aside as `<name>.newer-<nowMs>`, whole, for that newer build; Room creates it afresh and
 * `downloads.db` adopts its files ([AdoptDownloadsOnCreate]). Null [newestVersion]: not checked
 * (`cache.db`, whose destructive fallback covers a downgrade).
 */
private fun newerThanDeclared(
	db: File,
	nowMs: Long,
	newestVersion: Int?,
	userVersion: (File) -> Int,
	move: (File, File) -> Boolean,
	delete: (File) -> Boolean,
	warn: (String) -> Unit
): CacheDbCheck {
	if (newestVersion == null) return CacheDbCheck.Healthy
	val found = try {
		userVersion(db)
	} catch (e: Exception) {
		return CacheDbCheck.Unchecked("user_version: ${e::class.simpleName}: ${e.message}")
	}
	if (found <= newestVersion) return CacheDbCheck.Healthy
	val why = "user_version $found is newer than this build's $newestVersion (a newer build wrote it)"
	return quarantined(quarantineCacheDb(db, nowMs, move, delete, warn, kind = "newer"), why)
}

/** What became of `cache.db` itself; its `-wal`, `-shm` and `-journal` are logged, not reported. */
internal sealed interface Quarantine {
	data class Moved(val to: File) : Quarantine
	data object Deleted : Quarantine
	data object Stuck : Quarantine
}

/**
 * Move [db] and its `-wal` / `-shm` / `-journal` aside as `<name>.<kind>-<nowMs>` (`corrupt`, or
 * `newer` for a file a newer build wrote); drop older such copies of the same kind. A file that
 * will not move is deleted, since Room would open it again where it is; one that will do neither
 * is left, and said so.
 */
internal fun quarantineCacheDb(
	db: File,
	nowMs: Long,
	move: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
	delete: (File) -> Boolean = { it.delete() },
	warn: (String) -> Unit = { Logger.w("CacheDb", it) },
	kind: String = "corrupt"
): Quarantine {
	val dir = db.absoluteFile.parentFile
	val prefix = "${db.name}.$kind-"
	dir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
	val target = File(dir, "$prefix$nowMs")
	var result: Quarantine = Quarantine.Moved(target)
	for (suffix in listOf("", "-wal", "-shm", "-journal")) {
		val from = File(dir, db.name + suffix)
		if (!from.exists()) continue
		val to = File(dir, target.name + suffix)
		if (move(from, to)) continue
		val gone = delete(from)
		if (gone) {
			warn("could not move ${from.name} aside; deleted it")
		} else {
			warn("could not move ${from.name} aside, nor delete it; it is left where it is")
		}
		if (suffix == "") result = if (gone) Quarantine.Deleted else Quarantine.Stuck
	}
	return result
}

/**
 * `PRAGMA quick_check(1)` through the driver Room uses, on a connection closed before Room opens.
 *
 * Read-only, and that is load-bearing (the VM walk of B-306): closing the last READ-WRITE connection
 * to a WAL database checkpoints the WAL into the main file, truncates it and deletes the WAL, so a
 * read-write check rewrote the very file it then moved aside (24 KiB kept of 64, the WAL lost). A
 * read-only connection never checkpoints: what is moved aside is the file and its WAL as found.
 */
internal fun bundledQuickCheck(file: File): String =
	BundledSQLiteDriver().open(file.absolutePath, SQLITE_OPEN_READONLY).use { connection ->
		connection.prepare("PRAGMA quick_check(1)").use { statement ->
			if (statement.step()) statement.getText(0) else ""
		}
	}

/** `PRAGMA user_version` (the schema version Room wrote) through the driver Room uses; read-only as above. */
internal fun bundledUserVersion(file: File): Int =
	BundledSQLiteDriver().open(file.absolutePath, SQLITE_OPEN_READONLY).use { connection ->
		connection.prepare("PRAGMA user_version").use { statement ->
			if (statement.step()) statement.getLong(0).toInt() else 0
		}
	}

/**
 * [delegate], with [check] run once before its first connection opens (B-167, the N9 walk).
 *
 * The check used to run where Koin builds `CacheDatabase`, i.e. on the main thread at `startKoin`
 * (the `createdAtStart` managers take DAOs), and `Room.build()` opens nothing: Room's first open
 * happens later, inside the first DAO call's coroutine. So the check put a whole first open (the
 * bundled driver's native library load included) on the main thread, 254 to 460 ms on the AVD,
 * whatever the file's size. Here it runs on the thread of Room's first open instead, and every
 * open waits for it, so no connection, and so no DAO, can reach the file before it is checked.
 * A check that throws still counts as done: Room then opens as it would have without it.
 */
internal class CheckedFirstOpenDriver(
	private val delegate: SQLiteDriver,
	private val check: () -> Unit
) : SQLiteDriver {
	@Volatile
	private var checked = false
	private val lock = Any()

	override fun open(fileName: String): SQLiteConnection {
		if (!checked) {
			synchronized(lock) {
				if (!checked) {
					try {
						check()
					} finally {
						checked = true
					}
				}
			}
		}
		return delegate.open(fileName)
	}
}

/**
 * The check as the app runs it, on the thread of Room's first open of `cache.db`
 * ([CheckedFirstOpenDriver]), before any connection exists. One log line under the tag `CacheDb` with what it found and how long it took.
 */
internal fun checkCacheDbAtOpen(db: File) = checkDbAtOpen(db, "CacheDb")

/**
 * The same check for any Room file: `cache.db` (B-167) and `downloads.db` (B-306), each under its own
 * log [tag]. A file that fails it is moved aside and Room creates it afresh; for `downloads.db` the
 * files already on disk are then adopted by [AdoptDownloadsOnCreate].
 */
internal fun checkDbAtOpen(db: File, tag: String, newestVersion: Int? = null) {
	val started = System.nanoTime()
	val sizeKb = if (db.exists()) db.length() / 1024 else 0
	val warn: (String) -> Unit = { Logger.w(tag, it) }
	val result = try {
		guardCacheDb(db, System.currentTimeMillis(), ::bundledQuickCheck, warn = warn, newestVersion = newestVersion)
	} catch (e: Exception) {
		CacheDbCheck.Unchecked("${e::class.simpleName}: ${e.message}")
	}
	val ms = (System.nanoTime() - started) / 1_000_000
	val where = Thread.currentThread().name
	val name = db.name
	when (result) {
		CacheDbCheck.Missing -> Logger.i(tag, "$name check: no file yet ($ms ms, thread $where)")
		CacheDbCheck.Healthy -> Logger.i(tag, "$name check: ok in $ms ms ($sizeKb KiB, thread $where)")
		is CacheDbCheck.Quarantined -> Logger.w(
			tag,
			"$name failed its check (${result.why}); " +
				(result.movedTo?.let { "moved aside to ${it.name}" } ?: "could not be moved, deleted it") +
				", Room creates it afresh ($ms ms, $sizeKb KiB, thread $where)"
		)
		is CacheDbCheck.Stuck -> Logger.e(
			tag,
			"$name failed its check (${result.why}) and could be neither moved nor deleted; " +
				"Room opens it as it is ($ms ms, $sizeKb KiB, thread $where)"
		)
		is CacheDbCheck.Unchecked -> Logger.w(tag, "$name could not be checked (${result.why}); left as it is ($ms ms, thread $where)")
	}
}
