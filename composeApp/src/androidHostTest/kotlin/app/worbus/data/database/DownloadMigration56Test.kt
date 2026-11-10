package app.worbus.data.database

import app.worbus.sourceFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * B-373: the offline write queue moves into downloads.db (5 -> 6, a new table). Run as
 * DownloadMigration34Test runs 3 -> 4: MIGRATION_DOWNLOAD_5_6 on a real SQLite file made from 5.json,
 * through the app's own migration code, and the table and indices it leaves are compared with what
 * Room's 6.json declares. The downloads table, and a row written at 5, are untouched.
 */
class DownloadMigration56Test {
	private val dir: File = Files.createTempDirectory("downloads56").toFile()

	@AfterTest
	fun cleanUp() {
		dir.deleteRecursively()
	}

	private fun entities(version: Int): List<JsonObject> = Json.parseToJsonElement(
		sourceFile("schemas/app.worbus.data.database.DownloadDatabase/$version.json").readText()
	).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject }

	/** Every CREATE the schema holds for its entities, each table before its indices. */
	private fun createSql(version: Int): List<String> = entities(version).flatMap { e ->
		val table = e["tableName"]!!.jsonPrimitive.content
		val indices = e["indices"]?.jsonArray?.map { it.jsonObject["createSql"]!!.jsonPrimitive.content } ?: emptyList()
		(listOf(e["createSql"]!!.jsonPrimitive.content) + indices).map { it.replace("\${TABLE_NAME}", table) }
	}

	private data class Column(val type: String, val notNull: Boolean, val primaryKeyPosition: Int)

	private fun Connection.columns(table: String): Map<String, Column> = createStatement().use { statement ->
		statement.executeQuery("PRAGMA table_info(`$table`)").use { rows ->
			buildMap {
				while (rows.next()) {
					put(rows.getString("name"), Column(rows.getString("type").uppercase(), rows.getInt("notnull") == 1, rows.getInt("pk")))
				}
			}
		}
	}

	/** Index name -> (unique, columns in order). */
	private fun Connection.indices(table: String): Map<String, Pair<Boolean, List<String>>> = buildMap {
		val names = createStatement().use { st ->
			st.executeQuery("PRAGMA index_list(`$table`)").use { rows ->
				buildList { while (rows.next()) add(rows.getString("name") to (rows.getInt("unique") == 1)) }
			}
		}
		for ((name, unique) in names) {
			if (name.startsWith("sqlite_autoindex")) continue
			val cols = createStatement().use { st ->
				st.executeQuery("PRAGMA index_info(`$name`)").use { rows ->
					buildList { while (rows.next()) add(rows.getInt("seqno") to rows.getString("name")) }
				}
			}.sortedBy { it.first }.map { it.second }
			put(name, unique to cols)
		}
	}

	private fun open(name: String): Connection = DriverManager.getConnection("jdbc:sqlite:${File(dir, name).absolutePath}")

	@Test
	fun fiveToSixLeavesTheQueueTableSixJsonDeclares() {
		val (expectedColumns, expectedIndices) = open("v6.db").use { v6 ->
			createSql(6).forEach { sql -> v6.createStatement().use { it.executeUpdate(sql) } }
			v6.columns("PendingWriteEntity") to v6.indices("PendingWriteEntity")
		}

		open("v5.db").use { v5 ->
			createSql(5).forEach { sql -> v5.createStatement().use { it.executeUpdate(sql) } }
			val downloadsBefore = v5.columns("DownloadEntity")
			v5.createStatement().use {
				it.executeUpdate(
					"INSERT INTO DownloadEntity (songId, status, progress, filePath, maxBitRate, fileSize, retryCount, sourcePolicy, createdAt, updatedAt) " +
						"VALUES ('s1', 'DOWNLOADED', 1.0, '/d/s1.flac', 0, 10, 0, 'manual', 1, 2)"
				)
			}

			runBlocking { MIGRATION_DOWNLOAD_5_6.migrate(jdbcSQLiteConnection(v5)) }

			assertEquals(expectedColumns, v5.columns("PendingWriteEntity"), "5 -> 6 leaves the columns 6.json declares")
			assertEquals(expectedIndices, v5.indices("PendingWriteEntity"), "and its indices, the legacyId one unique")
			assertEquals(downloadsBefore, v5.columns("DownloadEntity"), "the downloads table is untouched")
			v5.createStatement().use { st ->
				st.executeQuery("SELECT songId, filePath FROM DownloadEntity").use { rows ->
					rows.next()
					assertEquals("s1" to "/d/s1.flac", rows.getString(1) to rows.getString(2), "a row written at 5 survives")
				}
			}

			// A database that already has the table (created afresh at 6): nothing is created twice.
			runBlocking { MIGRATION_DOWNLOAD_5_6.migrate(jdbcSQLiteConnection(v5)) }
			assertEquals(expectedColumns, v5.columns("PendingWriteEntity"))
		}
	}
}
