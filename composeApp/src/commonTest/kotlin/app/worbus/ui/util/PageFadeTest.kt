package app.worbus.ui.util

import app.worbus.domain.models.settings.AnimationStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * B-378 (the owner, 2026-11-09, on the phone, Settings › Server): mid-transition the two pages'
 * titles were drawn on top of each other. The pages are transparent (the wash is behind NavDisplay,
 * `AppWash`), and the incoming page faded in from the first frame (450 ms) while the outgoing one
 * was still fading out (250 ms): for 250 ms both texts showed through each other. The owner's
 * ruling: keep the slide and fade, stagger the fades (Material's shared-axis timing): the outgoing
 * page fades out over about the first third while it slides, the incoming fades in over the rest
 * while it slides; both move the whole time, and the old page is visible on the first frames.
 */
class PageFadeTest {
	private val styles = AnimationStyle.entries
	// I-075: and at every animation speed the slider offers.
	private val speeds = listOf(0.5f, 1f, 2f)

	@Test
	fun theTwoFadesNeverOverlap() {
		for (style in styles) for (speed in speeds) {
			val w = pageFadeWindows(pageMotionOf(style, speed))
			assertTrue(w.exitEndMs <= w.enterStartMs, "$style: out ${w.exitStartMs}..${w.exitEndMs}, in ${w.enterStartMs}..${w.enterEndMs}")
		}
	}

	@Test
	fun theOldPageFadesFromTheFirstFrameOverAboutAThird() {
		for (style in styles) for (speed in speeds) {
			val m = pageMotionOf(style, speed)
			val w = pageFadeWindows(m)
			assertEquals(0, w.exitStartMs, "$style")
			val third = w.exitEndMs.toFloat() / m.enterSlideMs
			assertTrue(third in 0.25f..0.45f, "$style: the old page fades over ${(third * 100).toInt()} % of the motion")
			// Review M2: Quick's push is 300 ms, so at 2x a 150 ms push whose old page fades over 53 ms (three
			// frames at 60 Hz): still a fade, never a cut.
			assertTrue(w.exitEndMs >= 50, "$style at $speed: never a cut (${w.exitEndMs} ms)")
		}
	}

	@Test
	fun theNewPageFadesInOverTheRestAndBothMoveTheWholeTime() {
		for (style in styles) for (speed in speeds) {
			val m = pageMotionOf(style, speed)
			val w = pageFadeWindows(m)
			assertEquals(m.enterSlideMs, w.enterEndMs, "$style: the fade in ends with the slide")
			assertEquals(m.enterSlideMs, m.exitSlideMs, "$style: the old page slides as long as the new one")
		}
	}
}
