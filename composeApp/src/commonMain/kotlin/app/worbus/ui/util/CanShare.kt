package app.worbus.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.worbus.domain.manager.SessionManager
import app.worbus.domain.manager.canShare

/**
 * Whether the session's user may share (B-308), read as state: the Share rows appear when the user
 * loads, which a plain `canUserShare()` in composition never noticed (its answer was not state, so a
 * sheet opened before the user loaded kept no Share row). Asks for the user once, when first composed.
 */
@Composable
fun rememberCanShare(sessionManager: SessionManager): Boolean {
	val user by sessionManager.currentUserState.collectAsState()
	LaunchedEffect(sessionManager) { sessionManager.getCachedUser() }
	return user?.canShare() == true
}
