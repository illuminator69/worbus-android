package app.worbus.ui.util

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.worbus.domain.models.settings.AnimationStyle
import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The review of the motion lane, option (c) (`REVIEW-motion-android-2026-11-14.md`): one case per finding the fix
 * changed, each seen failing at `8a7e2fb8` (they use only what that tree has: source text and the transforms it
 * already had). The rules themselves are held by `TabTapMotionTest`, `RowSwapTest`, `PageMotionTest` and
 * `EasingsTest` (commonTest).
 */
class MotionReviewTest {
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

	private val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

	@Test
	fun m1_theSharedElementsOverlayIsUnderTheDrawerAndTheSnackbar() {
		val app = src("App.kt")
		val layout = Regex("""SharedTransitionLayout\s*[({]""").find(app)?.range?.first ?: -1
		assertTrue(layout >= 0, "no SharedTransitionLayout")
		// The overlay is drawn over everything INSIDE the layout: the drawer and the app's snackbar must be outside it.
		assertTrue(app.indexOf("AvatarMenuDrawer {") in 0 until layout, "the avatar menu drawer is inside the shared layout")
		assertTrue(app.indexOf("snackbarHost = {") in 0 until layout, "the app's snackbar is inside the shared layout")
		assertTrue(layout < app.indexOf("NavDisplay("), "the pages are not inside the shared layout")
		assertTrue(app.indexOf("LocalSharedTransitionScope provides") > layout)
	}

	@Test
	fun m2_freshsScopeChipsResetTheScrollThroughTheChipHelper() {
		val fresh = src("ui/screens/fresh/FreshScreen.kt")
		assertTrue("chipTapped(gridState)" in args(fresh, "FreshScopeChips"), "the header's scope chips do not reset the scroll")
		val typeChips = args(fresh, "PageChips")
		assertTrue("chipTapped(gridState)" in typeChips, "the type chips do not go through the same call")
		assertTrue("state = gridState" in args(fresh, "ArtGrid"), "the grid's state is not the one reset")
	}

	@Test
	fun l3_aBarTapsFlightsTakeTheTapsTime() {
		val bounds = src("ui/util/Material3Transitions.kt")
			.substringAfter("fun rememberCoverBoundsTransform()", "").substringBefore("\n}\n")
		assertTrue("isTabTap(" in bounds, "a bar tap's cover flight and bar bounds take the album slide's 500 ms")
	}

	/** Held already at `8a7e2fb8` (the finding did not reproduce): a guard, not a red. */
	@Test
	fun l4_theBarsSelectionIsTheCommittedStacksNotThePagesBeingDragged() {
		val bar = src("ui/components/layouts/BottomBar.kt")
		assertTrue("val backStack = LocalNavStack.current" in bar && "tab.isSelected(top)" in bar)
		assertTrue("val top = backStack.lastOrNull()" in bar)
	}

	@Test
	fun l5_theTapsMarkIsTheActivitysAndEndsWithItsTransition() {
		assertFalse("TabTapMotion" in src("domain/manager/ShellController.kt"), "one mark shared by every activity")
		val app = src("App.kt")
		assertTrue("remember(backStack) { TabTapMotion() }" in app, "the mark is not the activity's")
		assertTrue("tabTaps.settled(" in app, "the mark outlives its transition")
	}

	@Test
	fun l6_libraryMatchesFirstChoiceIsNotAChipsSwap() {
		val matches = src("ui/screens/matches/LibraryMatchesScreen.kt")
		assertTrue("animate =" in args(matches, "ContentSwap"), "the page's own first choice fades through like a tap")
	}

	@Test
	fun l7_onlyAChipsChangeSwapsTheRows() {
		for (p in listOf(
			"ui/screens/savedqueues/SavedQueuesScreen.kt",
			"ui/screens/search/SearchScreen.kt",
			"ui/screens/fresh/FreshScreen.kt",
			"ui/screens/browse/BrowseFeedScreen.kt"
		)) {
			val s = src(p)
			assertFalse("SwapRows {" in s, "$p: every change of the list is staged")
			assertTrue("chipTapped(" in s, "$p: no chip arms the swap")
		}
	}

