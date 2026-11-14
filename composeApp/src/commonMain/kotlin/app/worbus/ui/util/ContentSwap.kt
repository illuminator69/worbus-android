package app.worbus.ui.util

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
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
 *   that stay slide and the new ones fade in. A chip under a page's own header resets the scroll in the same frame
 *   (I-039, CLAUDE.md §7: Saved queues, Search); where the chips are an item of the list itself (Fresh, Charts),
 *   they are on screen when tapped, so the rows that come in arrive below them and there is nothing to reset.
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
		transitionSpec = { contentSwapTransform(prefs.pageMotion()) using SizeTransform(clip = false) { _, _ -> snap() } },
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
 * [content] (a lazy list whose chips filter its rows) with its items' `animatedItem` motion set to the content
 * swap's ([swapItemMotionOf]). A row an action removes moves the same way: fade out, then the gap closes.
 */
@Composable
fun SwapRows(content: @Composable () -> Unit) {
	val motion = koinInject<PreferenceManager>().pageMotion()
	val items = remember(motion) { swapItemMotionOf(motion) }
	CompositionLocalProvider(LocalItemMotion provides items, content = content)
}
