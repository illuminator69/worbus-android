package app.worbus.ui.screens.nowPlaying

import app.worbus.ui.screens.nowPlaying.components.controls.PagerSwipeGate
import app.worbus.ui.screens.nowPlaying.components.controls.pagerPage
import app.worbus.ui.screens.nowPlaying.components.controls.pagerRow
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * B-343: under shuffle the cover pager's pages were queue rows, so a swipe went to the next song in
 * QUEUE order while the peek strip and the queue list (B-341) named the next in play order. The
 * pages follow the play order: page p shows row order[p], the playing row's page is its play
 * position, and a settled swipe plays the row its page shows.
 */
class PagerOrderTest {
	private val order = listOf(3, 0, 4, 1, 5, 2)

	@Test
	fun underShuffleASwipeToTheNextPagePlaysTheNextInPlayOrder() {
		val current = 4
		val page = pagerPage(current, 6, order)
		assertEquals(2, page, "row 4 is shown on its play position")
		val gate = PagerSwipeGate()
		gate.onDragStart()
		gate.onFrame(scrolling = true, settledPage = page, currentIndex = page)
		val target = gate.onFrame(scrolling = false, settledPage = page + 1, currentIndex = page)
		assertEquals(page + 1, target)
		assertEquals(1, pagerRow(target!!, 6, order), "the page after row 4 shows row 1, the next in play order")
	}

	@Test
	fun everyPageShowsADifferentRowAndEveryRowHasItsPage() {
		val rows = (0 until 6).map { pagerRow(it, 6, order) }
		assertEquals(order, rows)
		(0 until 6).forEach { row -> assertEquals(row, pagerRow(pagerPage(row, 6, order), 6, order)) }
	}

	@Test
	fun withoutAnOrderOfTheseRowsPagesAreQueueRows() {
		assertEquals(3, pagerRow(3, 6, null))
		assertEquals(3, pagerPage(3, 6, null))
		// An order of another queue (a frame ahead or behind) is not used.
		assertEquals(3, pagerRow(3, 7, order))
		assertEquals(3, pagerPage(3, 7, order))
	}
}
