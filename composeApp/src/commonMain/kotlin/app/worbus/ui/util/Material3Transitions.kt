package app.worbus.ui.util

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import app.worbus.domain.manager.PreferenceManager
import app.worbus.di.LocalNavStack
import app.worbus.ui.navigation.LocalTabTapMotion

// EmphasizedEasing, EmphasizedAccelerateEasing and EmphasizedDecelerateEasing are in Easings.kt (motion lane,
// 2026-11-14; review L8: no android.graphics.Path is built when this file's class loads).

// navi-connect I-042: a sheet's opening and closing times follow the animation style
// (PageMotion.kt); the vals are the Expressive/Standard timing, unchanged.
fun sheetShowMotionSpec(motion: PageMotion) = tween<Float>(
	durationMillis = motion.sheetShowMs,
	easing = EmphasizedDecelerateEasing
)
fun sheetHideMotionSpec(motion: PageMotion) = tween<Float>(
	durationMillis = motion.sheetHideMs,
	easing = EmphasizedAccelerateEasing
)

/**
 * navi-connect I-042: a cover's shared-element flight between a list and its page, timed by the
 * animation style so it lands with the page. B-404 (the owner, 2026-11-13): the album slide's time and
 * curve ([coverSlideTiming]); it was 500 ms (Quick 350) on EmphasizedDecelerate, so the cover still
 * flew after the page had landed.
 */
@Composable
fun rememberCoverBoundsTransform(): BoundsTransform {
	val motion = koinInject<PreferenceManager>().pageMotion()
	// Review L3: a bar tap's flights take the tap's fade-through time (sharedFlightTiming), read when the flight starts.
	val tabTaps = LocalTabTapMotion.current
	val stack = LocalNavStack.current
	return remember(motion, tabTaps, stack) {
		BoundsTransform { _, _ ->
			val timing = sharedFlightTiming(motion, tabTap = tabTaps.isTabTap(stack))
			tween(
				durationMillis = timing.durationMs,
				easing = timing.easing.curve()
			)
		}
	}
}

/**
 * Suspends for as long as a bar tap's page change runs: the tab fade-through's whole time ([tabFadeWindows]: the old
 * page out, then the new one in; the cover's flight and the bar's bounds take the same time on a tap, review L3), on
 * the animation clock, so the system's animator duration scale stretches it as it stretches the transition, then two
 * frames more. App.kt ends the mark (`TabTapMotion.settled`) after it.
 */
suspend fun awaitTabTapTransition(motion: PageMotion) {
	animate(0f, 1f, animationSpec = tween(durationMillis = tabFadeWindows(motion).enterEndMs, easing = LinearEasing)) { _, _ -> }
	repeat(2) { withFrameNanos { } }
}

/** The curve a [PageEasing] names. */
fun PageEasing.curve(): Easing = when (this) {
	PageEasing.Emphasized -> EmphasizedEasing
	PageEasing.EmphasizedDecelerate -> EmphasizedDecelerateEasing
	PageEasing.EaseOutQuart -> androidx.compose.animation.core.EaseOutQuart
}

object Material3Transitions {
	// navi-connect I-042: the four specs are built from a PageMotion (PageMotion.kt), so the
	// Quick style can shorten them. The vals are the Expressive/Standard timing, unchanged.
	fun enter(motion: PageMotion): (Density) -> EnterTransition = { density ->
		pageEnter(motion, density, sign = 1)
	}

	fun popEnter(motion: PageMotion): (Density) -> EnterTransition = { density ->
		pageEnter(motion, density, sign = -1)
	}

	fun exit(motion: PageMotion): (Density) -> ExitTransition = { density ->
		pageExit(motion, density, sign = -1)
	}

	fun popExit(motion: PageMotion): (Density) -> ExitTransition = { density ->
		pageExit(motion, density, sign = 1)
	}

	/**
	 * Every page change NavDisplay runs, push or pop, and the predictive drag (which drives the same
	 * transform by its progress, B-378's one back animation): [change] picks the motion
	 * ([pageChangeOf]), [pop] its direction.
	 */
	fun page(change: PageChange, pop: Boolean, motion: PageMotion, density: Density): ContentTransform = when (change) {
		PageChange.SharedAxis ->
			if (pop) popEnter(motion)(density) togetherWith popExit(motion)(density)
			else enter(motion)(density) togetherWith exit(motion)(density)
		// Settings' regular slide (B-403) on its own curve; the album slide (B-404) is its own timing.
		PageChange.Slide -> slide(motion.slideMs, motion.slideEasing.curve(), sign = if (pop) -1 else 1)
		PageChange.CoverSlide -> coverSlideTiming(motion).let { slide(it.durationMs, it.easing.curve(), sign = if (pop) -1 else 1) }
	}

