package app.worbus.ui.screens.nowPlaying.components.controls

/**
 * Which settles of the cover pager are the user's swipe, and so change the track.
 *
 * The pager is also scrolled PROGRAMMATICALLY whenever the playing index changes (playback
 * advancing, locally or through the remote mirror). Acting on those settles caused an infinite
 * jump/skip loop when remote, so only the end of a scroll that a drag started acts.
 *
 * B-142: the flag used to be cleared only when the settled page CHANGED. A drag that came back to
 * the page it left changed nothing and left it armed, and the next programmatic settle was taken
 * for a swipe (`playAt`, from 0). Now every scroll's end consumes it, whatever page it ends on, and
 * a programmatic scroll ([onProgrammaticScroll]) takes it over from a drag still settling.
 *
 * [onFrame] is fed every (scrolling, settled page) the pager shows, with the playing index.
 */
class PagerSwipeGate {
	private var scrollByUser = false
	private var wasScrolling = false

	/** A drag started on the pager. */
	fun onDragStart() {
		scrollByUser = true
	}

	/** The app is about to scroll the pager itself (following the playing index). */
	fun onProgrammaticScroll() {
		scrollByUser = false
	}

	/** The page to play, or null. */
	fun onFrame(scrolling: Boolean, settledPage: Int, currentIndex: Int): Int? {
		val ended = wasScrolling && !scrolling
		wasScrolling = scrolling
		if (!ended) return null
		val byUser = scrollByUser
		scrollByUser = false
		return settledPage.takeIf { byUser && it != currentIndex }
	}
}
