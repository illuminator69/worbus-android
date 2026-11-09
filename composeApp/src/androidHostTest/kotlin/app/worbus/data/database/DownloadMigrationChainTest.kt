package app.worbus.data.database

import app.worbus.sourceFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * B-365: `downloads.db` has no destructive fallback, so its migrations must reach the version the
 * app declares, step by step, from version 3 ([OLDEST]). A version bumped
 * without a migration fails here instead of stopping the app at open.
 */
class DownloadMigrationChainTest {
	private val declared: Int = DOWNLOAD_DB_VERSION

	@Test
	fun roomsAnnotationDeclaresTheVersionTheFirstOpenCheckIsGiven() {
		// The check moves aside a file above DOWNLOAD_DB_VERSION; a literal in the annotation could
		// drift from it, and the check would then move aside the app's own current file.
		val source = sourceFile("src/commonMain/kotlin/app/worbus/data/database/DownloadDatabase.kt").readText()
		assertTrue(Regex("""(?s)@Database\(.*?version\s*=\s*DOWNLOAD_DB_VERSION""").containsMatchIn(source), source)
	}

	@Test
	fun theMigrationsChainFromThreeToTheDeclaredVersion() {
		val steps = DOWNLOAD_MIGRATIONS.map { it.startVersion to it.endVersion }
		assertEquals((OLDEST until declared).map { it to it + 1 }, steps, "one migration per version, 3 to $declared")
	}

	@Test
	fun everyVersionOnTheChainHasItsSchemaExported() {
		val dir = sourceFile("schemas/app.worbus.data.database.DownloadDatabase")
		(OLDEST..declared).forEach { version ->
			assertTrue(File(dir, "$version.json").exists(), "schema $version.json is exported")
		}
		assertEquals(declared, dir.listFiles()!!.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.max(), "no schema above the declared version")
	}

	private companion object {
		/**
		 * Where the chain starts: 3, upstream Navic's `downloads.db` before the fork's download-center
		 * columns (4). `app.worbus.android` (268f1aaf) shipped with downloads already at 5
		 * (627de526), so nothing older than 5 is on a device under this app ID; 3 -> 4 and 4 -> 5 stay
		 * for the old `paige.navic` app's history, and both are checked against their schemas
		 * (`DownloadMigration34Test`, `CacheDatabase24MigrationTest`).
		 */
		const val OLDEST = 3
	}
}
