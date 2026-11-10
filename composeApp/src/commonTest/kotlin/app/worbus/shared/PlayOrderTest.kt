package app.worbus.shared

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W1: the one rule for what plays next under shuffle (DESIGN-w1-play-order §2). */
class PlayOrderTest {
	private val six = listOf(3, 0, 4, 1, 5, 2)

	@Test
	fun appendGoesToTheEndInTheOrderGiven() {
		assertEquals(listOf(3, 0, 4, 1, 5, 2, 6, 7, 8), PlayOrder.insert(six, 6, 3))
	}

	@Test
	fun playNextLandsRightAfterTheRowBeforeIt() {
		// Row 4 plays; Play next inserts at row 5, which shifts the old 5 to 6.
		assertEquals(listOf(3, 0, 4, 5, 6, 1, 7, 2), PlayOrder.insert(six, 5, 2))
	}

	@Test
	fun anInsertAboveTheFirstRowGoesRightBeforeItsQueueSuccessor() {
		// Controller ruling (2026-11-10, W1 review): with no queue predecessor, right BEFORE the
		// queue successor (the old row 0, now row 1) in play order; the mirror of ruling 6.
		assertEquals(listOf(4, 0, 1, 5, 2, 6, 3), PlayOrder.insert(six, 0, 1))
		assertEquals(listOf(5, 0, 1, 2, 6, 3, 7, 4), PlayOrder.insert(six, 0, 2))
	}

	@Test
	fun playNextGoesRightAfterThePlayingRowEvenWhenItIsTheLastQueueRow() {
		// W1 review finding 1: row 5 (the last queue row) plays at play position 4; Play next lands at
		// row 6, which is also where an append lands. Ruling 6: right after the current song.
		assertEquals(listOf(3, 0, 4, 1, 5, 6, 2), PlayOrder.playNext(six, 6, 1, current = 5))
		assertEquals(listOf(3, 0, 4, 1, 5, 6, 7, 2), PlayOrder.playNext(six, 6, 2, current = 5))
		// The ordinary case is unchanged.
		assertEquals(listOf(3, 0, 4, 5, 6, 1, 7, 2), PlayOrder.playNext(six, 5, 2, current = 4))
	}

	@Test
	fun anInsertIntoNothingIsAnOrderOfTheNewRows() {
		val order = PlayOrder.insert(emptyList(), 0, 5, Random(1))
		assertTrue(PlayOrder.isPermutation(order, 5))
	}

	@Test
	fun removeDropsTheRowsAndKeepsTheRest() {
		assertEquals(listOf(3, 0, 1, 4, 2), PlayOrder.remove(six, 4, 5))
		assertEquals(listOf(1, 2, 3, 0), PlayOrder.remove(six, 0, 2))
	}

	@Test
	fun moveKeepsEveryRowsPlayPosition() {
		// Rows by name: queue [a b c d e f], play order d a e b f c.
		val names = listOf("a", "b", "c", "d", "e", "f")
		val played = six.map { names[it] }
		fun check(from: Int, to: Int, newFrom: Int) {
			val queue = names.toMutableList()
			val moved = queue.subList(from, to).toList()
			repeat(moved.size) { queue.removeAt(from) }
			queue.addAll(newFrom, moved)
			val order = PlayOrder.move(six, from, to, newFrom)
			assertTrue(PlayOrder.isPermutation(order, 6))
			assertEquals(played, order.map { queue[it] }, "move($from, $to, $newFrom)")
		}
		check(5, 6, 0)
		check(0, 1, 5)
		check(1, 3, 3)
		check(2, 4, 0)
	}

	@Test
	fun currentFirstPutsTheRowFirstAndKeepsEveryRow() {
		val order = PlayOrder.currentFirst(20, 7, Random(3))
		assertEquals(7, order.first())
		assertTrue(PlayOrder.isPermutation(order, 20))
		assertTrue(PlayOrder.isPermutation(PlayOrder.currentFirst(4, -1, Random(3)), 4))
	}

	@Test
	fun isPermutationRefusesWrongSizesRepeatsAndStrangers() {
		assertTrue(PlayOrder.isPermutation(six, 6))
		assertFalse(PlayOrder.isPermutation(six, 7))
		assertFalse(PlayOrder.isPermutation(listOf(0, 0, 1), 3))
		assertFalse(PlayOrder.isPermutation(listOf(0, 1, 3), 3))
		assertFalse(PlayOrder.isPermutation(null, 0))
	}

	@Test
	fun successorIsTheNextInPlayOrder() {
		assertEquals(1, PlayOrder.successor(six, 4))
		assertNull(PlayOrder.successor(six, 2))
	}

	@Test
	fun aSentOrderIsShownUntilTheTimelineShowsItAndTheMaskIsNot() {
		val sent = listOf(2, 0, 3, 1)
		// The controller's mask after the edit: queue order. Not shown; still waiting.
		assertEquals(PlayOrder.Reading(sent, sent), PlayOrder.resolve(true, listOf(0, 1, 2, 3), sent, sent, 4))
		// The session's timeline before the order lands (its own rule's result): still waiting.
		assertEquals(PlayOrder.Reading(sent, sent), PlayOrder.resolve(true, listOf(2, 3, 0, 1), sent, sent, 4))
		// It lands.
		assertEquals(PlayOrder.Reading(sent, null), PlayOrder.resolve(true, sent, sent, sent, 4))
		// Nothing sent: the timeline is believed (the notification's shuffle button).
		assertEquals(PlayOrder.Reading(listOf(3, 0, 1, 2), null), PlayOrder.resolve(true, listOf(3, 0, 1, 2), null, sent, 4))
	}

	@Test
	fun aReadingIsAlwaysAnOrderOfItsQueueOrNothing() {
		val sent = listOf(4, 0, 3, 1, 2)
		// Inside an append the state still holds four rows: the five-row order waits, the old one shows.
		assertEquals(PlayOrder.Reading(listOf(2, 0, 3, 1), sent), PlayOrder.resolve(true, listOf(0, 1, 2, 3, 4), sent, listOf(2, 0, 3, 1), 4))
		// No order of this queue anywhere: none.
		assertEquals(PlayOrder.Reading(null, null), PlayOrder.resolve(true, listOf(0, 1), null, listOf(1, 0, 2), 4))
		// Shuffle off: none, and nothing waited for.
		assertEquals(PlayOrder.Reading(null, null), PlayOrder.resolve(false, sent, sent, sent, 5))
	}

	@Test
	fun onceTheSentOrderHasLandedTheNextTimelineIsBelieved() {
		// W1 review finding 4: the service accepted the order, then its order changed some other way
		// (an outside controller's same-size edit). Waiting for an exact match would wait forever.
		val sent = listOf(2, 0, 1)
		assertEquals(PlayOrder.Reading(listOf(1, 0, 2), null), PlayOrder.resolve(true, listOf(1, 0, 2), sent, sent, 3, landed = true))
		// Not landed yet: still waiting.
		assertEquals(PlayOrder.Reading(sent, sent), PlayOrder.resolve(true, listOf(1, 0, 2), sent, sent, 3, landed = false))
	}
}
