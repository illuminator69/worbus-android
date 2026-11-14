package app.worbus.ui.util

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import org.koin.compose.koinInject
import app.worbus.domain.manager.PreferenceManager

/*
 * The motion lane (option (c), the owner's ruling of 2026-11-14; B-431, B-432, B-435): ONE way a chip or a
 * segmented switch changes what a page shows. It was six: a 220 ms cross-fade with both lists drawn at once
 * (Library), Material's fade-through with a scale that ignored the speed (Source history), per-row fades with and
 * without a scroll reset (Saved queues, Search, Fresh), and on Library Matches nothing coherent at all, the To fix
 * rows fading out over the next section's rows (ASSESSMENT-android-motion-2026-11-14 §2).
 *
 * The rule: **the old content goes, then the new comes; the two are never drawn at once.** It is the root tabs'
 * fade-through ([Material3Transitions.tab]: Expressive 100 ms out, then 230 in; Quick 90 + 210), so it follows the
 * style and the speed slider through [PageMotion]. No scale, no slide: a chip changes the page in place.
 *
 * Two shapes, by what the chips switch between:
 *
 * - **Sections that are different lists** ([ContentSwap]): Library (each chip is another page's body), Library
 *   Matches, Source history. The whole body fades through. A body is composed fresh, so a list that keeps no state
 *   of its own opens at its top (the scroll reset) and no row animates across sections: the old list leaves whole,
 *   it is never "every row removed". [keepState] keeps each section's saved state across a switch, for bodies that
 *   keep their own place (Library: Recents' arrival gate and each chip's list, which P5 ruled keeps its scroll);
 *   `AnimatedContent` alone drops a leaving content's saved state, which is why Recents' tiles arrived again on the
 *   way back (B-432).
 * - **Chips that filter one list** ([SwapRows]): Saved queues, Search, Fresh, Charts and Editor's picks. Rows that
 *   stay are the same rows, and the owner approved them sliding to their new places (I-039, B-161), so the list
 *   is kept and its items fade through instead ([swapItemMotionOf]): rows that leave fade out first, then the rows
 *   that stay slide and the new ones fade in. **Only a chip's change does** ([RowSwap], review L7): a search's
 *   answer, a hub update, a row an action removes keep the list's ordinary item motion. A chip that sits above
 *   the list resets the scroll in the same frame (I-039, B-161, CLAUDE.md §7: Saved queues, Search, and Fresh's
 *   scope chips in its header, review M2), through [RowSwap.chipTapped]; Fresh's type chips, the grid's first
 *   item, go through the same call. Charts' genre chips are the grid's first item, on screen when tapped, and the
 *   new chart lands later: they arm the swap without a reset.
 *
 * A source guard (`ContentSwapGuardTest`) fails on a chip page that switches with an `AnimatedContent` or
 * `Crossfade` of its own.
 */

/** The fade-through every content swap runs: [Material3Transitions.tab]'s, the root tabs'. */
fun contentSwapTransform(motion: PageMotion): ContentTransform = Material3Transitions.tab(motion)

/** When the old content fades and when the new one does, in ms from the tap (never overlapping). */
fun contentSwapWindows(motion: PageMotion): PageFadeWindows = tabFadeWindows(motion)

/**
 * The page's body for [target], swapped by fading through ([contentSwapTransform]). [key] is what makes two
 * targets the same section (a count changing is not a switch); [keepState] keeps each section's saved state
 * (`rememberSaveable`, a list state) for the next time it is shown.
 */
@Composable
fun <S> ContentSwap(
	target: S,
	modifier: Modifier = Modifier,
	key: (S) -> Any? = { it },
	keepState: Boolean = false,
	// Review L6: whether the change from the first to the second runs the swap; false shows the new one at once.
	animate: (S, S) -> Boolean = { _, _ -> true },
	contentAlignment: Alignment = Alignment.TopStart,
	label: String = "contentSwap",
	content: @Composable (S) -> Unit
) {
	val prefs = koinInject<PreferenceManager>()
	val holder = if (keepState) rememberSaveableStateHolder() else null
	AnimatedContent(
		targetState = target,
		modifier = modifier,
		// The body is as large as the page: a size change between two sections is not animated.
		transitionSpec = {
			val swap = if (animate(initialState, targetState)) contentSwapTransform(prefs.pageMotion())
			else EnterTransition.None togetherWith ExitTransition.None
			swap using SizeTransform(clip = false) { _, _ -> snap() }
		},
		contentAlignment = contentAlignment,
		contentKey = key,
		label = label
	) { shown ->
		if (holder != null) holder.SaveableStateProvider(stateKeyOf(key(shown))) { content(shown) }
		else content(shown)
	}
}

