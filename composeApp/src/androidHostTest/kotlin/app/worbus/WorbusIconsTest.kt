package app.worbus

import app.worbus.domain.models.settings.AppIconVariant
import app.worbus.domain.models.settings.label
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Worbus P5, N3: the launcher icons and the splash (design §6.5); P6 N1 made the icons monochrome. The
 * picker's two entries are Ink (the default alias) and Paper (the `Tidepool` alias); each is an activity alias with its own adaptive icon. The
 * splash is the 760 ms animated vector, set in the existing values-v31 theme with no androidx
 * splashscreen library, so it may name no theme attribute (ruling R9). JVM-only, run from composeApp/.
 */
class WorbusIconsTest {
	private val res = "../androidApp/src/main/res"
	private val manifest by lazy { sourceFile("../androidApp/src/main/AndroidManifest.xml").readText() }
	private val strings by lazy { sourceFile("src/commonMain/composeResources/values/strings.xml").readText() }

	private fun englishString(key: String): String =
		Regex("""<string name="$key"[^>]*>([^<]*)</string>""").find(strings)?.groupValues?.get(1)
			?: error("values/strings.xml has no $key")

	/** The `<activity-alias …>` start tag whose name ends in [name]. */
	private fun aliasTag(name: String): String {
		val start = Regex("""<activity-alias\s+android:name="\$\{applicationId}\.$name"""").find(manifest)?.range?.first
			?: error("the manifest declares no alias $name")
		return manifest.substring(start, manifest.indexOf(">", start))
	}

	private fun attr(tag: String, name: String) =
		Regex("""android:$name="([^"]*)"""").find(tag)?.groupValues?.get(1) ?: error("no $name in $tag")

	/** background, foreground and monochrome of an adaptive icon file. */
	private fun layers(file: String): Triple<String, String, String> {
		val xml = sourceFile("$res/mipmap-anydpi-v26/$file").readText()
		fun layer(name: String) = Regex("""<$name\s+android:drawable="([^"]+)"""").find(xml)?.groupValues?.get(1) ?: error("$file: no $name")
		return Triple(layer("background"), layer("foreground"), layer("monochrome"))
	}

	private fun color(name: String): String =
		Regex("""<color name="$name">([^<]+)</color>""").find(sourceFile("$res/values/colors.xml").readText())?.groupValues?.get(1)
			?: error("values/colors.xml has no $name")

	/**
	 * Review Focus 1 (Worbus P6): the launcher icon vanishes after an update when an alias is renamed,
	 * removed or has its `android:enabled` flipped. The names, the order and the flags are exactly 0.5.0's.
	 */
	@Test
	fun iconAliasesUnchangedFromV050() {
		assertEquals(listOf("Default" to "MainActivityDefault", "Tidepool" to "MainActivityTidepool"),
			AppIconVariant.entries.map { it.name to it.activityName })
		assertEquals(2, Regex("""<activity-alias\b""").findAll(manifest).count(), "the manifest declares exactly two aliases")
		assertEquals("true", attr(aliasTag("MainActivityDefault"), "enabled"))
		assertEquals("false", attr(aliasTag("MainActivityTidepool"), "enabled"))
		assertTrue("Inverted" !in manifest, "the manifest still names the inverted icon")
	}

	/** P6 N1: the two picker entries read Ink and Paper; the string keys, like the enum, did not move. */
	@Test
	fun pickerLabelsAreInkAndPaper() {
		assertEquals(listOf("Ink", "Paper"), AppIconVariant.entries.map { englishString(it.label.key) })
	}

	/**
	 * P6 N1: each launcher foreground is one flat colour, Paper (`#F1F3EF`) for the Ink icon and Ink
	 * (`#18211F`) for the Paper icon. The gradient this replaced reads its colours from a nested
	 * `<gradient>` and has no `android:fillColor` attribute, so it cannot pass.
	 */
	@Test
	fun launcherForegroundsAreMono() {
		fun fills(file: String): List<String> {
			val xml = sourceFile("$res/drawable/$file").readText()
			assertTrue("gradient" !in xml, "$file still draws a gradient")
			assertTrue("c2" + "pa" !in xml.lowercase(), "$file carries provenance metadata")
			return Regex("""android:fillColor="([^"]*)"""").findAll(xml).map { it.groupValues[1].uppercase() }.toList()
		}
		assertEquals(listOf("#F1F3EF"), fills("ic_launcher_foreground.xml"))
		assertEquals(listOf("#18211F"), fills("ic_launcher_foreground_paper.xml"))
	}

	/** P6 N1: the Ink icon is the default alias, the Paper icon the second; each has its adaptive icon and legacy webp. */
	@Test
	fun launcherIconsAreInkAndPaper() {
		val ink = aliasTag("MainActivityDefault")
		assertEquals("@mipmap/ic_launcher", attr(ink, "icon"))
		assertEquals("@mipmap/ic_launcher_round", attr(ink, "roundIcon"))
		val paper = aliasTag("MainActivityTidepool")
		assertEquals("@mipmap/ic_launcher_paper", attr(paper, "icon"))
		assertEquals("@mipmap/ic_launcher_paper_round", attr(paper, "roundIcon"))

		for (file in listOf("ic_launcher.xml", "ic_launcher_round.xml")) {
			assertEquals(Triple("@color/ic_launcher_background", "@drawable/ic_launcher_foreground", "@drawable/ic_launcher_monochrome"), layers(file))
		}
		for (file in listOf("ic_launcher_paper.xml", "ic_launcher_paper_round.xml")) {
			assertEquals(Triple("@color/ic_launcher_background_paper", "@drawable/ic_launcher_foreground_paper", "@drawable/ic_launcher_monochrome"), layers(file))
		}
		assertEquals("#18211F", color("ic_launcher_background").uppercase())
		assertEquals("#F1F3EF", color("ic_launcher_background_paper").uppercase())
		assertTrue(Regex("""ic_launcher_background_tidepool""").find(sourceFile("$res/values/colors.xml").readText()) == null, "the old Tidepool background is still there")
		for (webp in listOf("ic_launcher", "ic_launcher_round", "ic_launcher_paper", "ic_launcher_paper_round")) {
			assertTrue(sourceFile("$res/mipmap-xxhdpi/$webp.webp").length() > 0)
		}
		for (gone in listOf("$res/drawable/ic_launcher_foreground_tidepool.xml", "$res/mipmap-anydpi-v26/ic_launcher_tidepool.xml",
			"$res/mipmap-anydpi-v26/ic_launcher_tidepool_round.xml", "$res/mipmap-xxhdpi/ic_launcher_tidepool.webp", "$res/mipmap-xxhdpi/ic_launcher_tidepool_round.webp")) {
			assertTrue(!java.io.File(gone).exists(), "$gone is still there")
		}
	}

	@Test
	fun splashIsTheWorbusAnimation() {
		val theme = sourceFile("$res/values-v31/themes.xml").readText()
		fun item(name: String) = Regex("""<item name="android:$name">([^<]+)</item>""").find(theme)?.groupValues?.get(1)
		assertEquals("@drawable/worbus_splash_icon", item("windowSplashScreenAnimatedIcon"))
		assertEquals("760", item("windowSplashScreenAnimationDuration"))
		assertEquals("@color/worbus_splash_background", item("windowSplashScreenBackground"))
		assertEquals("#FFFFFF", color("worbus_splash_background").uppercase())
		val night = sourceFile("$res/values-night/colors.xml").readText()
		assertTrue("""<color name="worbus_splash_background">#000000</color>""" in night)

		// R9: the theme is a platform one, so the vector may not resolve an AppCompat attribute.
		val vector = sourceFile("$res/drawable/worbus_splash_vector.xml").readText()
		assertTrue("?attr/" !in vector, "the splash vector names a theme attribute the launch theme does not define")
		val icon = sourceFile("$res/drawable/worbus_splash_icon.xml").readText()
		for (anim in listOf("worbus_wave_draw", "worbus_arc_upper_left_draw", "worbus_arc_lower_right_draw", "worbus_sheen_rotate")) {
			assertTrue("@animator/$anim" in icon, "the splash does not run $anim")
			sourceFile("$res/animator/$anim.xml")
		}
		sourceFile("$res/interpolator/m3_emphasized_decelerate.xml")
		assertTrue(!java.io.File("$res/drawable/splash.xml").exists(), "the old splash is still there")
		val versions = sourceFile("../gradle/libs.versions.toml").readText()
		assertTrue("splashscreen" !in versions, "no androidx splashscreen dependency")
	}

	/**
	 * P6 N2: the splash draws wave and arcs in one colour, the wallpaper's. In every `colors.xml` that
	 * defines them the arc resolves to what the wave resolves to: the wave's own value in `values`
	 * (`@color/worbus_splash_wave`, read by nothing below API 31), Material You's primary tone in the
	 * `-v31` files (600 by day, 200 at night).
	 */
	@Test
	fun splashArcsFollowTheWave() {
		fun value(dir: String, name: String): String =
			Regex("""<color name="$name">([^<]+)</color>""").find(sourceFile("$res/$dir/colors.xml").readText())?.groupValues?.get(1)
				?: error("$dir/colors.xml has no $name")
		assertEquals("#72A58F", value("values", "worbus_splash_wave").uppercase(), "the wave keeps its value")
		assertEquals("@color/worbus_splash_wave", value("values", "worbus_splash_arc"))
		assertEquals("@android:color/system_accent1_600", value("values-v31", "worbus_splash_wave"))
		assertEquals("@android:color/system_accent1_600", value("values-v31", "worbus_splash_arc"))
		assertEquals("@android:color/system_accent1_200", value("values-night-v31", "worbus_splash_wave"))
		assertEquals("@android:color/system_accent1_200", value("values-night-v31", "worbus_splash_arc"))
	}

	@Test
	fun brandIconIsWorbus() {
		for (gone in listOf("$res/drawable/ic_navic.xml", "src/commonMain/valkyrieResources/brand/ic_navic.xml",
			"$res/drawable/ic_launcher_inverted_foreground.xml", "$res/mipmap-xxhdpi/ic_launcher_inverted.webp")) {
			assertTrue(!java.io.File(gone).exists(), "$gone is still there")
		}
		sourceFile("$res/drawable/ic_worbus.xml")
		sourceFile("src/commonMain/valkyrieResources/brand/ic_worbus.xml")
		assertTrue("@drawable/ic_worbus" in manifest, "Android Auto's attribution icon is ic_worbus")
	}
}
