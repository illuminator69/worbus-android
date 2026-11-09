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
	fun anInsertAtTheTopGoesBeforeTheOldFirstRow() {
		assertEquals(listOf(4, 0, 1, 5, 2, 6, 3), PlayOrder.insert(six, 0, 1))
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
}