/** A saved-state key for a section: a string, which every platform's saved state can hold. */
internal fun stateKeyOf(key: Any?): String = "contentSwap:" + (key?.toString() ?: "none")

/**
 * The item motion of a list whose chips filter its rows ([SwapRows]): the content swap's fade-through, row by row.
 * A leaving row fades out over the old content's window; a row that stays waits for it, then slides to its new
 * place over the new content's window, as a new row fades in; so a leaving row is never drawn under a moving or an
 * arriving one. Its times are the page motion's, so it follows the style and the speed.
 */
fun swapItemMotionOf(motion: PageMotion): ItemMotion {
	val w = contentSwapWindows(motion)
	return ItemMotion(
		fadeIn = tween(durationMillis = w.enterEndMs - w.enterStartMs, delayMillis = w.enterStartMs),
		placement = tween<IntOffset>(
			durationMillis = w.enterEndMs - w.enterStartMs,
			delayMillis = w.enterStartMs,
			easing = EmphasizedDecelerateEasing
		),
		fadeOut = tween(durationMillis = w.exitEndMs - w.exitStartMs, delayMillis = w.exitStartMs, easing = EmphasizedAccelerateEasing)
	)
}

/**
 * Which change of a [SwapRows] list is a chip's (motion lane review L7, 2026-11-14). The swap's item motion used to
 * be the list's for good, so every change was held back by the swap's out window and staged: each new answer to a
 * Search query, every saved-queues update from the hub, a row an action removed, nested rows. Now a chip's tap arms
 * it ([chipTapped]); the next change of the rows takes the swap's motion for the swap's time; every other change
 * keeps the list's ordinary motion. The next change, not the tap's frame: Charts' new chart lands seconds after its
 * genre chip. A chip whose answer is the rows already shown leaves it armed until the rows next change.
 */
@Stable
class RowSwap {
	internal var armed by mutableStateOf(false)
	internal var swapping by mutableStateOf(false)
	internal var generation by mutableIntStateOf(0)

	/** A chip changed what the list shows: its next change of rows fades through. */
	fun chipTapped() {
		armed = true
	}

	/** Whether rows composed now take the swap's motion; [changed]: they differ from the rows composed before. */
	internal fun swapMotion(changed: Boolean): Boolean = swapping || (armed && changed)

	/** A composition showed rows; when they [changed] while armed, the swap runs ([settled] ends it). */
	internal fun rowsShown(changed: Boolean) {
		if (changed && armed) {
			armed = false
			swapping = true
			generation++
		}
	}

	/** The swap [generation] started has had its time; a later one keeps running. */
	internal fun settled(generation: Int) {
		if (generation == this.generation) swapping = false
	}
}

@Composable
fun rememberRowSwap(): RowSwap = remember { RowSwap() }

/**
 * A chip above a list (I-039, B-161; CLAUDE.md §7): the list starts at its top in the same frame, and its rows fade
 * through ([RowSwap]). Without the reset a list keeps its first visible KEY in place, so the rows that came in above
 * it did so out of sight and the chip looked inert.
 */
fun RowSwap.chipTapped(list: LazyListState) {
	list.requestScrollToItem(0)
	chipTapped()
}

/** [chipTapped] for a lazy grid. */
fun RowSwap.chipTapped(grid: LazyGridState) {
	grid.requestScrollToItem(0)
	chipTapped()
}

/**
 * [content] (a lazy list whose chips filter its [rows]) with its items' `animatedItem` motion set to the content
 * swap's ([swapItemMotionOf]) for the change a chip made ([swap]), and the list's ordinary motion otherwise.
 * [rows] is what the list shows (compared by equality).
 */
@Composable
fun SwapRows(swap: RowSwap, rows: Any?, content: @Composable () -> Unit) {
	val motion = koinInject<PreferenceManager>().pageMotion()
	val swapItems = remember(motion) { swapItemMotionOf(motion) }
	val ordinary = LocalItemMotion.current
	val shown = remember { ShownRows(rows) }
	val changed = shown.rows != rows
	val useSwap = swap.swapMotion(changed)
	SideEffect {
		if (changed) {
			shown.rows = rows
			swap.rowsShown(true)
		}
	}
	val generation = swap.generation
	LaunchedEffect(swap, generation) {
		if (!swap.swapping) return@LaunchedEffect
		// The swap's whole time on the animation clock (the system's animator scale stretches both alike).
		animate(0f, 1f, animationSpec = tween(durationMillis = contentSwapWindows(motion).enterEndMs, easing = LinearEasing)) { _, _ -> }
		withFrameNanos { }
		swap.settled(generation)
	}
	CompositionLocalProvider(LocalItemMotion provides if (useSwap) swapItems else ordinary, content = content)
}

/** The rows a [SwapRows] composed last (a plain holder: written after a composition is applied). */
private class ShownRows(var rows: Any?)
