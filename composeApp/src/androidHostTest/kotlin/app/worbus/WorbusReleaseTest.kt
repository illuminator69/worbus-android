package app.worbus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Worbus P5, N4: a wrong-key APK is never published as a release (Review Focus 5, design §6.6). The
 * release certificate's SHA-256 is recorded in androidApp/release-cert.sha256 (`unset` until the
 * owner makes the key on the workstation); the release workflow refuses to draft a release while it
 * says `unset` or when the built APK's certificate differs; and a local release build without the
 * release key says, in one Gradle warning, that it is debug-signed. JVM-only, run from composeApp/.
 */
class WorbusReleaseTest {
	private val workflow by lazy { sourceFile("../.github/workflows/build.yml").readText() }

	/** The `release:` job, from its key to the next top-level job or the end of the file. */
	private fun releaseJob(): String {
		val start = Regex("""(?m)^  release:\s*$""").find(workflow)?.range?.first ?: error("build.yml has no release job")
		val next = Regex("""(?m)^  [A-Za-z0-9_-]+:\s*$""").findAll(workflow).map { it.range.first }.firstOrNull { it > start }
		return workflow.substring(start, next ?: workflow.length)
	}

	@Test
	fun releaseCertFileFormat() {
		val text = sourceFile("../androidApp/release-cert.sha256").readText()
		val lines = text.lines().filter { it.isNotEmpty() }
		assertEquals(1, lines.size, "release-cert.sha256 is one line: $text")
		assertTrue(text.endsWith("\n") && text.count { it == '\n' } == 1, "release-cert.sha256 is one line ending in a newline")
		val value = lines.single()
		assertTrue(value == "unset" || Regex("^[0-9a-f]{64}$").matches(value),
			"release-cert.sha256 holds `unset` or 64 lower-case hex characters, not `$value`")
	}

	@Test
	fun buildWorkflowGuardsWorbusAndroid() {
		val job = releaseJob()
		val guard = Regex("""(?m)^    if:\s*(.+)$""").find(job)?.groupValues?.get(1) ?: error("the release job has no if:")
		assertTrue("github.repository == 'illuminator69/worbus-android'" in guard, "the release job runs only on illuminator69/worbus-android: $guard")

		val check = job.indexOf("release-cert.sha256")
		val draft = job.indexOf("gh release create")
		assertTrue(check >= 0, "no step reads androidApp/release-cert.sha256")
		assertTrue(draft > check, "the certificate check must come before the draft release")
		val step = job.substring(job.lastIndexOf("- name:", check), job.indexOf("- name:", check).takeIf { it > 0 } ?: job.length)
		assertTrue(Regex("""apksigner"?\s+verify\s+--print-certs""").containsMatchIn(step), "the check reads the built APK's certificate with apksigner")
		assertTrue(Regex("""\[\s*"\${'$'}want"\s*=\s*"unset"\s*]""").containsMatchIn(step), "the check fails while the file says unset")
		assertTrue(Regex("""\[\s*"\${'$'}got"\s*!=\s*"\${'$'}want"\s*]""").containsMatchIn(step), "the check fails on a mismatch")
		assertTrue(Regex("""exit 1""").findAll(step).count() >= 3, "each refusal exits 1")

		assertTrue("\"./Worbus-\${GITHUB_REF_NAME}.apk\"" in job, "the release APK is Worbus-<tag>.apk")
		assertTrue("name: worbus-debug-android" in workflow, "the debug artifact is worbus-debug-android")
		assertTrue("Navic" !in workflow && "navic" !in workflow, "build.yml still names the old app")
	}

	@Test
	fun releaseWithoutKeyWarns() {
		val build = sourceFile("../androidApp/build.gradle.kts").readText()
		val line = "Worbus release APK signed with the DEBUG key (SIGNING_* unset): not for distribution"
		assertEquals(1, Regex(Regex.escape(line)).findAll(build).count(), "the warning is written once, verbatim")
		// Configuration cache is on (gradle.properties): a warning printed while the build script
		// configures vanishes on a cache hit. So it must be written by a task action (doFirst), from a
		// Boolean captured at configuration time, and by the lifecycle task that always runs.
		assertEquals(1, Regex(Regex.escape("logger.warn(")).findAll(build).count(), "the only logger.warn is the debug-key line")
		val hook = Regex(
			"""tasks\.matching \{[^}]*"assembleRelease"[^}]*}\.configureEach \{\s*val (\w+) = isTaskRelease && !hasReleaseSigning\s*doFirst \{\s*if \(\1\) logger\.warn\("([^"]+)"\)\s*}\s*}"""
		).find(build)
		assertEquals(line, hook?.groupValues?.get(2), "the warning is printed by assembleRelease's doFirst, only for a release task without the key")
	}
}
