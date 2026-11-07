package app.worbus

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Worbus P5, N1: the package moved from upstream's name to `app.worbus` (tools/rename-package.sh).
 *
 * Three things a sed can get wrong and the compiler cannot see:
 * - a source-reading test that names a file under the old path, reads nothing and passes
 *   (Review Focus 4): no test source may name the old package, as a path or dotted;
 * - Room's schema history sits in directories named after each database class, so a move that
 *   left them behind loses every migration's "from" schema (Review Focus 1);
 * - a file, an import, a build setting or a keep rule still naming the old package.
 *
 * The old name is spelled in pieces here so that the rename script, which rewrites the dotted
 * and the slashed forms, never rewrites this file's own needle. JVM-only, run from composeApp/.
 */
class RenameGuardTest {
	private val old = listOf("paige", "navic")
	private val oldDotted = old.joinToString(".")
	private val oldSlashed = old.joinToString("/")

	private fun kotlinUnder(vararg roots: String): List<File> = roots.map(::sourceFile).flatMap { root ->
		root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
	}

	/** Every test source set of both modules: composeApp's `*Test` folders and androidApp's, if any. */
	private fun testSources(): List<File> {
		val composeTests = sourceFile("src").listFiles()!!.filter { it.isDirectory && it.name.endsWith("Test") }
		val appTests = File("../androidApp/src").listFiles().orEmpty().filter { it.isDirectory && it.name.contains("test", ignoreCase = true) }
		val roots = composeTests + appTests
		assertTrue(composeTests.isNotEmpty(), "no test source sets found under ${File("src").absolutePath}")
		return roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
	}

	private fun hits(files: List<File>, needle: (String) -> Boolean): List<String> = files.flatMap { f ->
		f.readLines().mapIndexedNotNull { i, line ->
			if (needle(line)) "${f.invariantSeparatorsPath}:${i + 1}: ${line.trim()}" else null
		}
	}

	@Test
	fun noTestNamesOldPackagePath() {
		val tests = testSources()
		assertTrue(tests.size > 50, "only ${tests.size} test sources found: the scan is looking in the wrong place")
		val offenders = hits(tests) { oldSlashed in it || oldDotted in it }
		assertEquals(emptyList(), offenders, "a test still names the old package; a path under it reads nothing")
	}

	@Test
	fun noSourceDeclaresOldPackage() {
		val sources = kotlinUnder("src", "../androidApp/src")
		assertTrue(sources.size > 500, "only ${sources.size} Kotlin files found: the scan is looking in the wrong place")
		val underOldPath = sources.map { it.invariantSeparatorsPath }.filter { "/$oldSlashed/" in it }
		assertEquals(emptyList(), underOldPath, "Kotlin files still sit under the old package's directory")
		val declares = Regex("""^\s*(package|import)\s+${Regex.escape(oldDotted)}\b""")
		assertEquals(emptyList(), hits(sources) { declares.containsMatchIn(it) }, "a file still declares or imports the old package")
		val anyUse = hits(sources) { oldDotted in it }
		assertEquals(emptyList(), anyUse, "a fully qualified name, KDoc link or key still names the old package")

		// The hand-written names: Gradle's namespace, the Valkyrie and BuildInfo packages, the keep
		// rule, the manifest's fully qualified services. The application ID is an ID, not the
		// package, and is checked by the backup-rules test (N2).
		val build = listOf("build.gradle.kts", "../androidApp/build.gradle.kts", "../androidApp/proguard-rules.pro", "../androidApp/src/main/AndroidManifest.xml")
			.map(::sourceFile)
		val inBuild = hits(build) { (oldDotted in it || oldSlashed in it) && "applicationId" !in it }
		assertEquals(emptyList(), inBuild, "a build file, keep rule or manifest entry still names the old package")
	}

	/**
	 * Each `@Database` class's schema directory is named after its fully qualified name and holds the
	 * whole recorded history up to the class's `version`. The history has gaps that were never
	 * committed (cache 9 to 11 and 14, downloads 2), so the guard is "nothing recorded was lost",
	 * not "1 to N".
	 */
	@Test
	fun schemaDirsFollowDatabaseClass() {
		val recorded = mapOf(
			"CacheDatabase" to ((1..8) + (12..13) + (15..24)).toSet(),
			"DownloadDatabase" to setOf(1, 3, 4, 5),
		)
		val sources = kotlinUnder("src/commonMain")
		for ((name, history) in recorded) {
			val source = sources.single { it.name == "$name.kt" }
			val text = source.readText()
			val pkg = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
				?: error("$source declares no package")
			val annotation = text.substringAfter("@Database(").substringBefore("\nabstract class")
			val version = Regex("""\bversion\s*=\s*(\d+)""").find(annotation)?.groupValues?.get(1)?.toInt()
				?: error("$name's @Database has no version")
			val dir = sourceFile("schemas/$pkg.$name")
			assertTrue(dir.isDirectory, "${dir.path} is not a directory")
			val present = dir.listFiles()!!.mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }.toSet()
			assertTrue(version in present, "$name is at version $version but ${dir.path} has no $version.json")
			assertEquals(emptySet(), history - present, "$name's schema history lost files in ${dir.path}")
			assertTrue(present.all { it <= version }, "${dir.path} holds a schema above $name's version $version: $present")
		}
	}
}
