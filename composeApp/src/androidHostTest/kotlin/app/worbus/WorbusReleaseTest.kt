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

	/**
	 * P6 N3: the release carries a `SHA256SUMS` beside the APK. It is written AFTER the certificate check (a
	 * wrong-key APK is never summed, let alone published) and attached by the same `gh release create`.
	 */
	@Test
	fun releaseAttachesChecksums() {
		val job = releaseJob()
		val check = job.indexOf("release-cert.sha256")
		assertTrue(check >= 0, "no step reads androidApp/release-cert.sha256")
		// The check is the whole step that reads release-cert.sha256 (its comparison and every `exit 1`),
		// so the sum must start in a LATER step, not merely after the step's name.
		val checkEnd = job.indexOf("- name:", check)
		assertTrue(checkEnd > check, "the certificate check is followed by another step")
		val sums = job.indexOf("""sha256sum "Worbus-${'$'}{GITHUB_REF_NAME}.apk" > SHA256SUMS""")
		val draft = job.indexOf("gh release create")
		assertTrue(sums >= 0, "no step writes SHA256SUMS from the release APK")
		assertTrue(sums > checkEnd, "SHA256SUMS is written after the certificate check step has ended (its comparison and exits ran first)")
		assertTrue(draft > sums, "SHA256SUMS is written before the release is drafted")
		val create = job.substring(draft)
		assertTrue(Regex("""gh release create\s+"\${'$'}GITHUB_REF_NAME"\s+"\./Worbus-\${'$'}\{GITHUB_REF_NAME}\.apk"\s+SHA256SUMS""").containsMatchIn(create),
			"the draft release attaches the APK and SHA256SUMS")
	}

	/** P6 N3: upstream's four phone screenshots are gone; the slot stays until the real shots land (C2). */
	@Test
	fun noUpstreamScreenshots() {
		val dir = java.io.File("../fastlane/metadata/android/en-US/images/phoneScreenshots")
		assertTrue(dir.isDirectory, "the phoneScreenshots folder is kept (a .gitkeep holds it until C2)")
		assertEquals(emptyList(), dir.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".jpg") }, "an upstream screenshot is still there")
	}

	/**
	 * P7 N3: F-Droid-style changelog for versionCode 63, three lines: the queue under shuffle, the sign-in
	 * step, and small fixes; no word the launch keeps off its pages (the plan's banned-words regex).
	 */
	@Test
	fun changelogForThisVersion() {
		val changelog = sourceFile("../fastlane/metadata/android/en-US/changelogs/63.txt").readText()
		val lines = changelog.lines().filter { it.isNotBlank() }
		assertEquals(3, lines.size, "63.txt is three lines: the queue under shuffle, the sign-in step, small fixes")
		assertTrue("shuffle" in lines[0].lowercase(), "63.txt's first line is the queue under shuffle: ${lines[0]}")
		assertTrue("Worbus Server" in lines[1], "63.txt's second line is the sign-in step: ${lines[1]}")
		assertTrue("small fixes" in lines[2].lowercase(), "63.txt's last line is the small fixes: ${lines[2]}")
		val banned = Regex("""soulseek|slskd|torrent|acquir|\bfills?\b|download(s|ed|ing)? (the |an? )?(album|music|song|track)""", RegexOption.IGNORE_CASE)
		assertEquals(null, banned.find(changelog)?.value, "63.txt says a word the launch keeps off its pages")
	}

	/** P7 N3: the pull request template sends a patch to the one tracker, in the plan's words. */
	@Test
	fun pullRequestTemplateIsTheFixedText() {
		val expected = "Thanks for the patch. It is read as a report: the fix is written and tested in the main tree, " +
			"and you are credited in the release notes. Bugs and ideas go to the one tracker: " +
			"https://github.com/illuminator69/worbus/issues/new/choose"
		assertEquals(expected, sourceFile("../.github/pull_request_template.md").readText().trim())
	}

	/** P6 N3 (ruling R6): the README shows the umbrella's shots by raw URL, and keeps no copy of upstream's. */
	@Test
	fun readmeShowsTheUmbrellasScreenshots() {
		val readme = sourceFile("../README.md").readText()
		for (n in 1..4) {
			assertTrue("https://raw.githubusercontent.com/illuminator69/worbus/main/docs/screenshots/0.6/android-$n.png" in readme, "README does not show android-$n.png")
		}
		assertTrue("docs/screenshots/navic" !in readme, "README still shows a navic-gaps screenshot")
		assertTrue("Obtainium" !in readme, "README still sends people to Obtainium")
		assertTrue(!java.io.File("../docs/screenshots").exists(), "navic-gaps' own screenshots are still there with nothing to reference them")
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
