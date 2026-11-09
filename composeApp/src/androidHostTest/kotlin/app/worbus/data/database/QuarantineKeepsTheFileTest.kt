package app.worbus.data.database

import app.worbus.sourceFile
import org.sqlite.SQLiteConfig
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/*
 * The VM walk (2026-11-09, B-306): the moved-aside `downloads.db.corrupt-…` was 24 KiB, not the
 * 64 KiB of garbage written, rewritten from byte 8193 on, and its 128 KiB `-wal` was gone. Cause:
 * the first-open check opened the file READ-WRITE, and when the last read-write connection to a WAL
 * database closes, SQLite checkpoints the WAL into the main file, truncates it to the WAL's page
 * count and deletes the WAL. So the check rewrote the file it was about to keep "as found". A
 * read-only connection never checkpoints. The app's check runs through the bundled driver (Android
 * natives); these show the mechanism through sqlite-jdbc and read the app's open flags.
 */
class QuarantineKeepsTheFileTest {
	private val dir: File = Files.createTempDirectory("quarantine").toFile()

	@AfterTest
	fun cleanUp() {
		dir.deleteRecursively()
	}

	/**
	 * `downloads.db` as a kill can leave it with a WAL whose frames are not all checkpointed, then
	 * its main file overwritten with garbage (the walk's step). Its -wal and -shm are as left.
	 */
	private fun corruptWithWal(): Triple<File, ByteArray, ByteArray> {
		val src = File(dir, "src.db")
		DriverManager.getConnection("jdbc:sqlite:${src.absolutePath}").use { c ->
			c.createStatement().use { s ->
				s.execute("PRAGMA journal_mode=WAL")
				s.execute("PRAGMA wal_autocheckpoint=0")
				s.executeUpdate("CREATE TABLE t(a TEXT)")
				repeat(40) { s.executeUpdate("INSERT INTO t VALUES ('${"x".repeat(500)}')") }
				s.execute("PRAGMA wal_checkpoint(PASSIVE)")
				repeat(20) { s.executeUpdate("INSERT INTO t VALUES ('${"z".repeat(500)}')") }
			}
			for (suffix in listOf("", "-wal", "-shm")) File(src.path + suffix).copyTo(File(dir, "downloads.db$suffix"))
		}
		val db = File(dir, "downloads.db")
		val garbage = Random(306).nextBytes(64 * 1024)
		db.writeBytes(garbage)
		return Triple(db, garbage, File(dir, "downloads.db-wal").readBytes())
	}

	private fun quickCheck(file: File, readOnly: Boolean): String {
		val properties = SQLiteConfig().apply { setReadOnly(readOnly) }.toProperties()
		return DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}", properties).use { connection ->
			connection.createStatement().use { statement ->
				statement.executeQuery("PRAGMA quick_check(1)").use { rows -> if (rows.next()) rows.getString(1) else "" }
			}
		}
	}

	@Test
	fun aReadWriteCheckRewritesTheFileItThenMovesAside() {
		// The cause, shown: the copy kept is not the file as found, and its WAL is gone.
		val (db, garbage, _) = corruptWithWal()
		val moved = assertIs<CacheDbCheck.Quarantined>(guardCacheDb(db, 9000, { quickCheck(it, readOnly = false) }))
		assertFalse(moved.movedTo!!.readBytes().contentEquals(garbage), "a read-write check rewrote it")
		assertFalse(File(dir, "downloads.db.corrupt-9000-wal").exists(), "and deleted its WAL")
	}

	@Test
	fun aReadOnlyCheckMovesTheFileAndItsWalAsideAsFound() {
		val (db, garbage, wal) = corruptWithWal()
		val moved = assertIs<CacheDbCheck.Quarantined>(guardCacheDb(db, 9000, { quickCheck(it, readOnly = true) }))
		assertContentEquals(garbage, moved.movedTo!!.readBytes(), "the copy kept is the file as found")
		assertContentEquals(wal, File(dir, "downloads.db.corrupt-9000-wal").readBytes(), "with its WAL")
		assertFalse(db.exists())
	}

	@Test
	fun theAppsChecksOpenTheFileReadOnly() {
		val guard = sourceFile("src/androidMain/kotlin/app/worbus/data/database/CacheDbGuard.kt").readText()
		for (name in listOf("bundledQuickCheck", "bundledUserVersion")) {
			val body = guard.substringAfter("internal fun $name(").substringBefore("\n\t}\n")
			assertTrue("SQLITE_OPEN_READONLY" in body && "SQLITE_OPEN_READWRITE" !in body, "$name opens read-only:\n$body")
		}
	}
}
