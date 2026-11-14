package app.worbus.ui.util

import app.worbus.domain.models.settings.AnimationStyle
import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The motion lane, option (c) (the owner's ruling of 2026-11-14; `ASSESSMENT-android-motion-2026-11-14.md`): one
 * motion pattern per context. One case per broken item (B-431..B-436) and per inconsistency the lane settled, each
 * seen failing at `a2f6577e` (it uses only what that tree has: source text and `pageMotionOf`). The rules
 * themselves are held by `ContentSwapTest`, `TabTapMotionTest`, `PageMotionTest` and `ContentSwapGuardTest`.
 */
class MotionLaneTest {
	private fun src(p: String) = sourceFile("src/commonMain/kotlin/app/worbus/$p").readText()

	/** The argument list of the first `name(` call after [from] in [text], by matching parentheses. */
	private fun args(text: String, name: String, from: Int = 0): String {
		val start = text.indexOf("$name(", from).also { assertTrue(it >= 0, "$name( not found") } + name.length + 1
		var depth = 1
		var i = start
		while (depth > 0 && i < text.length) {
			when (text[i]) { '(' -> depth++; ')' -> depth-- }
			i++
		}
		return text.substring(start, i - 1)
	}

	@Test
	fun b431_libraryMatchesSwapsWholeSections() {
		val s = src("ui/screens/matches/LibraryMatchesScreen.kt")
		// The section's list is the swap's content: To fix's rows no longer fade out over the next section's.
		val swap = s.indexOf("ContentSwap(section")
		assertTrue(swap >= 0, "the chips do not swap through ContentSwap")
		assertTrue(swap < s.indexOf("LazyColumn("), "the list is not inside the swap")
	}

	@Test
	fun b432_libraryChipsFadeThroughAndKeepRecentsState() {
		val s = src("ui/screens/library/LibraryScreen.kt")
		assertFalse("togetherWith fadeOut" in s, "the bodies still cross-fade over each other")
		assertTrue("ContentSwap(chip, keepState = true" in s, "Recents' saved state (its arrival gate) is not kept")
		assertTrue(Regex("""SaveableStateProvider\(""").containsMatchIn(src("ui/util/ContentSwap.kt")))
	}

	@Test
	fun b433_queueTabsStaggerTheirFades() {
		val q = src("ui/screens/queue/QueueScreen.kt")
		assertFalse("defaultEffectsSpec" in q, "the tabs fade on the scheme's spring, both at once")
		val axis = src("ui/util/Material3Transitions.kt").substringAfter("fun inPageAxis(", "")
		assertTrue("delayMillis = motion.enterFadeDelayMs" in axis, "the new tab does not wait for the old one")
	}

	@Test
	fun b434_aBarTapFadesThroughWhicheverWayItGoes() {
		val app = src("App.kt")
		val pop = app.substringAfter("popTransitionSpec = {\n", "").substringBefore("predictivePopTransitionSpec")
		assertTrue("tabTaps.isTabTap(backStack)" in pop, "a tap that pops runs the page's own motion")
		assertTrue("tabTaps.tapped(" in src("ui/navigation/ShellTab.kt"), "the tap does not mark its stack")
	}

	@Test
	fun b435_everyListedMotionFollowsTheSpeed() {
		assertFalse("AnimatedContent(" in src("ui/screens/sourcehistory/SourceHistoryScreen.kt"), "Source history's default spec")
		val viewer = src("App.kt").substringAfter("val imageViewMetadata").substringBefore("return ")
		assertFalse("fadeIn()" in viewer || "fadeOut()" in viewer, "the image viewer's default fade")
		assertTrue(".atMotionSpeed()" in src("ui/components/layouts/RootBottomBar.kt"), "the bottom bar's collapse")
		val mini = src("ui/components/layouts/MiniPlayer.kt")
		assertTrue("enter =" in args(mini, "AnimatedVisibility"), "the mini player's appearing")
		assertTrue(".atSpeed(speed)" in src("ui/components/sheets/ModalBottomSheet.kt"), "the sheet's content height")
		assertTrue("animationSpec" in args(src("ui/screens/share/ShareListScreen.kt"), "Crossfade"), "the share list")
		assertFalse("fadeIn()" in src("ui/components/sheets/AddToPlaylistSheet.kt"), "the add-to-playlist row")
		// Two-pane motion cannot be passed to the adaptive scene: it is on the documented unscaled list.
		assertTrue("PaneMotionDefaults" in src("ui/util/MotionSpeed.kt"), "two-pane motion is neither scaled nor listed")
	}

	@Test
	fun b436_sheetsCloseFasterThanTheyOpen() {
		for (style in AnimationStyle.entries) {
			val m = pageMotionOf(style)
			assertTrue(m.sheetHideMs < m.sheetShowMs, "$style: ${m.sheetShowMs} in, ${m.sheetHideMs} out")
		}
	}

	@Test
	fun b436_everyStyleFoldsOnASpring() {
		val fold = src("ui/util/Fold.kt")
		assertFalse("tween(" in fold, "Quick folds on a tween beside its springs")
		assertTrue("defaultSpatialSpec" in fold && "defaultEffectsSpec" in fold)
	}

	@Test
	fun oneBottomBarWhileThePagesChange() {
		// Each page draws its own bar: a whole-width slide drew two, side by side. Shared across the pages, one.
		assertTrue("sharedElement(" in src("ui/components/layouts/RootBottomBar.kt"))
	}
}
