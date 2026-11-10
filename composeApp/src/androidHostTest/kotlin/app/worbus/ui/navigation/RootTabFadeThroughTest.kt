package app.worbus.ui.navigation

import app.worbus.sourceFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/*
 * Ruling 7 (controller, 2026-11-10): the "Predictive back animations" setting is removed (B-378 made
 * one back animation for every way of going back, so it changed nothing), and a root-tab switch
 * uses the same staggered fade-through as a page push: the old tab fades out, then the new one fades
 * in (no slide). The three root-tab specs cross-faded both tabs at once (`fadeIn()` + `fadeOut()`).
 */
class RootTabFadeThroughTest {
	private val app = sourceFile("src/commonMain/kotlin/app/worbus/App.kt").readText()

	@Test
	fun theSettingIsGone() {
		val files = listOf(
			"src/commonMain/kotlin/app/worbus/ui/screens/settings/AppearanceScreen.kt",
			"src/commonMain/kotlin/app/worbus/domain/manager/PreferenceManager.kt",
			"src/commonMain/kotlin/app/worbus/App.kt"
		)
		for (path in files) assertTrue("enablePredictiveBackAnimations" !in sourceFile(path).readText(), path)
		val resources = sourceFile("src/commonMain/composeResources")
		val hits = resources.walkTopDown().filter { it.name == "strings.xml" }
			.filter { "option_enable_predictive_back_animations" in it.readText() }.map(File::getPath).toList()
		assertTrue(hits.isEmpty(), "the setting's string: $hits")
		// The manifest keeps predictive Back itself (SingleActivityLaunchTest).
	}

	@Test
	fun theRootTabsFadeThrough() {
		val tabs = app.substringAfter("val navtabMetadata").substringBefore("val imageViewMetadata")
		assertTrue("fadeIn()" !in tabs && "fadeOut()" !in tabs, "no simultaneous default fades:\n$tabs")
		assertTrue("Material3Transitions.tabEnter" in tabs && "Material3Transitions.tabExit" in tabs, tabs)
		assertTrue(Regex("(?i)transitionSpec \\{").findAll(tabs).count() == 3, "all three specs (push, pop, predictive pop):\n$tabs")
	}
}
