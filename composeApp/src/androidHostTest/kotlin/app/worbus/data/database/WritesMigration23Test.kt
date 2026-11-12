package app.worbus.data.database

import app.worbus.sourceFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * The Q-165 follow-up (major 2): writes.db 2 → 3 adds whose a download is, and every record from 2 is
 * nobody's ('' and '') with its gone mark cleared (2 kept it on the wall clock). Run on a real SQLite file
 * holding a v2 record, then compared with Room's exported 3.json.
 */
class WritesMigration23Test {
	private fun tables(version: Int): Map<String, String> =
		Json.parseToJsonElement(sourceFile("schemas/app.worbus.data.database.WriteQueueDatabase/$version.json").readText())
			.jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray.associate {
				val table = it.jsonObject["tableName"]!!.jsonPrimitive.content
				table to it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
			}

	@Test
	fun aRecordFromTwoIsKeptUnplacedAndUnmarked() {
		val dir = Files.createTempDirectory("writes23").toFile()
		try {
			DriverManager.getConnection("jdbc:sqlite:${File(dir, "writes.db").absolutePath}").use { db ->
				db.createStatement().use { st ->
					tables(2).values.forEach { st.execute(it) }
					st.execute("INSERT INTO DownloadOrigin (songId, origin, recordedAt, goneSeenAt) VALUES ('s-1', 'o1:[]', 5, 1700000000000)")
				}
				runBlocking { MIGRATION_WRITES_2_3.migrate(jdbcSQLiteConnection(db)) }
				val row = db.createStatement().use { st ->
					st.executeQuery("SELECT songId, origin, recordedAt, goneSeenAt, server, account, gonePulls FROM DownloadOrigin").use { rs ->
						rs.next(); listOf(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getInt(7))
					}
				}
				assertEquals(listOf("s-1", "o1:[]", 5L, 0L, "", "", 0), row)
				// A table altered by ADD COLUMN carries the same columns, types, NOT NULLs and defaults as 3.json.
				val columns = db.createStatement().use { st ->
					st.executeQuery("PRAGMA table_info(DownloadOrigin)").use { rs ->
						buildList { while (rs.next()) add(listOf(rs.getString("name"), rs.getString("type"), rs.getInt("notnull"), rs.getString("dflt_value"), rs.getInt("pk"))) }
					}
				}
				val expected = DriverManager.getConnection("jdbc:sqlite::memory:").use { mem ->
					mem.createStatement().use { st ->
						st.execute(tables(3).getValue("DownloadOrigin"))
						st.executeQuery("PRAGMA table_info(DownloadOrigin)").use { rs ->
							buildList { while (rs.next()) add(listOf(rs.getString("name"), rs.getString("type"), rs.getInt("notnull"), rs.getString("dflt_value"), rs.getInt("pk"))) }
						}
					}
				}
				assertEquals(expected, columns)
			}
		} finally {
			dir.deleteRecursively()
		}
	}
}
