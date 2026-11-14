package app.worbus.ui.screens.collection.components

import androidx.compose.animation.EnterExitState
import app.worbus.domain.models.settings.AnimationStyle
import app.worbus.sourceFile
import app.worbus.ui.util.pageMotionOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * B-447 (the owner's ruling 2026-11-14, option c): no cover shadow while the cover flies, faded in once it lands; and
 * on a predictive Back the shadow fades out with the gesture instead of vanishing at once, and comes back if the gesture
 * is cancelled.
 *
 * Measured before (B-446 lane, VM AVD, release, simpleperf): the shadow (`dropShadow`, 30 dp blur) FOLLOWED
 * `sharedElement`, so it was drawn by the flying cover at a new size every frame and its blurred mask made again in
 * software each time (Skia `SkMaskBlurFilter`, 16-35 ms a frame, ~110-130 ms of main thread per album open). On a
 * Back the flight draws the tile's content (the target), which has no shadow, so the page's shadow popped off.
 */
class CoverShadowTest {
	@Test
	fun theShadowIsDrawnOnlyWhileThePageIsAtRest() {
		assertFalse(coverShadowAtRest(EnterExitState.PreEnter, EnterExitState.Visible), "the cover is flying in")
		assertTrue(coverShadowAtRest(EnterExitState.Visible, EnterExitState.Visible), "landed")
		assertFalse(coverShadowAtRest(EnterExitState.Visible, EnterExitState.PostExit), "leaving")
	}

	@Test
	fun theShadowFadesInThePagesOwnFadeTime() {
		for (style in AnimationStyle.entries) for (speed in listOf(0.5f, 1f, 2f)) {
			val m = pageMotionOf(style, speed)
			assertEquals(m.exitFadeMs, coverShadowFadeMs(m), "$style at $speed")
		}
	}

	/* A Back seeks the cover's flight; the shadow (at the card's place) must be gone before the cover has visibly left
	 * it, and back in full when the gesture is cancelled. */
	@Test
	fun theShadowIsGoneEarlyInTheFlightBack() {
		assertEquals(1f, coverShadowLeaving(0f))
		assertEquals(0f, coverShadowLeaving(COVER_SHADOW_GONE_AT))
		assertEquals(0f, coverShadowLeaving(1f))
		assertEquals(0.5f, coverShadowLeaving(COVER_SHADOW_GONE_AT / 2), 1e-4f)
		assertTrue(COVER_SHADOW_GONE_AT <= 0.15f, "gone before the cover is a sixth of its way")
	}

	@Test
	fun theShadowStaysAtTheCardsPlaceAndSize() {
		val src = sourceFile("src/commonMain/kotlin/app/worbus/ui/screens/collection/components/HeadingRow.kt").readText()
		val shadow = src.indexOf(".dropShadow(")
		val flight = src.indexOf(".sharedElement(")
		assertTrue(shadow in 0 until flight, "the shadow is outside the shared element, so it is never drawn at the flight's size")
		assertTrue("rememberCoverShadowFade(" in src, "the shadow's alpha follows the page's transition")
	}
}
