package app.worbus.data.database

import app.worbus.data.database.entities.DownloadSource
import app.worbus.data.database.entities.DownloadStatus
import app.worbus.sourceFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/*
 * B-306: a corrupt downloads.db is moved aside like cache.db (B-167), and the database Room then
 * creates adopts the files already on disk, so no download becomes an invisible orphan. The SQL is
 * run on a table made from the exported schema (5.json), through sqlite-jdbc.
 */
class DownloadAdoptionTest {
	private val dir: File = Files.createTempDirectory("downloadsdb").toFile()

	@AfterTest
	fun cleanUp() {
		dir.deleteRecursively()
	}

	private fun jdbcQuickCheck(file: File): String =
		DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
			connection.createStatement().use { statement ->
				statement.executeQuery("PRAGMA quick_check(1)").use { rows -> if (rows.next()) rows.getString(1) else "" }
			}
		}

	@Test
	fun aCorruptDownloadsDbIsMovedAside() {
		val db = File(dir, "downloads.db")
		db.writeBytes(Random(306).nextBytes(64 * 1024))

		val moved = assertIs<CacheDbCheck.Quarantined>(guardCacheDb(db, nowMs = 5000, quickCheck = ::jdbcQuickCheck))

		assertEquals("downloads.db.corrupt-5000", moved.movedTo?.name)
		assertFalse(db.exists(), "Room creates downloads.db afresh")
	}

	private fun jdbcUserVersion(file: File): Int =
		DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
			connection.createStatement().use { statement ->
				statement.executeQuery("PRAGMA user_version").use { rows -> if (rows.next()) rows.getInt(1) else 0 }
			}
		}

	private fun dbAtVersion(file: File, version: Int) {
		DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
			connection.createStatement().use { it.executeUpdate("CREATE TABLE DownloadEntity(songId TEXT PRIMARY KEY)") }
			connection.createStatement().use { it.executeUpdate("PRAGMA user_version = $version") }
		}
	}

	@Test
	fun aDownloadsDbANewerBuildWroteIsMovedAsideNotCrashedOn() {
		// Review finding 1: a release installed over a dev build whose DownloadDatabase is at 6 (the
		// versionCode is pinned, so Android takes it). Room finds no 6 -> 5 path and, with no
		// destructive fallback, throws at the first open, in DownloadManager's scope at startup: a
		// crash at every launch. Moved aside, Room creates it afresh and the files are adopted.
		val db = File(dir, "downloads.db")
		dbAtVersion(db, 6)
		val bytes = db.readBytes()

		val result = guardCacheDb(
			db, nowMs = 7000, quickCheck = ::jdbcQuickCheck, newestVersion = 5, userVersion = ::jdbcUserVersion
		)

		val moved = assertIs<CacheDbCheck.Quarantined>(result, "a newer file is moved aside")
		assertEquals("downloads.db.newer-7000", moved.movedTo?.name)
		assertFalse(db.exists(), "Room creates downloads.db afresh")
		assertContentEquals(bytes, File(dir, "downloads.db.newer-7000").readBytes(), "kept whole, for the newer build")
	}

	@Test
	fun aDownloadsDbAtOrBelowTheDeclaredVersionIsLeftForRoom() {
		listOf(5, 4).forEach { version ->
			val db = File(dir, "downloads-$version.db")
			dbAtVersion(db, version)
			assertEquals(
				CacheDbCheck.Healthy,
				guardCacheDb(db, nowMs = 7000, quickCheck = ::jdbcQuickCheck, newestVersion = 5, userVersion = ::jdbcUserVersion),
				"version $version: Room opens (and migrates) it"
			)
			assertTrue(db.exists())
		}
	}

	@Test
	fun onlyFinishedDownloadsAreFoundOnDisk() {
		val downloads = File(dir, "downloads").apply { mkdirs() }
		File(downloads, "song-1.flac").writeBytes(ByteArray(10))
		File(downloads, "song-2.mp3").writeBytes(ByteArray(20))
		File(downloads, "song-3.opus.part").writeBytes(ByteArray(5)) // a transfer that never finished
		File(downloads, "song-4.flac").writeBytes(ByteArray(0)) // nothing in it
		File(downloads, "nested").mkdirs()

		assertEquals(
			listOf("song-1" to 10L, "song-2" to 20L),
			downloadsOnDisk(downloads).map { it.songId to it.size }
		)
		assertEquals(emptyList(), downloadsOnDisk(File(dir, "absent")))
	}

	private fun downloadTableSql(): String = Json.parseToJsonElement(
		sourceFile("schemas/app.worbus.data.database.DownloadDatabase/5.json").readText()
	).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray.single().jsonObject["createSql"]!!
		.jsonPrimitive.content.replace("\${TABLE_NAME}", "DownloadEntity")

	@Test
	fun anAdoptionThatFailsDoesNotFailTheDatabasesCreation() {
		// Review finding 2: onCreate runs inside Room's create transaction. A throw from an insert
		// (disk full, an I/O error) rolled the creation back, and every launch repeated it and died
		// in DownloadManager. A file that cannot be adopted is logged; the rest are adopted.
		val downloads = File(dir, "downloads").apply { mkdirs() }
		File(downloads, "song-1.flac").writeBytes(ByteArray(10))
		File(downloads, "song-2.mp3").writeBytes(ByteArray(20))
		val warnings = mutableListOf<String>()

		DriverManager.getConnection("jdbc:sqlite:${File(dir, "d.db").absolutePath}").use { jdbc ->
			jdbc.createStatement().use { it.executeUpdate(downloadTableSql()) }
			val connection = jdbcSQLiteConnection(jdbc) { sql, call -> call == 0 && sql == ADOPT_DOWNLOAD_SQL }
			val adopt = AdoptDownloadsOnCreate({ downloads }, now = { 1_800_000_000_000L }, warn = { message, _ -> warnings += message })

			runBlocking { adopt.onCreate(connection) }

			jdbc.createStatement().use { statement ->
				statement.executeQuery("SELECT songId FROM DownloadEntity").use { rows ->
					assertEquals(listOf("song-2"), buildList { while (rows.next()) add(rows.getString(1)) }, "the file that could be adopted is")
				}
			}
		}
		assertTrue(warnings.any { "song-1" in it }, "the one that could not is logged: $warnings")
	}

	@Test
	fun theAdoptionRowFitsTheTableRoomCreates() {
		val createSql = downloadTableSql()
		val file = DownloadOnDisk("song-1", "/data/files/downloads/song-1.flac", 1234L, 1_700_000_000_000L)

		DriverManager.getConnection("jdbc:sqlite:${File(dir, "d.db").absolutePath}").use { connection ->
			connection.createStatement().use { it.executeUpdate(createSql) }
			repeat(2) { // INSERT OR IGNORE: a row already there is left alone
				connection.prepareStatement(ADOPT_DOWNLOAD_SQL).use { statement ->
					adoptDownloadArgs(file, nowMs = 1_800_000_000_000L).forEachIndexed { i, value -> statement.setObject(i + 1, value) }
					statement.executeUpdate()
				}
			}
			connection.createStatement().use { statement ->
				statement.executeQuery("SELECT songId, status, progress, filePath, maxBitRate, format, fileSize, retryCount, sourcePolicy, createdAt, updatedAt FROM DownloadEntity").use { rows ->
					val got = buildList { while (rows.next()) add((1..11).map { rows.getObject(it) }) }
					assertEquals(1, got.size)
					val row = got.single()
					assertEquals("song-1", row[0])
					assertEquals(DownloadStatus.DOWNLOADED.name, row[1], "the status as Room stores the enum")
					assertEquals(1.0, (row[2] as Number).toDouble())
					assertEquals(file.path, row[3])
					assertEquals(0, (row[4] as Number).toInt())
					assertEquals(null, row[5])
					assertEquals(1234L, (row[6] as Number).toLong())
					assertEquals(DownloadSource.MANUAL, row[8])
					assertEquals(1_700_000_000_000L, (row[9] as Number).toLong(), "created when the file was written")
				}
			}
		}
	}
}
