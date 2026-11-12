package app.worbus.data.database

import app.worbus.sourceFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * Review major 3 (the coordinator's ruling): a rollback to Worbus 0.7.0 must cost the write queue at
 * most, never the download records. 0.7.0 builds `downloads.db` at version 5 with a destructive
 * fallback, so any version above 5 written here makes 0.7.0 drop and recreate every table: the
 * Downloads list empties and every file on disk is orphaned. So `downloads.db` stays exactly what
 * 0.7.0 shipped (version 5, the same schema identity), this build opens a 0.7.0 file without
 * touching it, and the write queue lives in a database file of its own.
 */
class DownloadsDbRollbackTest {
	private val dir: File = Files.createTempDirectory("downloads-rollback").toFile()

	@AfterTest
	fun cleanUp() {
		dir.deleteRecursively()
	}

	private fun schema(version: Int) = Json.parseToJsonElement(
		sourceFile("schemas/app.worbus.data.database.DownloadDatabase/$version.json").readText()
	).jsonObject["database"]!!.jsonObject

	@Test
	fun downloadsDbIsTheVersionAndSchemaWorbus070Shipped() {
		assertEquals(5, DOWNLOAD_DB_VERSION, "downloads.db's version (0.7.0 shipped 5; anything above makes 0.7.0 wipe it)")
		// `git show v0.7.0:composeApp/schemas/app.worbus.data.database.DownloadDatabase/5.json`.
		assertEquals("fe925357e3d03bb9cadd68418caea032", schema(5)["identityHash"]!!.jsonPrimitive.content, "the schema 0.7.0 shipped")
		val exported = sourceFile("schemas/app.worbus.data.database.DownloadDatabase").listFiles()!!
			.mapNotNull { it.nameWithoutExtension.toIntOrNull() }
		assertEquals(5, exported.max(), "no downloads.db schema above 0.7.0's")
		assertTrue(DOWNLOAD_MIGRATIONS.none { it.endVersion > 5 }, "no migration takes downloads.db past 5")
	}

	@Test
	fun aWorbus070DownloadsDbIsOpenedAsItIs() {
		val db = File(dir, "downloads.db")
		val tables = schema(5)["entities"]!!.jsonArray.map { it.jsonObject }
		DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { c ->
			tables.forEach { e ->
				val name = e["tableName"]!!.jsonPrimitive.content
				c.createStatement().use { it.executeUpdate(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name)) }
			}
			c.createStatement().use {
				it.executeUpdate(
					"INSERT INTO DownloadEntity (songId, status, progress, filePath, maxBitRate, fileSize, retryCount, sourcePolicy, createdAt, updatedAt) " +
						"VALUES ('s1', 'DOWNLOADED', 1.0, '/d/s1.flac', 0, 10, 0, 'manual', 1, 2)"
				)
			}
			c.createStatement().use { it.executeUpdate("PRAGMA user_version = 5") }
		}
		val before = db.readBytes()

		val result = guardCacheDb(
			db,
			nowMs = 7000,
			quickCheck = { f -> DriverManager.getConnection("jdbc:sqlite:${f.absolutePath}").use { c -> c.createStatement().use { s -> s.executeQuery("PRAGMA quick_check(1)").use { r -> r.next(); r.getString(1) } } } },
			newestVersion = DOWNLOAD_DB_VERSION,
			userVersion = { f -> DriverManager.getConnection("jdbc:sqlite:${f.absolutePath}").use { c -> c.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { r -> r.next(); r.getInt(1) } } } }
		)

		assertEquals(CacheDbCheck.Healthy, result, "the first-open check leaves a 0.7.0 file for Room")
		assertTrue(DOWNLOAD_MIGRATIONS.none { it.startVersion == 5 }, "and Room has nothing to migrate at 5")
		assertContentEquals(before, db.readBytes(), "the file is untouched")
	}

	@Test
	fun theWriteQueueHasADatabaseFileOfItsOwn() {
		val downloads = sourceFile("src/commonMain/kotlin/app/worbus/data/database/DownloadDatabase.kt").readText()
		assertFalse("PendingWriteEntity" in downloads, "the queue's table is not in downloads.db")
		val platform = sourceFile("src/androidMain/kotlin/app/worbus/di/PlatformModule.android.kt").readText()
		assertTrue("single<WriteQueueDatabase>" in platform && "\"writes.db\"" in platform, "the queue is built as writes.db")
	}
}
