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

/** The local player state [view] leads to. */
fun derivePlayback(state: PlayerUiState, view: ControllerView): PlayerUiState =
	state.copy(
		currentIndex = view.index,
		currentSong = state.queue.getOrNull(view.index),
		isPaused = !view.playWhenReady,
		isShuffleEnabled = view.shuffle,
		repeatMode = view.repeatMode
	)
