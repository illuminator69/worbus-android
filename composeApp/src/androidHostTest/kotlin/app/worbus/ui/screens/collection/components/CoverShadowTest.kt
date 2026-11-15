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
		// A Box of its own, the card's size, beside the card (a sibling, not a modifier of the card's chain).
		val shadowBox = src.substringBefore(".dropShadow(").substringAfterLast("Box(")
		assertTrue(".size(212.dp)" in shadowBox && "sharedElement" !in shadowBox, "the shadow is in a box of its own: $shadowBox")
		val card = src.substringAfter("CoverArt(").substringBefore("crossfadeMs")
		assertTrue(".size(212.dp)" in card && ".sharedElement(" in card && "dropShadow" !in card, "the card flies, the shadow does not: $card")
		assertTrue("rememberCoverShadowFade(" in src, "the shadow's alpha follows the page's transition")
		// Review Minor 2: the fade knows whether the cover really flies, from the same shared content state as the card.
		assertTrue(Regex("""rememberCoverShadowFade\([^\n]*\{ coverState\.isMatchFound \}""").containsMatchIn(src), "the fade reads the cover's match")
		assertTrue("sharedContentState = coverState," in src, "the card flies on that same state")
	}

	/*
	 * Review Minor 2 (2026-11-15): only a flight holds the shadow back. At `6c324d92` any page change did: an album page
	 * popped back to from its artist (no tile to fly from) slid its card in bare and faded the shadow in after.
	 */
	@Test
	fun withNoFlightTheShadowStays() {
		val pre = EnterExitState.PreEnter; val vis = EnterExitState.Visible; val post = EnterExitState.PostExit
		// Entering.
		assertEquals(0f, coverShadowFactor(pre, vis, matchFound = true, flown = false, landed = 0f, flight = 0f), "the first frame of a flight in, before the effect has run")
		assertEquals(0f, coverShadowFactor(pre, vis, matchFound = true, flown = true, landed = 0f, flight = 0f), "flying in")
		assertEquals(1f, coverShadowFactor(pre, vis, matchFound = false, flown = false, landed = 0f, flight = 0f), "sliding in with no flight")
		// At rest.
		assertEquals(0.4f, coverShadowFactor(vis, vis, matchFound = false, flown = true, landed = 0.4f, flight = 0f), "landing fade after a flight")
		assertEquals(1f, coverShadowFactor(vis, vis, matchFound = false, flown = false, landed = 0f, flight = 0f), "settled with no flight")
		// Leaving.
		assertEquals(coverShadowLeaving(0.05f), coverShadowFactor(vis, post, matchFound = true, flown = false, landed = 1f, flight = 0.05f), "flying back")
		assertEquals(0f, coverShadowFactor(vis, post, matchFound = true, flown = true, landed = 1f, flight = 0.5f), "gone early in the flight back")
		assertEquals(1f, coverShadowFactor(vis, post, matchFound = false, flown = true, landed = 1f, flight = 0.5f), "sliding out with no flight")
	}

	/*
	 * Review nit (2026-11-15): the leaving fade is a child animation of the page's transition; on the album slide's 500 ms
	 * it kept a bar tap's 330 ms page change running ~170 ms longer. It takes the cover's own flight timing, tab tap
	 * included.
	 */
	@Test
	fun theLeavingFadeTakesTheFlightsOwnTime() {
		val src = sourceFile("src/commonMain/kotlin/app/worbus/ui/screens/collection/components/CoverShadow.kt").readText()
		val spec = src.substringAfter("transition.animateFloat(").substringBefore("label =")
		assertTrue("sharedFlightTiming(motion, tabTap = tabTaps.isTabTap(stack))" in spec, "the flight's own timing: $spec")
		assertFalse("coverSlideTiming" in src, "not the album slide's fixed time")
	}

	/*
	 * Compose caches a drop shadow's bitmap by its Shadow, alpha included (`AndroidShadowContext.ShadowKey`, ui-graphics
	 * 1.13.0-alpha02), so a shadow whose own alpha moves is blurred again in software on the main thread for every value:
	 * each frame of the landing fade, and of the scroll fade (`titleAlpha`) it always had. Measured on the VM AVD
	 * (profileable release, simpleperf): the blur still ran at the landing. Its alpha stays fixed; the fades go on a
	 * graphics layer holding the shadow alone, which only recomposites.
	 */
	@Test
	fun theShadowsOwnAlphaNeverMoves() {
		val src = sourceFile("src/commonMain/kotlin/app/worbus/ui/screens/collection/components/HeadingRow.kt").readText()
		val block = src.substringAfter(".dropShadow(").substringBefore("}")
		assertFalse("titleAlpha" in block || "shadowFade" in block, "the shadow's own alpha moves: $block")
		val layer = src.substringBefore(".dropShadow(").substringAfterLast("graphicsLayer")
		assertTrue("shadowFade()" in layer && "titleAlpha" in layer, "the fades are on the shadow's own layer")
	}
}
