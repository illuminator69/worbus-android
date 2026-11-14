package app.worbus.ui.util

import androidx.compose.animation.core.TweenSpec
import app.worbus.domain.models.settings.AnimationStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/*
 * The motion lane (option (c), 2026-11-14): one content swap for every chip and segmented switch, the root tabs'
 * fade-through, never the old and the new drawn at once (B-431, B-432), following the style and the speed (B-435).
 */
class ContentSwapTest {
	private val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

	@Test
	fun theOldContentHasGoneBeforeTheNewComesUnderEveryStyleAndSpeed() {
		for (style in AnimationStyle.entries) for (speed in speeds) {
			val m = pageMotionOf(style, speed)
			val w = contentSwapWindows(m)
			assertEquals(tabFadeWindows(m), w, "one fade-through: the root tabs'")
			assertTrue(w.exitEndMs <= w.enterStartMs, "$style at $speed: $w")
			assertTrue(w.exitEndMs > 0, "never a cut")
		}
	}

	@Test
	fun itFollowsTheSpeed() {
		val one = contentSwapWindows(pageMotionOf(AnimationStyle.Expressive))
		assertEquals(PageFadeWindows(0, 100, 100, 330), one)
		assertEquals(PageFadeWindows(0, 200, 200, 660), contentSwapWindows(pageMotionOf(AnimationStyle.Expressive, 0.5f)))
		assertEquals(PageFadeWindows(0, 50, 50, 165), contentSwapWindows(pageMotionOf(AnimationStyle.Expressive, 2f)))
	}

	@Test
	fun rowsThatLeaveAreGoneBeforeRowsMoveOrArrive() {
		for (style in AnimationStyle.entries) for (speed in speeds) {
			val items = swapItemMotionOf(pageMotionOf(style, speed))
			val out = assertIs<TweenSpec<Float>>(items.fadeOut)
			val arrive = assertIs<TweenSpec<Float>>(items.fadeIn)
			val move = assertIs<TweenSpec<*>>(items.placement)
			val outEnd = out.delay + out.durationMillis
			assertTrue(outEnd <= arrive.delay, "$style at $speed: a row fades in while another fades out")
			assertTrue(outEnd <= move.delay, "$style at $speed: a staying row slides over a leaving one")
			assertEquals(arrive.delay + arrive.durationMillis, move.delay + move.durationMillis, "they land together")
		}
		val fast = assertIs<TweenSpec<Float>>(swapItemMotionOf(pageMotionOf(AnimationStyle.Expressive, 2f)).fadeIn)
		assertEquals(50, fast.delay)
		assertEquals(115, fast.durationMillis)
	}

	@Test
	fun eachSectionKeepsItsOwnSavedState() {
		assertNotEquals(stateKeyOf(null), stateKeyOf("Albums"))
		assertNotEquals(stateKeyOf("Albums"), stateKeyOf("Artists"))
		assertEquals(stateKeyOf("Albums"), stateKeyOf("Albums"))
	}
}
