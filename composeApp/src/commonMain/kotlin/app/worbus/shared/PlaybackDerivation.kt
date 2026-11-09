package app.worbus.shared

import app.worbus.ui.core.PlayerUiState

/**
 * What the app's MediaController says about the player at one moment, read on the controller's
 * thread by `AndroidMediaPlayerViewModel.updatePlaybackState`.
 */
data class ControllerView(
	/** `currentMediaItemIndex`. */
	val index: Int,
	/** `currentMediaItem?.mediaId`: a song id for the local player's items. */
	val mediaId: String?,
	val playWhenReady: Boolean,
	val shuffle: Boolean,
	val repeatMode: Int,
	/**
	 * The session's player is `RemoteSessionPlayer` (another device plays), so every value above is
	 * the REMOTE session's, not the local player's.
	 */
	val showsRemote: Boolean = false
)

/**
 * The local player state [view] leads to.
 *
 * The playing song is the queue's entry at the controller's index **only when that entry is the
 * song the controller is on** (B-155). The controller and `_uiState.queue` change at different
 * moments: media3 runs the listeners inline inside `setMediaItems`, before the caller has replaced
 * the queue, and a session update can arrive while the app's queue is being edited. An index into
 * the other list then named a song nothing was playing (the old queue's song at the new start
 * index; the first song during a restore), and every reader followed it: the cover, the wash, the
 * gain, the hub report. While the two disagree the index and the song are left as they were; the
 * caller derives again once its queue is in place. The play flags are the controller's whatever
 * the queue says, except while it shows the remote session ([ControllerView.showsRemote]).
 */
fun derivePlayback(state: PlayerUiState, view: ControllerView): PlayerUiState {
	// B-148: another device plays and the controller shows the remote session. None of it is the
	// local player's: its queue, index and shuffle stay as they were, and it is paused (the service
	// pauses the local player before it swaps the facade in, and nothing plays it while the facade
	// is the session's player). Written as paused, not left as it was: the controller may never
	// have shown that pause before the facade's state, and a local "playing" read while another
	// device plays is what made HubManager.adoptIfNoLiveReceiver re-claim the session when that
	// device dropped.
	if (view.showsRemote) return state.copy(isPaused = true)
	val agrees = view.mediaId != null && state.queue.getOrNull(view.index)?.id == view.mediaId
	return state.copy(
		currentIndex = if (agrees) view.index else state.currentIndex,
		currentSong = if (agrees) state.queue[view.index] else state.currentSong,
		isPaused = !view.playWhenReady,
		isShuffleEnabled = view.shuffle,
		repeatMode = view.repeatMode
	)
}

/**
 * What the controller and the app's queue disagree on, in words for the log, or null when the queue
 * holds the controller's song at its index (or the facade is shown, whose items are not the queue's).
 *
 * [derivePlayback] keeps the last song while they disagree, which is right for the moment inside a
 * queue edit; outside one (`updatePlaybackState` runs only then) a disagreement means some path
 * changed the controller's items without the queue, and the player would sit on the old song with
 * nothing said (B-155 review). The caller logs it once per distinct disagreement.
 */
fun playbackDisagreement(state: PlayerUiState, view: ControllerView): String? {
	if (view.showsRemote) return null
	val entry = state.queue.getOrNull(view.index)
	if (view.mediaId != null && entry?.id == view.mediaId) return null
	return "controller at ${view.index} on ${view.mediaId ?: "no item"}, " +
		"the queue (${state.queue.size}) holds ${entry?.id ?: "nothing"} there"
}
