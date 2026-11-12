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
 * Q-165: writes.db 1 → 2 adds `DownloadOrigin` and touches nothing else. Run on a real SQLite file
 * holding a queued write, then compared with Room's exported 2.json (the schema Room validates
 * against at open).
 */
class WritesMigration12Test {
	private fun schemaSql(version: Int): Map<String, String> =
		Json.parseToJsonElement(sourceFile("schemas/app.worbus.data.database.WriteQueueDatabase/$version.json").readText())
			.jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray.associate {
				val table = it.jsonObject["tableName"]!!.jsonPrimitive.content
				table to it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
			}

	@Test
	fun theMigrationMakesExactlyVersionTwosTableAndKeepsTheQueue() {
		val dir = Files.createTempDirectory("writes12").toFile()
		try {
			DriverManager.getConnection("jdbc:sqlite:${File(dir, "writes.db").absolutePath}").use { db ->
				db.createStatement().use { st ->
					schemaSql(1).values.forEach { st.execute(it) }
					st.execute("INSERT INTO PendingWriteEntity (actionType, itemId, timeMs, refusals) VALUES ('STAR', 's-1', 5, 0)")
				}
				runBlocking { MIGRATION_WRITES_1_2.migrate(jdbcSQLiteConnection(db)) }
				val tables = db.createStatement().use { st ->
					st.executeQuery("SELECT name, sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").use { rs ->
						buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) }
					}
				}
				val expected = schemaSql(2)
				assertEquals(expected.keys, tables.keys)
				assertEquals(
					expected.getValue("DownloadOrigin").replace("CREATE TABLE IF NOT EXISTS", "CREATE TABLE"),
					tables.getValue("DownloadOrigin")
				)
				val queued = db.createStatement().use { st -> st.executeQuery("SELECT itemId FROM PendingWriteEntity").use { rs -> rs.next(); rs.getString(1) } }
				assertEquals("s-1", queued, "the queue is kept")
			}
		} finally {
			dir.deleteRecursively()
		}
	}
}