	@Test
	fun l8_noPathIsBuiltWhenTheTransitionsLoad() {
		val t = src("ui/util/Material3Transitions.kt")
		assertFalse("Path()" in t, "the file class builds an android.graphics.Path")
	}

	// ----- L8: the transforms the screens run, on the host ----- //

	private fun fadeSpec(t: EnterTransition) = assertIs<TweenSpec<Float>>(assertNotNull(t.config.fade).animationSpec)
	private fun fadeSpec(t: ExitTransition) = assertIs<TweenSpec<Float>>(assertNotNull(t.config.fade).animationSpec)
	private val size = IntSize(400, 800)

	private fun assertFadeThrough(t: ContentTransform, m: PageMotion, what: String) {
		val w = tabFadeWindows(m)
		val out = fadeSpec(t.initialContentExit)
		val into = fadeSpec(t.targetContentEnter)
		assertEquals(w.exitStartMs, out.delay, what)
		assertEquals(w.exitEndMs - w.exitStartMs, out.durationMillis, what)
		assertEquals(w.enterStartMs, into.delay, what)
		assertEquals(w.enterEndMs - w.enterStartMs, into.durationMillis, what)
		assertTrue(out.delay + out.durationMillis <= into.delay, "$what: the old and the new drawn at once")
		// A fade-through: no slide, no scale.
		assertNull(t.targetContentEnter.config.slide, what)
		assertNull(t.initialContentExit.config.slide, what)
		assertNull(t.targetContentEnter.config.scale, what)
	}

	@Test
	fun l8_aTabSwitchFadesThroughUnderEveryStyleAndSpeed() {
		for (style in AnimationStyle.entries) for (speed in speeds) {
			val m = pageMotionOf(style, speed)
			assertFadeThrough(Material3Transitions.tab(m), m, "tab, $style at $speed")
		}
		val one = Material3Transitions.tab(pageMotionOf(AnimationStyle.Expressive))
		assertEquals(100, fadeSpec(one.targetContentEnter).delay)
		assertEquals(230, fadeSpec(one.targetContentEnter).durationMillis)
	}

	@Test
	fun l8_aContentSwapIsTheTabsFadeThrough() {
		for (style in AnimationStyle.entries) for (speed in speeds) {
			val m = pageMotionOf(style, speed)
			assertFadeThrough(contentSwapTransform(m), m, "content swap, $style at $speed")
		}
	}

	@Test
	fun l8_queueTabsSlideAQuarterAndFadeOneAfterTheOther() {
		for (style in AnimationStyle.entries) for (speed in speeds) for (forward in listOf(true, false)) {
			val m = pageMotionOf(style, speed)
			val t = Material3Transitions.inPageAxis(m, forward)
			val what = "inPageAxis, $style at $speed, forward $forward"
			val out = fadeSpec(t.initialContentExit)
			val into = fadeSpec(t.targetContentEnter)
			assertEquals(m.exitFadeMs, out.durationMillis, what)
			assertEquals(m.enterFadeDelayMs, into.delay, what)
			assertEquals(m.enterFadeMs, into.durationMillis, what)
			assertTrue(out.delay + out.durationMillis <= into.delay, "$what: both tabs half drawn")
			val sign = if (forward) 1 else -1
			val slideIn = assertNotNull(t.targetContentEnter.config.slide, what)
			val slideOut = assertNotNull(t.initialContentExit.config.slide, what)
			assertEquals(IntOffset(sign * size.width / 4, 0), slideIn.slideOffset(size), "$what: from the tab's side")
			assertEquals(IntOffset(-sign * size.width / 4, 0), slideOut.slideOffset(size), "$what: away from it")
			assertEquals(m.enterSlideMs, assertIs<TweenSpec<IntOffset>>(slideIn.animationSpec).durationMillis, what)
			assertEquals(m.exitSlideMs, assertIs<TweenSpec<IntOffset>>(slideOut.animationSpec).durationMillis, what)
		}
	}
}
