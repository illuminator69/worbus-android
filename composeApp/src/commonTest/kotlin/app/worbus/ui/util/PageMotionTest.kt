package app.worbus.ui.util

import app.worbus.domain.models.settings.AnimationStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Quick animation style (I-042): the old two styles keep the timing Worbus always had, and
 * Quick is shorter everywhere.
 */
class PageMotionTest {

	@Test
	fun expressiveAndStandardKeepTodaysNumbers() {
		// B-378 (the owner's ruling, 2026-11-09) moved the fades and the outgoing slide: staggered
		// fades (PageFadeTest), both pages sliding the whole 500 ms. The rest is as it always was.
		val today = PageMotion(
			enterFadeMs = 325,
			enterFadeDelayMs = 175,
			enterSlideMs = 500,
			enterSlideDp = null,
			enterEasing = PageEasing.Emphasized,
			exitFadeMs = 175,
			exitSlideMs = 500,
			exitSlideDp = 30,
			sheetShowMs = 400,
			sheetHideMs = 600,
			// B-403 (the owner, 2026-11-13): a tab's fade-through and the regular slide.
			tabExitFadeMs = 90,
			tabEnterFadeMs = 210,
			slideMs = 300
		)
		assertEquals(today, pageMotionOf(AnimationStyle.Expressive))
		assertEquals(today, pageMotionOf(AnimationStyle.Standard))
	}

	@Test
	fun everyQuickDurationIsShortAndShorterThanBefore() {
		val old = pageMotionOf(AnimationStyle.Expressive)
		val quick = pageMotionOf(AnimationStyle.Quick)
		val pairs = listOf(
			"enterFade" to (quick.enterFadeMs to old.enterFadeMs),
			"enterSlide" to (quick.enterSlideMs to old.enterSlideMs),
			"exitFade" to (quick.exitFadeMs to old.exitFadeMs),
			"exitSlide" to (quick.exitSlideMs to old.exitSlideMs),
			"sheetShow" to (quick.sheetShowMs to old.sheetShowMs),
			"sheetHide" to (quick.sheetHideMs to old.sheetHideMs),
			"tabExitFade" to (quick.tabExitFadeMs to old.tabExitFadeMs),
			"tabEnterFade" to (quick.tabEnterFadeMs to old.tabEnterFadeMs),
			"slide" to (quick.slideMs to old.slideMs)
		)
		for ((name, durations) in pairs) {
			val (q, o) = durations
			assertTrue(q <= 350, "$name: $q ms > 350")
			assertTrue(q < o, "$name: $q ms is not below $o")
		}
		// A fixed, short travel instead of half the screen.
		assertTrue((quick.enterSlideDp ?: Int.MAX_VALUE) <= 48)
	}

	@Test
	fun aFoldFollowsTheStyle() {
		// Expressive and Standard have no number of their own: the fold takes the motion scheme's springs
		// (the scheme `NavicTheme` builds for the style), so the two differ as the rest of the app does.
		for (style in listOf(AnimationStyle.Expressive, AnimationStyle.Standard)) {
			assertNull(foldMs(pageMotionOf(style), tall = false), "$style")
			assertNull(foldMs(pageMotionOf(style), tall = true), "$style")
		}
		// Quick: short, a box that holds a list longer than a few lines, and both under the 350 the rest
		// of Quick keeps to.
		val quick = pageMotionOf(AnimationStyle.Quick)
		val short = foldMs(quick, tall = false)!!
		val tall = foldMs(quick, tall = true)!!
		assertTrue(short in 1..tall, "$short .. $tall")
		assertTrue(tall <= 350, "$tall ms > 350")
	}

	@Test
	fun theEnumIsAppendOnlyBecauseItIsStoredByOrdinal() {
		assertEquals(
			listOf(AnimationStyle.Expressive, AnimationStyle.Standard, AnimationStyle.Quick),
			AnimationStyle.entries.toList()
		)
		assertEquals(0, AnimationStyle.Expressive.ordinal)
		assertEquals(1, AnimationStyle.Standard.ordinal)
		assertEquals(2, AnimationStyle.Quick.ordinal)
	}
}
