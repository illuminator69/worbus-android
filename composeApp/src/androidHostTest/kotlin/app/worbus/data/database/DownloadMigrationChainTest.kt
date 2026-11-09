package app.worbus.data.database

import app.worbus.sourceFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * B-365: `downloads.db` has no destructive fallback, so its migrations must reach the version the
 * app declares, step by step, from the oldest version this app ever shipped (3). A version bumped
 * without a migration fails here instead of stopping the app at open.
 */
class DownloadMigrationChainTest {
	private val declared: Int = Regex("""version\s*=\s*(\d+)""")
		.find(sourceFile("src/commonMain/kotlin/app/worbus/data/database/DownloadDatabase.kt").readText())!!
		.groupValues[1].toInt()

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
		/** The version `downloads.db` had when this app ID shipped; nothing older can be on a device. */
		const val OLDEST = 3
	}
}
