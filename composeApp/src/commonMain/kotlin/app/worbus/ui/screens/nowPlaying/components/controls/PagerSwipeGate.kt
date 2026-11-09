package app.worbus.ui.screens.nowPlaying.components.controls

/**
 * Which settles of the cover pager are the user's swipe, and so change the track.
 *
 * The pager is also scrolled PROGRAMMATICALLY whenever the playing index changes (playback
 * advancing, locally or through the remote mirror). Acting on those settles caused an infinite
 * jump/skip loop when remote, so only a settle that follows a drag acts.
 *
 * [onFrame] is fed every (scrolling, settled page) the pager shows, with the playing index.
 */
class PagerSwipeGate(initialPage: Int) {
	private var userDragging = false
	private var lastSettled = initialPage

	/** A drag started on the pager. */
	fun onDragStart() {
		userDragging = true
	}

	/** The page to play, or null. */
	fun onFrame(scrolling: Boolean, settledPage: Int, currentIndex: Int): Int? {
		if (settledPage == lastSettled) return null
		lastSettled = settledPage
		val wasUserSwipe = userDragging
		userDragging = false
		if (settledPage == currentIndex || !wasUserSwipe) return null
		return settledPage
	}
}
