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

	/*
	 * B-404 review M1: the bridge held for good on a page whose cover fetch had FAILED (a Cover Art Archive
	 * 404, a page opened offline): `coverToFetch` stayed true, so an unowned album with no front image wore
	 * the colour of the page it was opened from, and published it as its own.
	 */
	@Test
	fun aCoverWhoseFetchFailedIsNothingOnItsWay() {
		assertFalse(
			coverBridgeHolds(true, false, true, coverToFetch = true, coverPending = false, fetchFailed = true),
			"the fetch failed: the plain page, not the page it came from"
		)
		assertFalse(coverBridgeHolds(true, false, true, coverToFetch = true, coverPending = true, fetchFailed = true))
		assertTrue(coverBridgeHolds(true, false, true, coverToFetch = true, coverPending = false, fetchFailed = false), "still fetching: the bridge")
	}

	/*
	 * B-404 review L5: the cover pages published their seed whether or not they wore it, and an unresolved
	 * page's seed is the app's surface: the next page bridged from a tinted near-white (rule 1).
	 */
	@Test
	fun aPagePublishesOnlyAColourItWears() {
		fun colors(resolved: Boolean, wears: Boolean, themed: Boolean = true) = CoverColors(
			scheme = androidx.compose.material3.lightColorScheme(),
			seed = androidx.compose.ui.graphics.Color.Red,
			dominant = androidx.compose.ui.graphics.Color.Red,
			isDark = false,
			resolved = resolved,
			wears = wears,
			themed = themed
		)
		assertEquals(androidx.compose.ui.graphics.Color.Red, colors(resolved = true, wears = true).publishedSeed())
		assertEquals(androidx.compose.ui.graphics.Color.Red, colors(resolved = false, wears = true).publishedSeed(), "the bridge it shows")
		assertNull(colors(resolved = false, wears = false).publishedSeed(), "unresolved, no bridge: the surface is no colour")
		assertNull(colors(resolved = true, wears = true, themed = false).publishedSeed(), "theming off")
	}
}
