package app.worbus.ui.util

import app.worbus.domain.models.settings.AnimationStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The animation styles (I-042), as the owner ruled them on 2026-11-13 evening (I-075): Quick is the page
 * motion Expressive had at `4b592a4e`; Expressive and Standard are back to the speed and easing of
 * `bd39384d`, with B-378's, B-403's and B-404's structure kept (PageFadeTest, PageChangeTest,
 * CoverPageChangeTest, OneBackAnimationTest).
 */
class PageMotionTest {

	/** Expressive's numbers at `4b592a4e` (the owner's "current speed"), the motion Quick takes. */
	private val at4b592a4e = PageMotion(
		enterFadeMs = 325,
		enterFadeDelayMs = 175,
		enterSlideMs = 500,
		enterSlideDp = null,
		enterEasing = PageEasing.Emphasized,
		exitFadeMs = 175,
		exitSlideMs = 500,
		exitSlideDp = 30,
		tabExitFadeMs = 90,
		tabEnterFadeMs = 210,
		slideMs = 300,
		slideEasing = PageEasing.EaseOutQuart
	)

	@Test
	fun quickIsTheSpeedExpressiveHadAt4b592a4e() {
		val quick = pageMotionOf(AnimationStyle.Quick)
		// Every page and tab number this session's B-378, B-403 and B-404 set, but the slide and fade, which is
		// 300 ms since review M2 (the owner, 2026-11-13: "make it 300"), its fades scaled with it (x 0.6).
		assertEquals(
			at4b592a4e.copy(
				enterFadeMs = 195, enterFadeDelayMs = 105, enterSlideMs = 300, exitFadeMs = 105, exitSlideMs = 300,
				foldPace = 500f / 300f
			),
			quick
		)
		// Settings' slide 300 ms on EaseOutQuart, the album slide and the cover's flight 300 ms on Emphasized.
		assertEquals(SlideTiming(300, PageEasing.Emphasized), coverSlideTiming(quick))
		assertEquals(300, quick.slideMs)
		assertEquals(PageEasing.EaseOutQuart, quick.slideEasing)
		assertEquals(PageFadeWindows(0, 90, 90, 300), tabFadeWindows(quick))
		// Review M2: the old page out over 0-105, the new one in over 105-300, ending with the 300 ms slide.
		assertEquals(PageFadeWindows(0, 105, 105, 300), pageFadeWindows(quick))
		assertEquals(300, quick.enterSlideMs)
		assertEquals(300, quick.exitSlideMs)
	}

	@Test
	fun expressiveAndStandardAreBackToBd39384dsSpeed() {
		val before = PageMotion(
			enterFadeMs = 325,
			enterFadeDelayMs = 175,
			enterSlideMs = 500,
			enterSlideDp = null,
			enterEasing = PageEasing.Emphasized,
			exitFadeMs = 175,
			exitSlideMs = 500,
			exitSlideDp = 30,
			// The old springs' cross-fade settled in ~330 ms; a fade-through of that length, 30 / 70.
			tabExitFadeMs = 100,
			tabEnterFadeMs = 230,
			// Every page change was 500 ms on Emphasized at `bd39384d`, Settings' and an album's too.
			slideMs = 500,
			slideEasing = PageEasing.Emphasized
		)
		assertEquals(before, pageMotionOf(AnimationStyle.Expressive))
		assertEquals(before, pageMotionOf(AnimationStyle.Standard), "Standard shares Expressive's table")
		// The album slide and the cover's flight: `bd39384d`'s 500 ms page change, on its Emphasized curve.
		assertEquals(SlideTiming(500, PageEasing.Emphasized), coverSlideTiming(pageMotionOf(AnimationStyle.Expressive)))
		assertEquals(PageFadeWindows(0, 100, 100, 330), tabFadeWindows(pageMotionOf(AnimationStyle.Expressive)))
	}

	@Test
	fun expressiveIsNeverQuickerThanQuick() {
		val e = pageMotionOf(AnimationStyle.Expressive)
		val q = pageMotionOf(AnimationStyle.Quick)
		val pairs = listOf(
			"enterFade" to (q.enterFadeMs to e.enterFadeMs),
			"enterSlide" to (q.enterSlideMs to e.enterSlideMs),
			"exitFade" to (q.exitFadeMs to e.exitFadeMs),
			"exitSlide" to (q.exitSlideMs to e.exitSlideMs),
			"tabExitFade" to (q.tabExitFadeMs to e.tabExitFadeMs),
			"tabEnterFade" to (q.tabEnterFadeMs to e.tabEnterFadeMs),
			"slide" to (q.slideMs to e.slideMs)
		)
		for ((name, durations) in pairs) {
			val (quick, expressive) = durations
			assertTrue(quick <= expressive, "$name: Quick $quick ms > Expressive $expressive ms")
		}
		assertTrue(q.slideMs < e.slideMs, "the album and Settings slides are what Quick shortens")
		assertTrue(q.enterSlideMs < e.enterSlideMs, "review M2: and the ordinary push (300 against 500)")
	}

	// ---- I-075: the animation speed ------------------------------------------------------------ //

