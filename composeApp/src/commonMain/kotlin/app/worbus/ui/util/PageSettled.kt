package app.worbus.ui.util

import androidx.compose.animation.EnterExitState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigation3.ui.LocalNavAnimatedContentScope

/**
 * B-405: runs [onSettled] once this page's own transition in has ended (NavDisplay's scope for the
 * entry is visible and no longer animating), so work that would cost a frame lands after the slide,
 * not in it.
 */
@Composable
fun OnPageSettled(onSettled: () -> Unit) {
	val transition = LocalNavAnimatedContentScope.current.transition
	val settled = pageTransitionSettled(transition.isRunning, transition.currentState == EnterExitState.Visible)
	val latest by rememberUpdatedState(onSettled)
	LaunchedEffect(settled) { if (settled) latest() }
}

/** A page has settled when its transition has stopped with the page shown. */
internal fun pageTransitionSettled(running: Boolean, visible: Boolean): Boolean = !running && visible
