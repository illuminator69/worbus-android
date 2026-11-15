package app.worbus.ui.screens.collection.components

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
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
 * The shadow's alpha factor, 0 to 1, from the page's enter/exit state ([current] → [target]), whether the cover's shared
 * element has a match ([matchFound]: a tile it flies from or to), whether this page's entry was such a flight ([flown]),
 * the landing fade ([landed], 0 to 1) and the cover's flight progress back ([flight], 0 to 1).
 *
 * Review Minor 2 (2026-11-15): only a FLIGHT holds the shadow back. A page that enters or leaves with no match (a pop back
 * to an album page from its artist, lyrics or Settings; no tile to fly from) slides its card in or out with its shadow
 * on from the first frame to the last, as before B-447.
 */
internal fun coverShadowFactor(
	current: EnterExitState,
	target: EnterExitState,
	matchFound: Boolean,
	flown: Boolean,
	landed: Float,
	flight: Float,
): Float {
	val entering = target == EnterExitState.Visible && !coverShadowAtRest(current, target)
	val arrived = when {
		flown -> landed
		entering && matchFound -> 0f
		else -> 1f
	}
	val leaving = if (target == EnterExitState.PostExit && matchFound) coverShadowLeaving(flight) else 1f
	return arrived * leaving
}

/**
 * The shadow's alpha factor, 0 to 1, for the page whose enter/exit [transition] this is ([coverShadowFactor]).
 * [matchFound] is the cover's `SharedContentState.isMatchFound`. Read it in the draw phase (the shadow's layer), so a fade
 * redraws and never recomposes.
 *
 * - **Entering on a flight**: 0 while the cover flies, then a [coverShadowFadeMs] fade once the page has settled. Kept
 *   outside [transition], so the page's transition does not last any longer for it.
 * - **Leaving on a flight**: the cover's flight progress as a [transition] animation on the flight's own time and curve
 *   ([coverSlideTiming]), through [coverShadowLeaving]: gone by [COVER_SHADOW_GONE_AT] of the flight. A
 *   predictive Back seeks this transition with the gesture, so the shadow fades with the finger as the cover starts to
 *   move and returns when the gesture is cancelled (the transition seeks back to Visible).
 * - **No flight** (no match): the shadow stays.
 */
@Composable
internal fun rememberCoverShadowFade(transition: Transition<EnterExitState>, motion: PageMotion, matchFound: () -> Boolean): () -> Float {
	val fadeMs = coverShadowFadeMs(motion)
	val matchFoundNow by rememberUpdatedState(matchFound)
	val enteredAtRest = remember(transition) { coverShadowAtRest(transition.currentState, transition.targetState) }
	val landed = remember(transition) { Animatable(if (enteredAtRest) 1f else 0f) }
	val flown = remember(transition) { mutableStateOf(false) }
	LaunchedEffect(transition) {
		if (enteredAtRest) return@LaunchedEffect
		// Did the cover fly in? Known once the shared element finds its match, which a flight does at its start; a
		// page that settles first had none.
		val flew = snapshotFlow { matchFoundNow() to coverShadowAtRest(transition.currentState, transition.targetState) }
			.first { (match, atRest) -> match || atRest }.first
		if (!flew) return@LaunchedEffect
		flown.value = true
		snapshotFlow { coverShadowAtRest(transition.currentState, transition.targetState) }.first { it }
		landed.animateTo(1f, tween(fadeMs, easing = LinearEasing))
	}
	val flightTiming = coverSlideTiming(motion)
	val flight = transition.animateFloat(
		transitionSpec = { tween(flightTiming.durationMs, easing = flightTiming.easing.curve()) },
		label = "coverShadowFlight"
	) { state -> if (state == EnterExitState.PostExit) 1f else 0f }
	return remember(landed, flight, flown) {
		{
			coverShadowFactor(
				transition.currentState, transition.targetState, matchFoundNow(), flown.value, landed.value, flight.value
			)
		}
	}
}
