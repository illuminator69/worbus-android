package app.worbus.ui.util

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import org.koin.compose.koinInject
import kotlin.math.roundToInt
import app.worbus.domain.manager.PreferenceManager

/*
 * I-075 review L4 (second re-walk): the animation speed setting reaches every motion, not only the page table
 * ([PageMotion.atSpeed]).
 *
 * - **Fixed times** (a tile's arrival and its stagger, the colour wash on a page, a sheet and the mini player's
 *   ring, a cover's fade over its placeholder, the Library chips' cross-fade, the bottom bar's shadow fade,
 *   the alphabet scroller's fade) go through
 *   [motionMs] / [msAtSpeed]: divided by the speed, as every page time is.
 * - **Springs** (the motion scheme every Material component and every `Fold` under Expressive and Standard
 *   reads) go through [atSpeed] on the scheme `NavicTheme` provides: a spring's stiffness is multiplied by
 *   the speed squared and its damping ratio kept. A spring's time goes as 1 / sqrt(stiffness), so 2× settles
 *   in half the time and 0.5× in twice, with the same shape (the same overshoot, the same number of
 *   swings). So a fold follows the slider the same way under every preset: Quick's tween by its time,
 *   Expressive's and Standard's spring by its stiffness.
 * - **Not scaled**, on purpose: what runs for as long as something lasts rather than as a transition (the
 *   marquee, the playing waveform, the loading shimmer, the login progress, the mini player's ring rotation);
 *   the staged queue layer's settle after a drag (it continues the finger) and the play button's 100 ms press
 *   bounce. The system's animator scale still applies on top of all of it, as before.
 * - **The lazy lists' item motion** (`animateItem`: placement and the fades of an item added or removed) is
 *   Compose's own springs, not the scheme's; since the follow-up of 2026-11-14 every site goes through
 *   `animatedItem` (`ItemMotion.kt`), the same springs scaled the same way.
 * - **Compose's own defaults at a site** (B-435, motion lane 2026-11-14: the mini player appearing, the bottom
 *   bar's collapse on scroll, the sheet's content height, the share list's cross-fade, the add-to-playlist
 *   row's state) go through [atMotionSpeed]: the same spec as before at 1x, scaled as above.
 * - **Not scaled, and cannot be (B-435):** the tablet's and the landscape phone's two-pane motion (a detail pane
 *   opening beside its list). It is the adaptive library's own spring (`PaneMotionDefaults`), and
 *   `rememberListDetailSceneStrategy` (adaptive-navigation3 1.3.0-alpha06, read with `javap`) takes a scene key,
 *   a directive and adapt strategies, no pane motion, and draws the scaffold itself; a pane motion of ours would
 *   mean writing our own list-detail scene. Dialogs keep the platform's window animation (the system animator
 *   scale only), and a dialog's predictive back follows the finger.
 */

/** A fixed time at [speed]: [ms] divided by the speed (on the slider's stops); 1× is [ms] itself. */
fun msAtSpeed(ms: Int, speed: Float): Int {
	val s = motionSpeedOf(speed)
	return if (s == 1f) ms else (ms / s).roundToInt()
}

/** [ms] at the speed the settings ask for. Read in composition, so a change of speed recomposes the reader. */
@Composable
fun motionMs(ms: Int): Int = msAtSpeed(ms, koinInject<PreferenceManager>().motionSpeed)

/**
 * [this] at [speed]: a tween's duration and delay divided by it; a spring's stiffness multiplied by its square,
 * its damping ratio and visibility threshold kept. Anything else (a keyframes spec, a snap) as it is.
 */
fun <T> FiniteAnimationSpec<T>.atSpeed(speed: Float): FiniteAnimationSpec<T> = atPace(motionSpeedOf(speed))

/**
 * [this] at [pace], not rounded to the slider's stops ([atSpeed] is this on a stop): a tween's time and delay
 * divided by it, a spring's stiffness multiplied by its square. B-436: Quick's folds are the motion scheme's
 * spring at Quick's pace (`PageMotion.foldPace`).
 */
fun <T> FiniteAnimationSpec<T>.atPace(pace: Float): FiniteAnimationSpec<T> {
	if (pace == 1f || !pace.isFinite() || pace <= 0f) return this
	return when (this) {
		is SpringSpec<T> -> spring(dampingRatio, stiffness * pace * pace, visibilityThreshold)
		is TweenSpec<T> -> tween((durationMillis / pace).roundToInt(), (delay / pace).roundToInt(), easing)
		else -> this
	}
}

/** [this] at the speed the settings ask for ([atSpeed]). Read in composition, as [motionMs]. */
@Composable
fun <T> FiniteAnimationSpec<T>.atMotionSpeed(): FiniteAnimationSpec<T> = atSpeed(koinInject<PreferenceManager>().motionSpeed)

/** A motion scheme whose every spec is [base]'s at [speed] ([atSpeed]); 1× is [base] itself. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun MotionScheme.atSpeed(speed: Float): MotionScheme =
	if (motionSpeedOf(speed) == 1f) this else SpeedMotionScheme(this, motionSpeedOf(speed))

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private class SpeedMotionScheme(private val base: MotionScheme, private val speed: Float) : MotionScheme {
	override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = base.defaultSpatialSpec<T>().atSpeed(speed)
	override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = base.fastSpatialSpec<T>().atSpeed(speed)
	override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = base.slowSpatialSpec<T>().atSpeed(speed)
	override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = base.defaultEffectsSpec<T>().atSpeed(speed)
	override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = base.fastEffectsSpec<T>().atSpeed(speed)
	override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = base.slowEffectsSpec<T>().atSpeed(speed)
}
