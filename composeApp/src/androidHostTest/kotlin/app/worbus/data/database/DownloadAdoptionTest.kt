package app.worbus.data.database

import app.worbus.data.database.entities.DownloadSource
import app.worbus.data.database.entities.DownloadStatus
import app.worbus.sourceFile
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

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

	@Test
	fun theAdoptionRowFitsTheTableRoomCreates() {
		val createSql = Json.parseToJsonElement(
			sourceFile("schemas/app.worbus.data.database.DownloadDatabase/5.json").readText()
		).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray.single().jsonObject["createSql"]!!
			.jsonPrimitive.content.replace("\${TABLE_NAME}", "DownloadEntity")
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
