package app.worbus.ui.screens.collection.components

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import app.worbus.ui.util.PageMotion
import app.worbus.ui.util.coverSlideTiming
import app.worbus.ui.util.curve
import kotlinx.coroutines.flow.first

/*
 * B-447 (the owner's ruling 2026-11-14, option c): the album page's cover card shadow is not drawn while the cover flies,
 * fades in once it has landed, and fades out as the cover leaves, a dragged (predictive) Back included, coming back if
 * the gesture is cancelled.
 *
 * The shadow sits OUTSIDE the cover's shared element (`HeadingRow.kt`), at the card's place and size. Inside it, it
 * followed the flight: measured at a new size every frame, its 30 dp blur was made again in software on the main thread
 * each time (Skia `SkMaskBlurFilter`, 16-35 ms a frame, ~110-130 ms per album open on the VM AVD). And a Back's flight
 * draws the target's content, the tile, which has no shadow, so the shadow popped off as the gesture began.
 */

/** Whether the page holding the cover is at rest: not entering (the cover in flight) and not leaving. */
internal fun coverShadowAtRest(current: EnterExitState, target: EnterExitState): Boolean =
	current == EnterExitState.Visible && target == EnterExitState.Visible

/** How long the shadow takes to fade in after landing: the outgoing page's own fade. */
internal fun coverShadowFadeMs(motion: PageMotion): Int = motion.exitFadeMs

/**
 * How far along its flight back the cover is when its shadow is gone. The shadow stays at the card's place while the
 * cover leaves it, so it must be gone before the two visibly part: a fade over the page's exit fade left a half-strong
 * smudge where the card had been while the cover was already well on its way (VM AVD, predictive Back held at 1/6).
 */
internal const val COVER_SHADOW_GONE_AT = 0.12f

/** The shadow's alpha factor while the page leaves, from the cover's flight progress (0 at the card, 1 at the tile). */
internal fun coverShadowLeaving(flight: Float): Float = (1f - flight / COVER_SHADOW_GONE_AT).coerceIn(0f, 1f)

/**
 * The shadow's alpha factor, 0 to 1, for the page whose enter/exit [transition] this is. Read it in the draw phase (the
 * shadow's block), so a fade redraws and never recomposes.
 *
 * - **Entering**: 0 for the whole transition (the cover flies), then a [coverShadowFadeMs] fade once it has settled.
 *   Kept outside [transition], so the page's transition does not last any longer for it.
 * - **Leaving**: the cover's flight progress as a [transition] animation on the flight's own time and curve
 *   ([coverSlideTiming]), through [coverShadowLeaving]: gone by [COVER_SHADOW_GONE_AT] of the flight. A predictive Back
 *   seeks this transition with the gesture, so the shadow fades with the finger as the cover starts to move and
 *   returns when the gesture is cancelled (the transition seeks back to Visible).
 */
@Composable
internal fun rememberCoverShadowFade(transition: Transition<EnterExitState>, motion: PageMotion): () -> Float {
	val fadeMs = coverShadowFadeMs(motion)
	val landed = remember(transition) {
		Animatable(if (coverShadowAtRest(transition.currentState, transition.targetState)) 1f else 0f)
	}
	LaunchedEffect(transition) {
		if (landed.value < 1f) {
			snapshotFlow { coverShadowAtRest(transition.currentState, transition.targetState) }.first { it }
			landed.animateTo(1f, tween(fadeMs, easing = LinearEasing))
		}
	}
	val flightTiming = coverSlideTiming(motion)
	val flight = transition.animateFloat(
		transitionSpec = { tween(flightTiming.durationMs, easing = flightTiming.easing.curve()) },
		label = "coverShadowFlight"
	) { state -> if (state == EnterExitState.PostExit) 1f else 0f }
	return remember(landed, flight) { { landed.value * coverShadowLeaving(flight.value) } }
}
