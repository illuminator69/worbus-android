package app.worbus.ui.screens.collection

import app.worbus.domain.manager.LbGapSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/*
 * B-370: the gaps box kept the chosen card as a list POSITION. A search (on this device or another)
 * replaces lb-bot's source list whole, so the highlight moved to whatever card then sat at that
 * position, and Request sent that card's id: lb-bot fetched another folder, with no error. lb-bot's
 * ids are the folder's identity since Phase A W6 (peer + folder, the same across searches), so the
 * selection is that id.
 */
class GapSelectionTest {
	private val alice = LbGapSource(id = 4711, peer = "alice", folder = "Album (FLAC)")
	private val bob = LbGapSource(id = 1302, peer = "bob", folder = "Album [MP3]")
	private val carol = LbGapSource(id = 88, peer = "carol", folder = "Album")

	@Test
	fun theChosenCardIsTheSameFolderAfterTheListIsReplaced() {
		val before = listOf(bob, alice)
		val kept = gapSelection(before, 1) // alice's card tapped

		val after = listOf(alice, carol, bob) // a new search ranked alice first
		assertEquals(alice, gapChosenSource(after, kept), "Request sends the folder that was tapped")
		assertEquals(0, gapSelectedIndex(after, kept), "and the highlight follows it")
	}

	@Test
	fun aChosenFolderTheNewListNoLongerHoldsIsNoSelection() {
		val kept = gapSelection(listOf(bob, alice), 1)

		val after = listOf(carol, bob)
		assertNull(gapChosenSource(after, kept), "nothing is sent for a folder that is gone")
		assertNull(gapSelectedIndex(after, kept))
	}

	@Test
	fun theSafeDefaultStandsInOnlyForAChoiceThatIsGone() {
		val sources = listOf(alice, bob)
		assertEquals(alice.id, gapPreselection(sources, preselected = 0, selectedId = null, pickAgain = false))
		assertEquals(alice.id, gapPreselection(sources, preselected = 0, selectedId = carol.id, pickAgain = false), "carol left the list")
		assertNull(gapPreselection(sources, preselected = 0, selectedId = bob.id, pickAgain = false), "a choice in the list stands")
		assertNull(gapPreselection(sources, preselected = null, selectedId = null, pickAgain = false))
	}

	@Test
	fun afterAStaleRefusalNothingIsChosenForTheUser() {
		// Review nit (B-370): after lb-bot's 409 stale_source the box shows lb-bot's sentence and
		// re-reads the list. If the safe default then stood in, a card the user never picked was
		// highlighted under that sentence, and a quick second tap on Request sent it.
		assertNull(gapPreselection(listOf(alice, bob), preselected = 0, selectedId = null, pickAgain = true), "the user picks again")
	}
}
