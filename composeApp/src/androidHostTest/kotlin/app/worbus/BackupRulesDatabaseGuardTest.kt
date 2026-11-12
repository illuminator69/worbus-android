package app.worbus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Review (fix round, 2026-11-12) major 1: writes.db went into cloud backup and device transfer
 * when the write queue moved into a file of its own. A restore starts logged out (the secrets
 * never travel), a login does not clear the queue, and the first drain then sent a snapshot from
 * the day of the backup as whoever signed in. So no Room database may travel: every file the app
 * opens with `getDatabasePath(...)` is named, with its -wal and -shm, in every section of both
 * rule files. The list of databases is read from the main sources, so a fourth database fails
 * here before it ships.
 */
class BackupRulesDatabaseGuardTest {
	private fun mainSources() = listOf("src/commonMain", "src/androidMain").map(::sourceFile)
		.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

	private val databases: Set<String> by lazy {
		mainSources().flatMap { f ->
			Regex("""getDatabasePath\(\s*"([^"]+)"\s*\)""").findAll(f.readText()).map { it.groupValues[1] }.toList()
		}.toSet()
	}

	/** The `path`s excluded from the `database` domain inside [section] (the whole file if null). */
	private fun excludedDatabases(xml: String, section: String? = null): Set<String> {
		val scope = section?.let {
			assertTrue("<$it>" in xml, "the rules file has no <$it> section")
			xml.substringAfter("<$it>").substringBefore("</$it>")
		} ?: xml
		return Regex("""<exclude\s+domain="database"\s+path="([^"]+)"\s*/>""")
			.findAll(scope).map { it.groupValues[1] }.toSet()
	}

	// -journal too (review of fix round 2, nit 4): Room in TRUNCATE journal mode (a low-RAM device) has one for the length of a transaction.
	private fun expectedFiles() = databases.flatMap { listOf(it, "$it-wal", "$it-shm", "$it-journal") }.toSet()

	@Test
	fun theDatabaseListIsReadFromTheCode() {
		// The derivation must see every builder, or the guard below checks a short list.
		val builders = mainSources().sumOf { Regex("""\.databaseBuilder<""").findAll(it.readText()).count() }
		assertEquals(builders, databases.size, "every Room databaseBuilder opens a getDatabasePath(\"…\") file: $databases")
		assertTrue("writes.db" in databases && "cache.db" in databases && "downloads.db" in databases, "$databases")
	}

	@Test
	fun everyDatabaseIsExcludedFromFullBackup() {
		val backup = sourceFile("../androidApp/src/main/res/xml/backup_rules.xml").readText()
		val missing = expectedFiles() - excludedDatabases(backup, "full-backup-content")
		assertEquals(emptySet(), missing, "backup_rules.xml must exclude every database file")
	}

	@Test
	fun everyDatabaseIsExcludedFromCloudBackupAndDeviceTransfer() {
		val extraction = sourceFile("../androidApp/src/main/res/xml/data_extraction_rules.xml").readText()
		for (section in listOf("cloud-backup", "device-transfer")) {
			val missing = expectedFiles() - excludedDatabases(extraction, section)
			assertEquals(emptySet(), missing, "data_extraction_rules.xml's $section must exclude every database file")
		}
	}
}
