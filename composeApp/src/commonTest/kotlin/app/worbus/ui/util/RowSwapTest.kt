package app.worbus.ui.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * Motion lane review L7 (2026-11-14): a SwapRows list fades its rows through for the change a chip made, and only
 * that one. The swap's item motion was the list's for good, so each answer to a Search query, every hub update to
 * the saved queues and a row an action removed were held back by the swap's out window and staged.
 */
class RowSwapTest {
	@Test
	fun aChangeNoChipMadeKeepsTheListsOwnMotion() {
		val swap = RowSwap()
		assertFalse(swap.swapMotion(changed = true), "a search answer was staged as a chip swap")
		swap.rowsShown(changed = true)
		assertFalse(swap.swapMotion(changed = false))
	}

	@Test
	fun theChangeAChipMadeSwapsForTheSwapsTimeThenNoMore() {
		val swap = RowSwap()
		swap.chipTapped()
		assertFalse(swap.swapMotion(changed = false), "the tap's own frame, rows not changed yet")
		assertTrue(swap.swapMotion(changed = true), "the chip's rows")
		swap.rowsShown(changed = true)
		assertTrue(swap.swapMotion(changed = false), "still the chip's swap while it runs")
		assertTrue(swap.swapMotion(changed = true), "rows that land during it")
		swap.settled(swap.generation)
		assertFalse(swap.swapMotion(changed = true), "an answer after the swap took the swap's motion")
	}

	@Test
	fun aChipsRowsMayLandLater() {
		val swap = RowSwap()
		swap.chipTapped()                       // Charts: the tiles stay until the new chart lands
		swap.rowsShown(changed = false)
		assertTrue(swap.swapMotion(changed = true), "the new chart, seconds later")
	}

	@Test
	fun anOlderSwapsEndLeavesANewerOneRunning() {
		val swap = RowSwap()
		swap.chipTapped()
		swap.rowsShown(changed = true)
		val first = swap.generation
		swap.chipTapped()
		swap.rowsShown(changed = true)
		swap.settled(first)
		assertTrue(swap.swapMotion(changed = false), "the first swap's end cut the second short")
	}
}
