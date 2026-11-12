package app.worbus.data.database

import app.worbus.sourceFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * B-373 / review major 3: `writes.db` holds the only record of writes the server has not had, so it
 * follows the migration policy written on [WRITES_DB_VERSION]: no destructive fallback, the
 * first-open check against the declared version (a newer build's file is moved aside, not
 * dropped), and a migration plus an exported schema for every version. The bundled driver's
 * natives are Android's, so Room cannot open a file here; these read the one builder the app has.
 */
class WritesDbPolicyTest {
	private val builder: String = sourceFile("src/androidMain/kotlin/app/worbus/di/PlatformModule.android.kt")
		.readText()
		.substringAfter("single<WriteQueueDatabase> {")
		.substringBefore("\n\t}\n")

	@Test
	fun writesDbIsCheckedAtFirstOpenAgainstItsDeclaredVersion() {
		assertTrue("databaseBuilder<WriteQueueDatabase>" in builder && "\"writes.db\"" in builder, "found the builder:\n$builder")
		assertTrue("CheckedFirstOpenDriver(" in builder, "writes.db opens through the first-open check:\n$builder")
		assertTrue("newestVersion = WRITES_DB_VERSION" in builder, "the check is given the version Room declares:\n$builder")
	}

	@Test
	fun writesDbIsNeverDroppedAndAlwaysMigrated() {
		assertFalse("fallbackToDestructiveMigration" in builder, "no destructive fallback on writes.db:\n$builder")
		assertTrue("addMigrations(*WRITES_MIGRATIONS)" in builder, "its migrations are registered:\n$builder")
		val source = sourceFile("src/commonMain/kotlin/app/worbus/data/database/WriteQueueDatabase.kt").readText()
		assertTrue(Regex("""(?s)@Database\(.*?version\s*=\s*WRITES_DB_VERSION""").containsMatchIn(source), source)
		assertEquals((1 until WRITES_DB_VERSION).map { it to it + 1 }, WRITES_MIGRATIONS.map { it.startVersion to it.endVersion }, "one migration per version")
	}

	@Test
	fun everyVersionHasItsSchemaExported() {
		val dir = sourceFile("schemas/app.worbus.data.database.WriteQueueDatabase")
		(1..WRITES_DB_VERSION).forEach { assertTrue(File(dir, "$it.json").exists(), "schema $it.json is exported") }
		assertEquals(WRITES_DB_VERSION, dir.listFiles()!!.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.max())
	}
}
