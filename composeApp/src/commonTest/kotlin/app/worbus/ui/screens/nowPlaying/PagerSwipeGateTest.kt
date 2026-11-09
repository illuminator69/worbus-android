package app.worbus.ui.screens.nowPlaying

import app.worbus.ui.screens.nowPlaying.components.controls.PagerSwipeGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/*
 * B-142: the player jumped from queue position 64 to 57 and started it from 0, about a second after
 * a station restart replaced the queue, during an automated scroll. The swipe flag was set on a
 * drag's start and cleared only when the settled page CHANGED: a drag that came back to the page it
 * left changed nothing, so the flag stayed armed, and the next programmatic settle (the pager
 * following a queue change to an index the queue had already left) was taken for a swipe:
 * `playAt(page)`, i.e. `seekTo(index, 0)`.
 *
 * Frames are what the pager shows, in order: (scrolling, settled page), with the playing index.
 */
class PagerSwipeGateTest {
	@Test
	fun aDragThatComesBackToItsPageLeavesNothingArmed() {
		val gate = PagerSwipeGate(initialPage = 64)
		gate.onDragStart()
		assertNull(gate.onFrame(scrolling = true, settledPage = 64, currentIndex = 64))
		assertNull(gate.onFrame(scrolling = false, settledPage = 64, currentIndex = 64), "back on its own page: nothing to play")

		// The queue changes; the pager is scrolled to follow it, and the playing index has moved
		// on again by the time it settles.
		assertNull(gate.onFrame(scrolling = true, settledPage = 64, currentIndex = 58))
		assertNull(gate.onFrame(scrolling = false, settledPage = 57, currentIndex = 58), "a programmatic settle is not a swipe")
	}

	@Test
	fun aSwipeToAnotherPagePlaysIt() {
		val gate = PagerSwipeGate(initialPage = 3)
		gate.onDragStart()
		assertNull(gate.onFrame(scrolling = true, settledPage = 3, currentIndex = 3))
		assertEquals(4, gate.onFrame(scrolling = false, settledPage = 4, currentIndex = 3))
	}

	@Test
	fun aSettleWithNoDragNeverPlays() {
		val gate = PagerSwipeGate(initialPage = 3)
		assertNull(gate.onFrame(scrolling = true, settledPage = 3, currentIndex = 9))
		assertNull(gate.onFrame(scrolling = false, settledPage = 9, currentIndex = 8))
	}
}
