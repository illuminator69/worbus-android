package app.worbus

import app.worbus.domain.models.settings.AppIconVariant
import app.worbus.domain.models.settings.label
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Worbus P5, N3: the launcher icons and the splash (design §6.5). The picker's two entries are Kelp
 * and clay (the default) and Tidepool; each is an activity alias with its own adaptive icon. The
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

	@Test
	fun iconVariantsAreKelpAndTidepool() {
		assertEquals(listOf("Default" to "MainActivityDefault", "Tidepool" to "MainActivityTidepool"),
			AppIconVariant.entries.map { it.name to it.activityName })
		assertEquals(listOf("Kelp and clay", "Tidepool"), AppIconVariant.entries.map { englishString(it.label.key) })

		val kelp = aliasTag("MainActivityDefault")
		assertEquals("true", attr(kelp, "enabled"))
		assertEquals("@mipmap/ic_launcher", attr(kelp, "icon"))
		assertEquals("@mipmap/ic_launcher_round", attr(kelp, "roundIcon"))
		val tidepool = aliasTag("MainActivityTidepool")
		assertEquals("false", attr(tidepool, "enabled"))
		assertEquals("@mipmap/ic_launcher_tidepool", attr(tidepool, "icon"))
		assertEquals("@mipmap/ic_launcher_tidepool_round", attr(tidepool, "roundIcon"))
		assertTrue("Inverted" !in manifest, "the manifest still names the inverted icon")

		for (file in listOf("ic_launcher.xml", "ic_launcher_round.xml")) {
			assertEquals(Triple("@color/ic_launcher_background", "@drawable/ic_launcher_foreground", "@drawable/ic_launcher_monochrome"), layers(file))
		}
		for (file in listOf("ic_launcher_tidepool.xml", "ic_launcher_tidepool_round.xml")) {
			assertEquals(Triple("@color/ic_launcher_background_tidepool", "@drawable/ic_launcher_foreground_tidepool", "@drawable/ic_launcher_monochrome"), layers(file))
		}
		assertEquals("#18211F", color("ic_launcher_background").uppercase())
		assertEquals("#172326", color("ic_launcher_background_tidepool").uppercase())
		for (webp in listOf("ic_launcher", "ic_launcher_round", "ic_launcher_tidepool", "ic_launcher_tidepool_round")) {
			assertTrue(sourceFile("$res/mipmap-xxhdpi/$webp.webp").length() > 0)
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
