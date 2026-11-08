package app.worbus

import app.worbus.domain.models.settings.Theme
import app.worbus.ui.components.sheets.UPDATE_CHECK_URL
import app.worbus.ui.screens.settings.UPSTREAM_CREDIT_URL
import app.worbus.util.CLIENT_NAME
import app.worbus.util.defaultDeviceName
import app.worbus.util.deviceName
import app.worbus.util.userAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Worbus P5, N2: the IDs, names and wire names (design §6.1, §6.2, §6.7, §6.8, §6.11). Each value is
 * the plan's fixed value, verbatim. JVM-only (reads the build file, the XML and the sources), run
 * from composeApp/.
 */
class WorbusIdentityTest {
	private val appBuild by lazy { sourceFile("../androidApp/build.gradle.kts").readText() }
	private val strings by lazy { sourceFile("src/commonMain/composeResources/values/strings.xml").readText() }

	private fun gradleString(name: String): String =
		Regex("""\b$name\s*=\s*"([^"]*)"""").find(appBuild)?.groupValues?.get(1)
			?: error("androidApp/build.gradle.kts sets no $name")

	private fun englishString(key: String): String =
		Regex("""<string name="$key"[^>]*>([^<]*)</string>""").find(strings)?.groupValues?.get(1)
			?: error("values/strings.xml has no $key")

	private fun mainSources() = listOf("src/commonMain", "src/androidMain").map(::sourceFile)
		.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

	/** The `path`s a rules file excludes from the `sharedpref` domain, inside [section] if given. */
	private fun excludedPrefs(xml: String, section: String? = null): Set<String> {
		val scope = section?.let { xml.substringAfter("<$it>").substringBefore("</$it>") } ?: xml
		return Regex("""<exclude\s+domain="sharedpref"\s+path="([^"]+_preferences\.xml)"\s*/>""")
			.findAll(scope).map { it.groupValues[1] }.toSet()
	}

	/** Review Focus 2: the settings file is `<applicationId>_preferences.xml`, for each build. */
	@Test
	fun backupRulesNameTheAppId() {
		val appId = gradleString("applicationId")
		assertEquals("app.worbus.android", appId)
		val expected = setOf("${appId}_preferences.xml", "$appId${gradleString("applicationIdSuffix")}_preferences.xml")
		assertEquals(setOf("app.worbus.android_preferences.xml", "app.worbus.android.debug_preferences.xml"), expected)

		val backup = sourceFile("../androidApp/src/main/res/xml/backup_rules.xml").readText()
		assertEquals(expected, excludedPrefs(backup), "backup_rules.xml must exclude exactly the app's settings files")
		val extraction = sourceFile("../androidApp/src/main/res/xml/data_extraction_rules.xml").readText()
		assertEquals(expected, excludedPrefs(extraction, "cloud-backup"), "data_extraction_rules.xml's cloud backup must exclude the app's settings files")
	}

	@Test
	fun appIdsVersionAndApkNames() {
		assertEquals(".debug", gradleString("applicationIdSuffix"))
		assertEquals("0.7.0", gradleString("versionName"))
		assertTrue(Regex("""\bversionCode\s*=\s*63\b""").containsMatchIn(appBuild), "versionCode must be 63")
		assertTrue("0.7.0 (abc1234)" in sourceFile("../.github/ISSUE_TEMPLATE/bug_report.yml").readText(), "the bug report template's version placeholder is the current one")
		assertTrue("resValue(\"string\", \"app_name\", \"Worbus (Dev)\")" in appBuild, "the debug build is named Worbus (Dev)")
		assertTrue("\"Worbus.apk\"" in appBuild && "\"Worbus.fdroid.apk\"" in appBuild, "the APKs are Worbus.apk and Worbus.fdroid.apk")
		val launcher = sourceFile("../androidApp/src/main/res/values/strings.xml").readText()
		assertTrue(Regex("""<string name="app_name"[^>]*>Worbus</string>""").containsMatchIn(launcher), "the launcher label is Worbus")
		assertEquals("Worbus", englishString("app_name"))
	}

	@Test
	fun subsonicClientNameIsWorbus() {
		assertEquals("Worbus-Android", CLIENT_NAME)
		val session = sourceFile("src/commonMain/kotlin/app/worbus/domain/manager/SessionManager.kt").readText()
		val sites = Regex("""parameter\("c",\s*([^)]+)\)""").findAll(session).map { it.groupValues[1].trim() }.toList()
		assertEquals(8, sites.size, "SessionManager's hand-built requests: $sites")
		assertTrue(sites.all { it == "CLIENT_NAME" }, "every hand-built request sends c=CLIENT_NAME: $sites")
		assertTrue(Regex("""\bclient\s*=\s*CLIENT_NAME\b""").containsMatchIn(session), "the bundled client is built with CLIENT_NAME")
		val literal = mainSources().filter { Regex("""parameter\("c",\s*"""").containsMatchIn(it.readText()) }
		assertEquals(emptyList(), literal.map { it.path }, "a Subsonic client name typed as a literal")
	}

	@Test
	fun userAgentNamesWorbus() {
		assertEquals("Worbus-Android/0.7.0", userAgent())
		assertEquals("Worbus-Android/${gradleString("versionName")}", userAgent())
		val agents = mainSources().flatMap { f ->
			Regex("""agent\s*=\s*([^\n}]+)""").findAll(f.readText()).map { "${f.name}: ${it.groupValues[1].trim()}" }.toList()
		}
		assertEquals(10, agents.size, "the UserAgent sites: $agents")
		assertTrue(agents.all { it.endsWith(": userAgent()") }, "every client says userAgent(): $agents")
	}

	@Test
	fun defaultDeviceName_usesModel() {
		assertEquals("Pixel 8 Pro", defaultDeviceName(" Pixel 8 Pro "))
		assertEquals("Worbus", defaultDeviceName(""))
		assertEquals("Worbus", defaultDeviceName("   "))
		assertEquals("Worbus", defaultDeviceName(null))
	}

	@Test
	fun savedDeviceNameKept() {
		assertEquals("Kitchen", deviceName(saved = "Kitchen", model = "Pixel 8 Pro"))
		assertEquals("Pixel 8 Pro", deviceName(saved = "", model = "Pixel 8 Pro"))
		assertEquals("Worbus", deviceName(saved = " ", model = null))
		// Nothing writes a default into the preference: a blank saved name follows the model.
		val prefs = sourceFile("src/commonMain/kotlin/app/worbus/domain/manager/PreferenceManager.kt").readText()
		assertTrue(Regex("""var hubDeviceName by preference\(""\)""").containsMatchIn(prefs), "hubDeviceName defaults to blank")
		val writers = mainSources().filter { Regex("""hubDeviceName\s*=\s*.*ifBlank""").containsMatchIn(it.readText()) }
		assertEquals(emptyList(), writers.map { it.path }, "a default written into the saved name")
		for (file in listOf("domain/manager/HubAuth.kt", "domain/manager/HubManager.kt")) {
			val text = sourceFile("src/commonMain/kotlin/app/worbus/$file").readText()
			assertTrue("hubDeviceName.ifBlank" !in text, "$file: the name sent is deviceName(saved, model)")
			assertTrue("deviceName(preferenceManager.hubDeviceName" in text, "$file sends deviceName(saved, model)")
		}
	}

	/** Themes are stored by position (getEnum: ordinal), so a rename keeps every saved choice. */
	@Test
	fun themesKeepPositions() {
		assertEquals(listOf("Seeded", "Dynamic", "Crisp", "Rose", "Evergreen"), Theme.entries.map { it.name })
		// By position, as they are stored: 2, 3 and 4 were iOS, AppleMusic and Spotify.
		assertEquals(listOf("Crisp", "Rose", "Evergreen"), (2..4).map { englishString(Theme.entries[it].title.key) })
		assertEquals("Flat and bright, with soft corners", englishString("theme_subtitle_crisp"))
		assertEquals("Red and pink highlights", englishString("theme_subtitle_rose"))
		assertEquals("Green highlights", englishString("theme_subtitle_evergreen"))
		// The translations of the three names are dropped: they fall back to English.
		val translated = sourceFile("src/commonMain/composeResources").listFiles()!!
			.filter { it.isDirectory && it.name.startsWith("values-") }
		assertTrue(translated.size > 20)
		for (dir in translated) {
			val text = java.io.File(dir, "strings.xml").takeIf { it.isFile }?.readText() ?: continue
			for (key in listOf("theme_crisp", "theme_rose", "theme_evergreen", "theme_ios", "theme_apple_music", "theme_spotify")) {
				assertTrue("name=\"$key\"" !in text, "${dir.name} still translates $key")
			}
		}
	}

	@Test
	fun aboutCarriesCreditAndNavidromeLine() {
		assertEquals("Worbus %1\$s", englishString("info_app_version"))
		assertEquals("Worbus for Android is a fork of Navic by paige.", englishString("about_credit"))
		assertEquals("Licensed GPL-3.0, like the original.", englishString("about_licence"))
		assertEquals("Worbus is not affiliated with Navidrome.", englishString("about_navidrome"))
		assertEquals("https://github.com/ssalggnikool/Navic", UPSTREAM_CREDIT_URL)
		val about = sourceFile("src/commonMain/kotlin/app/worbus/ui/screens/settings/AboutScreen.kt").readText()
		// In this order: the version line, then the credit, the licence and the Navidrome line.
		val order = listOf("Res.string.info_app_version", "Res.string.about_credit", "Res.string.about_licence", "Res.string.about_navidrome")
			.map { about.indexOf(it) }
		assertTrue(order.all { it >= 0 } && order == order.sorted(), "About draws the four lines in order: $order")
		assertTrue("UPSTREAM_CREDIT_URL" in about.substringAfter("@Composable"), "the credit links upstream")
		// The icon screen's "designer" line goes: the art is not upstream's.
		val icons = sourceFile("src/commonMain/kotlin/app/worbus/domain/models/settings/AppIconVariant.kt").readText()
		assertTrue("designer" !in icons, "AppIconVariant still names a designer")
	}

	@Test
	fun updateCheckPointsAtWorbusAndroid() {
		assertEquals("https://api.github.com/repos/illuminator69/worbus-android/releases/latest", UPDATE_CHECK_URL)
	}
}
