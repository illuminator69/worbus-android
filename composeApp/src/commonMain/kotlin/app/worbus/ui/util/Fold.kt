package app.worbus.ui.util

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import org.koin.compose.koinInject
import app.worbus.domain.manager.PreferenceManager

/*
 * navi-connect P1: a block that appears or goes by rolling its height out, not by popping. The
 * siblings below it follow the height, so nothing jumps. Home's Continue listening pill and box use
 * it, and so does the avatar menu's groups; P2 to P4's source boxes will too.
 *
 * Its motion follows the animation style (I-042), as the rest of the app does: the default spatial and
 * effects springs of the motion scheme `NavicTheme` sets for the style, so a fold moves as the components
 * around it do. B-436 (motion lane, 2026-11-14): under every style, Quick included, at Quick's pace
 * (`PageMotion.foldPace`, stiffness × pace²); Quick's folds were fixed tweens beside its expressive springs.
 * Both follow the animation speed (I-075 review L4): the scheme's springs are stiffened by its square
 * (`MotionSpeed.kt`), so a fold is twice as quick at 2× under every preset. It was a fixed 300 ms tween while
 * the pill sat among components that follow the style, which is what made it look as if it did not belong.
 * A spring takes the same time over any distance, so a block that holds a list needs no time of its own (the
 * tweens had a longer one, `tall`).
 */

/** What a fold is moved by, and whatever else moves with it (the capsule's corners, a chevron). */
@Immutable
class FoldMotion internal constructor(private val pace: Float, private val scheme: MotionScheme) {
	/** For the height, a size, a corner, a turn: where something goes. */
	fun <T> spatial(): FiniteAnimationSpec<T> = scheme.defaultSpatialSpec<T>().atPace(pace)

	/** For a fade: how it looks while it goes, a little quicker than the height. */
	fun <T> effects(): FiniteAnimationSpec<T> = scheme.defaultEffectsSpec<T>().atPace(pace)
}

@Composable
fun rememberFoldMotion(): FoldMotion {
	// Review L8: the pace is read once per style, not on every composition of every row's fold.
	val prefs = koinInject<PreferenceManager>()
	val style = prefs.animationStyle
	val pace = remember(style) { pageMotionOf(style).foldPace }
	val scheme = MaterialTheme.motionScheme
	return remember(pace, scheme) { FoldMotion(pace, scheme) }
}

@Composable
fun Fold(
	visible: Boolean,
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit
) {
	val motion = rememberFoldMotion()
	AnimatedVisibility(
		visible = visible,
		modifier = modifier,
		enter = expandVertically(motion.spatial<IntSize>()) + fadeIn(motion.effects()),
		exit = shrinkVertically(motion.spatial<IntSize>()) + fadeOut(motion.effects())
	) {
		content()
	}
}
