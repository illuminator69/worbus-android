package app.worbus.data.database

import app.worbus.sourceFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * The review of B-365 (finding 6): the chain test only checks that each step and schema exist, and
 * `CacheDatabase24MigrationTest` runs 4 -> 5. Here MIGRATION_DOWNLOAD_3_4 is RUN, on a real SQLite
 * file made from 3.json, through the app's own migration code (a sqlite-jdbc SQLiteConnection), and
 * the table it leaves is compared with the one 4.json declares: the columns Room's validation reads
 * (name, type, not null, primary key). Room checks a default only where the entity declares one,
 * and 4.json declares none, so the migration's DEFAULTs (needed to fill existing rows) are not
 * compared. A row written at 3 survives with them.
 */
class DownloadMigration34Test {
	private val dir: File = Files.createTempDirectory("downloads34").toFile()

	@AfterTest
	fun cleanUp() {
		dir.deleteRecursively()
	}

	private fun createSql(version: Int): String = Json.parseToJsonElement(
		sourceFile("schemas/app.worbus.data.database.DownloadDatabase/$version.json").readText()
	).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray.single().jsonObject["createSql"]!!
		.jsonPrimitive.content.replace("\${TABLE_NAME}", "DownloadEntity")

	private data class Column(val type: String, val notNull: Boolean, val primaryKeyPosition: Int)

	private fun Connection.columns(): Map<String, Column> = createStatement().use { statement ->
		statement.executeQuery("PRAGMA table_info(`DownloadEntity`)").use { rows ->
			buildMap {
				while (rows.next()) {
					put(rows.getString("name"), Column(rows.getString("type").uppercase(), rows.getInt("notnull") == 1, rows.getInt("pk")))
				}
			}
		}
	}

	private fun open(name: String): Connection = DriverManager.getConnection("jdbc:sqlite:${File(dir, name).absolutePath}")

	@Test
	fun threeToFourLeavesTheTableFourJsonDeclares() {
		val expected = open("v4.db").use { v4 ->
			v4.createStatement().use { it.executeUpdate(createSql(4)) }
			v4.columns()
		}

		open("v3.db").use { v3 ->
			v3.createStatement().use { it.executeUpdate(createSql(3)) }
			v3.createStatement().use {
				it.executeUpdate("INSERT INTO DownloadEntity (songId, status, progress, filePath) VALUES ('s1', 'DOWNLOADED', 1.0, '/d/s1.flac')")
			}

			runBlocking { MIGRATION_DOWNLOAD_3_4.migrate(jdbcSQLiteConnection(v3)) }

			assertEquals(expected, v3.columns(), "3 -> 4 leaves the columns 4.json declares")
			v3.createStatement().use { statement ->
				statement.executeQuery("SELECT songId, status, filePath, maxBitRate, fileSize, retryCount, sourcePolicy FROM DownloadEntity").use { rows ->
					rows.next()
					assertEquals(
						listOf<Any?>("s1", "DOWNLOADED", "/d/s1.flac", 0, 0, 0, "manual"),
						listOf(rows.getString(1), rows.getString(2), rows.getString(3), rows.getInt(4), rows.getInt(5), rows.getInt(6), rows.getString(7)),
						"a row written at 3 survives with the defaults"
					)
				}
			}

			// Run again on a table that already has the columns (a fresh install of the build that
			// shipped the expanded entity at the old number): nothing is added twice.
			runBlocking { MIGRATION_DOWNLOAD_3_4.migrate(jdbcSQLiteConnection(v3)) }
			assertEquals(expected, v3.columns())
		}
	}
}
