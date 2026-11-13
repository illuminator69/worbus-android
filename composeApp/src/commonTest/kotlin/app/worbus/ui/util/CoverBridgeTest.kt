package app.worbus.ui.util

import app.worbus.ui.navigation.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * B-404 (the owner's re-walk, 2026-11-13, `011b738d`): opening an album still flashed a background colour
 * before it settled on the album's ("the album page slides in over a grey backdrop, then recolours"). The
 * album page's cover came from its record, which Room gives a few frames after the page's first: until
 * then the page had no cover id, so it drew the neutral surface (the bridge from the page it came from
 * held only while a cover was being FETCHED), then eased to the album's colour once the record arrived.
 */
class CoverBridgeTest {
	@Test
	fun thePageOpensOnTheCoverItWasOpenedWithUntilItsRecordIsRead() {
		assertEquals("al-1", pageCoverArtId(recordRead = false, recordCover = null, opened = "al-1"))
		assertEquals("al-2", pageCoverArtId(recordRead = true, recordCover = "al-2", opened = "al-1"), "the record's, once read")
		assertNull(pageCoverArtId(recordRead = true, recordCover = null, opened = "al-1"), "an album with no cover has none")
	}

	@Test
	fun theAlbumPagesKeyCarriesTheOpenersCover() {
		assertEquals("al-1", Screen.CollectionDetail("1", "artist", coverArtId = "al-1").coverArtId)
		assertNull(Screen.CollectionDetail("1", "artist").coverArtId, "an opener that does not know it")
	}

	@Test
	fun whileTheRecordIsReadThePageWearsThePageItCameFrom() {
		assertTrue(
			coverBridgeHolds(themingOn = true, resolved = false, hasInitialSeed = true, coverToFetch = false, coverPending = true),
			"no cover id yet, the record on its way: the bridge holds, not the neutral surface"
		)
		assertTrue(coverBridgeHolds(true, false, true, coverToFetch = true, coverPending = false), "a cover being fetched, as before")
	}

	@Test
	fun withNothingOnItsWayThereIsNoBridge() {
		// Rule 3 of the colour engine: an artist with no picture must not keep the album's colour for good.
		assertFalse(coverBridgeHolds(true, false, true, coverToFetch = false, coverPending = false))
		assertFalse(coverBridgeHolds(true, resolved = true, hasInitialSeed = true, coverToFetch = true, coverPending = false), "resolved: its own")
		assertFalse(coverBridgeHolds(themingOn = false, resolved = false, hasInitialSeed = true, coverToFetch = true, coverPending = true))
		assertFalse(coverBridgeHolds(true, false, hasInitialSeed = false, coverToFetch = true, coverPending = true))
	}

	@Test
	fun theHolderCarriesThePagesBrightnessOnlyWithItsColour() {
		val h = AmbientColorHolder()
		h.publish(androidx.compose.ui.graphics.Color.Red, isDark = true)
		assertEquals(true, h.lastDark)
		h.publish(null, isDark = true)
		assertNull(h.last)
		assertNull(h.lastDark, "a plain page: no colour, and no brightness to bridge")
	}
}