	/**
	 * B-403: the regular slide. The new page comes in from one edge as the old one leaves by the other,
	 * both the whole width and on one curve, so they stay side by side: no fade, nothing drawn over
	 * the other page, and the old page moves from the first frame. [sign] 1 is a push (in from the
	 * right), -1 a pop (in from the left). Upstream's predictive-back slide, as one animation.
	 */
	private fun slide(durationMs: Int, easing: Easing, sign: Int): ContentTransform =
		slideInHorizontally(animationSpec = tween(durationMillis = durationMs, easing = easing)) { sign * it } togetherWith
			slideOutHorizontally(animationSpec = tween(durationMillis = durationMs, easing = easing)) { -sign * it }

	/**
	 * A root-tab switch (Ruling 7, 2026-11-10): a fade-through, no slide. The old tab fades out, then
	 * the new one fades in ([tabFadeWindows]); the two were faded at once, their text drawn over each
	 * other (B-378's overlap on a tab switch). B-403 (the owner, 2026-11-13): it took a page push's
	 * 500 ms, too long for a tab; Material's fade-through ([PageMotion.tabExitFadeMs] then
	 * [PageMotion.tabEnterFadeMs]: I-075 Expressive 100 + 230, Quick 90 + 210).
	 */
	fun tabEnter(motion: PageMotion): EnterTransition {
		val w = tabFadeWindows(motion)
		return fadeIn(animationSpec = tween(durationMillis = w.enterEndMs - w.enterStartMs, delayMillis = w.enterStartMs))
	}

	fun tabExit(motion: PageMotion): ExitTransition {
		val w = tabFadeWindows(motion)
		return fadeOut(
			animationSpec = tween(
				durationMillis = w.exitEndMs - w.exitStartMs,
				delayMillis = w.exitStartMs,
				easing = EmphasizedAccelerateEasing
			)
		)
	}

	/**
	 * The whole fade-through, old out then new in ([tabFadeWindows]). Every root tab change runs it, a bar tap
	 * that leaves a pushed page included (B-434, `TabTapMotion`), and so does every chip's content swap
	 * (`ContentSwap.kt`).
	 */
	fun tab(motion: PageMotion): ContentTransform = ContentTransform(tabEnter(motion), tabExit(motion))

	/**
	 * B-433 (motion lane, 2026-11-14): tabs inside a page (the queue's Up next and Related) move as pages do,
	 * the slide and fade ([PageChange.SharedAxis]'s times and staggered fades: the old tab out, then the new one
	 * in, never both), sliding a quarter of the width toward the tab being opened, as they did. They slid and
	 * faded at once on the motion scheme's springs, both lists half drawn in the same place. [forward] is a tab
	 * further right.
	 */
	fun inPageAxis(motion: PageMotion, forward: Boolean): ContentTransform {
		val sign = if (forward) 1 else -1
		val easing = motion.enterEasing.curve()
		val enter = fadeIn(tween(durationMillis = motion.enterFadeMs, delayMillis = motion.enterFadeDelayMs, easing = easing)) +
			slideInHorizontally(tween(durationMillis = motion.enterSlideMs, easing = easing)) { sign * it / 4 }
		val exit = fadeOut(tween(durationMillis = motion.exitFadeMs, easing = EmphasizedAccelerateEasing)) +
			slideOutHorizontally(tween(durationMillis = motion.exitSlideMs, easing = easing)) { -sign * it / 4 }
		return enter togetherWith exit
	}

	private fun pageEnter(motion: PageMotion, density: Density, sign: Int): EnterTransition {
		val easing = motion.enterEasing.curve()
		// B-378: the fade waits for the outgoing page's to end (pageFadeWindows); the slide does not.
		return fadeIn(
			animationSpec = tween(durationMillis = motion.enterFadeMs, delayMillis = motion.enterFadeDelayMs, easing = easing)
		) +
			slideInHorizontally(
				animationSpec = tween(durationMillis = motion.enterSlideMs, easing = easing)
			) {
				val travel = motion.enterSlideDp?.let { dp -> with(density) { dp.dp.roundToPx() } }
					?: (it / 2)
				sign * travel
			}
	}

	private fun pageExit(motion: PageMotion, density: Density, sign: Int): ExitTransition =
		fadeOut(
			animationSpec = tween(
				durationMillis = motion.exitFadeMs,
				easing = EmphasizedAccelerateEasing
			)
		) +
			// B-378: the outgoing page keeps moving for the whole change, with the incoming one.
			slideOutHorizontally(
				animationSpec = tween(
					durationMillis = motion.exitSlideMs,
					easing = motion.enterEasing.curve()
				)
			) {
				sign * with(density) { motion.exitSlideDp.dp.roundToPx() }
			}
}
