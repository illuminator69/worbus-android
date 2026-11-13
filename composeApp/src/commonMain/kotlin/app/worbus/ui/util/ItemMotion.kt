package app.worbus.ui.util

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

/*
 * The animation speed setting (I-075) reaches the lazy lists' item motion (follow-up 2026-11-14, the owner: list
 * item placement follows the slider "only if it doesn't break the UI").
 *
 * `Modifier.animateItem()` animates an item into its new place (placement) and fades one in or out as it is
 * added or removed. Its defaults are Compose's own springs (`LazyItemScope.animateItem`: all three
 * `spring(stiffness = Spring.StiffnessMediumLow)`, the placement with `IntOffset.VisibilityThreshold`), not the
 * motion scheme's, so they did not follow the speed: Home's Continue listening fold moved the page under it at
 * the 1× pace whatever the slider said (measured 283-299 ms at 0.5×, 1× and 2×).
 *
 * Every list site calls [animatedItem] instead: the same three springs through [atSpeed] (stiffness × speed²,
 * damping kept, so the same shape in 1 / speed of the time, as the motion scheme's springs). At 1× it is
 * exactly Compose's default. A site that animates no fade (or no placement) says so with the flags, as it
 * passed `null` before.
 */

/** The three specs an item moves by, at one speed. */
@Immutable
class ItemMotion internal constructor(
	val fadeIn: FiniteAnimationSpec<Float>,
	val placement: FiniteAnimationSpec<IntOffset>,
	val fadeOut: FiniteAnimationSpec<Float>
)

/** Compose's own `animateItem` defaults. */
private val DefaultItemMotion = ItemMotion(
	fadeIn = spring(stiffness = Spring.StiffnessMediumLow),
	placement = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold),
	fadeOut = spring(stiffness = Spring.StiffnessMediumLow)
)

/** Compose's `animateItem` defaults at [speed] ([atSpeed]); 1× is the defaults themselves. */
fun itemMotionOf(speed: Float): ItemMotion {
	val s = motionSpeedOf(speed)
	if (s == 1f) return DefaultItemMotion
	return ItemMotion(
		fadeIn = DefaultItemMotion.fadeIn.atSpeed(s),
		placement = DefaultItemMotion.placement.atSpeed(s),
		fadeOut = DefaultItemMotion.fadeOut.atSpeed(s)
	)
}

/**
 * The item motion at the speed the settings ask for. `NavicTheme` provides it (beside the motion scheme it
 * scales the same way); outside one it is 1×.
 */
val LocalItemMotion = compositionLocalOf { DefaultItemMotion }

private fun <T> FiniteAnimationSpec<T>.orNull(on: Boolean): FiniteAnimationSpec<T>? = if (on) this else null

/**
 * `Modifier.animateItem()` at the animation speed ([LocalItemMotion]). [fadeIn] / [placement] / [fadeOut] false
 * is the site's old `null` for that spec.
 */
@Composable
fun LazyItemScope.animatedItem(fadeIn: Boolean = true, placement: Boolean = true, fadeOut: Boolean = true): Modifier {
	val m = LocalItemMotion.current
	return Modifier.animateItem(m.fadeIn.orNull(fadeIn), m.placement.orNull(placement), m.fadeOut.orNull(fadeOut))
}

/** [animatedItem] for a lazy grid's item. */
@Composable
fun LazyGridItemScope.animatedItem(fadeIn: Boolean = true, placement: Boolean = true, fadeOut: Boolean = true): Modifier {
	val m = LocalItemMotion.current
	return Modifier.animateItem(m.fadeIn.orNull(fadeIn), m.placement.orNull(placement), m.fadeOut.orNull(fadeOut))
}

/** [animatedItem] for a staggered grid's item. */
@Composable
fun LazyStaggeredGridItemScope.animatedItem(fadeIn: Boolean = true, placement: Boolean = true, fadeOut: Boolean = true): Modifier {
	val m = LocalItemMotion.current
	return Modifier.animateItem(m.fadeIn.orNull(fadeIn), m.placement.orNull(placement), m.fadeOut.orNull(fadeOut))
}