	@Test
	fun theSpeedScalesEveryTimeAndNothingElse() {
		for (style in AnimationStyle.entries) {
			val base = pageMotionOf(style)
			assertEquals(base, pageMotionOf(style, 1f), "$style: 1x is the style")
			val fast = pageMotionOf(style, 2f)
			val slow = pageMotionOf(style, 0.5f)
			assertEquals(base.enterSlideMs / 2, fast.enterSlideMs, "$style")
			assertEquals(base.enterSlideMs * 2, slow.enterSlideMs, "$style")
			assertEquals(base.slideMs / 2, fast.slideMs, "$style")
			assertEquals(base.exitFadeMs * 2, slow.exitFadeMs, "$style")
			assertEquals(base.tabEnterFadeMs * 2, slow.tabEnterFadeMs, "$style")
			assertEquals(base.enterFadeDelayMs * 2, slow.enterFadeDelayMs, "$style")
			// Distances and curves do not move.
			assertEquals(base.enterSlideDp, fast.enterSlideDp)
			assertEquals(base.exitSlideDp, fast.exitSlideDp)
			assertEquals(base.enterEasing, fast.enterEasing)
			assertEquals(base.slideEasing, fast.slideEasing)
			// A fold's pace is relative to the motion scheme, which NavicTheme scales by the speed itself.
			assertEquals(base.foldPace, slow.foldPace, "$style")
			// The structure holds at every speed: fades never overlap, the fade in ends with the slide, the
			// cover lands with the album slide.
			for (speed in listOf(0.5f, 0.75f, 1.25f, 1.5f, 1.75f, 2f)) {
				val m = pageMotionOf(style, speed)
				val w = pageFadeWindows(m)
				assertTrue(w.exitEndMs <= w.enterStartMs, "$style at $speed")
				assertEquals(m.enterSlideMs, w.enterEndMs, "$style at $speed")
				val t = tabFadeWindows(m)
				assertTrue(t.exitEndMs <= t.enterStartMs, "$style at $speed")
				assertEquals(SlideTiming(m.slideMs, m.enterEasing), coverSlideTiming(m))
			}
		}
		// 1.25x of Expressive: 400 ms album slide, 264 ms tab fade-through.
		val e = pageMotionOf(AnimationStyle.Expressive, 1.25f)
		assertEquals(400, e.slideMs)
		assertEquals(80 + 184, tabFadeWindows(e).enterEndMs)
	}

	@Test
	fun theSpeedIsClampedToTheSlidersStops() {
		assertEquals(0.5f, motionSpeedOf(0.1f))
		assertEquals(2f, motionSpeedOf(9f))
		assertEquals(1f, motionSpeedOf(Float.NaN))
		assertEquals(1.25f, motionSpeedOf(1.3f))
		assertEquals(0.75f, motionSpeedOf(0.8f))
		assertEquals(pageMotionOf(AnimationStyle.Expressive, 2f), pageMotionOf(AnimationStyle.Expressive, 5f))
		// The slider: 0.5 to 2 by quarters is seven stops, five between the ends.
		val stops = (0..MOTION_SPEED_STEPS + 1).map { MOTION_SPEED_MIN + it * (MOTION_SPEED_MAX - MOTION_SPEED_MIN) / (MOTION_SPEED_STEPS + 1) }
		assertEquals(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f), stops)
		assertEquals(MOTION_SPEED_DEFAULT, 1f)
	}

	@Test
	fun theSpeedSaysItself() {
		assertEquals("1×", motionSpeedLabel(1f))
		assertEquals("0.5×", motionSpeedLabel(0.5f))
		assertEquals("0.75×", motionSpeedLabel(0.75f))
		assertEquals("1.25×", motionSpeedLabel(1.25f))
		assertEquals("2×", motionSpeedLabel(2f))
	}

	@Test
	fun aFoldFollowsTheStyle() {
		// B-436: every style folds on the motion scheme's spring; Expressive and Standard at its own pace (the two
		// differ as the rest of the app does), Quick at its page motion's pace, 500 / 300.
		for (style in listOf(AnimationStyle.Expressive, AnimationStyle.Standard)) assertEquals(1f, pageMotionOf(style).foldPace, "$style")
		val quick = pageMotionOf(AnimationStyle.Quick)
		assertEquals(pageMotionOf(AnimationStyle.Expressive).enterSlideMs.toFloat() / quick.enterSlideMs, quick.foldPace)
		assertTrue(quick.foldPace > 1f, "Quick's folds are quicker than Expressive's")
	}

	// B-436's "a sheet closes faster than it opens" was superseded by B-442 (the owner's walk, 2026-11-14): a sheet
	// closes on the spring it opens on (`SheetCloseMotionTest`); sheets are no longer timed by the page motion.

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

	/**
	 * Motion lane review L3 (2026-11-14): on a bar tap the cover's flight and the bar's bounds take the tap's
	 * fade-through time, so the cover never lands after the page (B-404's rule) and a tap is not held at 500 ms.
	 */
	@Test
	fun aBarTapsFlightsLandWithItsFadeThrough() {
		for (style in AnimationStyle.entries) for (speed in listOf(0.5f, 1f, 1.5f, 2f)) {
			val m = pageMotionOf(style, speed)
			val tap = sharedFlightTiming(m, tabTap = true)
			assertEquals(tabFadeWindows(m).enterEndMs, tap.durationMs, "$style at $speed")
			assertTrue(tap.durationMs <= coverSlideTiming(m).durationMs, "$style at $speed: a tap outlasts a push")
			assertEquals(coverSlideTiming(m), sharedFlightTiming(m, tabTap = false), "$style at $speed")
		}
		assertEquals(330, sharedFlightTiming(pageMotionOf(AnimationStyle.Expressive), tabTap = true).durationMs)
	}
}
