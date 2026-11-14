package app.worbus.domain.manager

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/*
 * I-026 "Library Matches": the page that replaced the "lb-bot data" settings section. What the
 * lists contain and say is unchanged and tested where it was (`LbBotDataTest`,
 * `OverrideListsTest`, `MatchesToCheckTest`); these are the page's own rules — which section it
 * opens on, what a chip says, what a row draws and whether it leads anywhere.
 */
class LibraryMatchesTest {
	private val all = listOf(MatchesSection.TO_CHECK, MatchesSection.ARTISTS, MatchesSection.ALBUMS, MatchesSection.MERGES)

	// --- the sections ------------------------------------------------------------------------

	@Test
	fun thePageOffersFourSectionsInOrder() {
		assertEquals(all, matchesSections(toCheck = true, albums = true, merges = true))
	}

	@Test
	fun aListLbBotDoesNotSendIsNoSectionAndArtistsIsAlwaysOne() {
		// An older lb-bot: the override list alone.
		assertEquals(listOf(MatchesSection.ARTISTS), matchesSections(toCheck = false, albums = false, merges = false))
		assertEquals(
			listOf(MatchesSection.TO_CHECK, MatchesSection.ARTISTS, MatchesSection.MERGES),
			matchesSections(toCheck = true, albums = false, merges = true)
		)
	}

	@Test
	fun thePageOpensOnToCheckWhenItHasRowsElseOnArtists() {
		assertEquals(MatchesSection.TO_CHECK, matchesSectionToShow(null, all, toCheckCount = 106))
		assertEquals(MatchesSection.ARTISTS, matchesSectionToShow(null, all, toCheckCount = 0))
		// Rows counted for a section that is not on offer open nothing.
		assertEquals(MatchesSection.ARTISTS, matchesSectionToShow(null, listOf(MatchesSection.ARTISTS), toCheckCount = 5))
	}

	@Test
	fun theUsersChoiceStandsWhileItIsOnOffer() {
		assertEquals(MatchesSection.MERGES, matchesSectionToShow(MatchesSection.MERGES, all, toCheckCount = 106))
		// An empty section is still the user's to look at.
		assertEquals(MatchesSection.TO_CHECK, matchesSectionToShow(MatchesSection.TO_CHECK, all, toCheckCount = 0))
		// The section went away under the choice (an lb-bot rolled back): back to the landing one.
		val fewer = listOf(MatchesSection.TO_CHECK, MatchesSection.ARTISTS)
		assertEquals(MatchesSection.TO_CHECK, matchesSectionToShow(MatchesSection.ALBUMS, fewer, toCheckCount = 3))
	}

	@Test
	fun aChipNamesItsSectionAndCountsWhatWasLoaded() {
		assertEquals("To check (106)", matchesSectionLabel(MatchesSection.TO_CHECK, 106))
		assertEquals("Artists (0)", matchesSectionLabel(MatchesSection.ARTISTS, 0))
		assertEquals("Albums (3)", matchesSectionLabel(MatchesSection.ALBUMS, 3))
		assertEquals("Merged names (1)", matchesSectionLabel(MatchesSection.MERGES, 1))
		// Nothing loaded yet: a count would be a count of nothing.
		assertEquals("Artists", matchesSectionLabel(MatchesSection.ARTISTS, null))
	}

	// --- B-406: To fix, the "Needs attention" page that was -------------------------------------

	/*
	 * The owner's re-walk ruling (2026-11-13): the "To fix" downloaded-folder items belong on Library Matches,
	 * and the "Needs attention" menu felt redundant. lb-bot's to-fix list (failed albums, albums waiting on a
	 * pick, folders and imports to file) is this page's first chip, offered to an admin (lb-bot answers it to
	 * admins only), and the page opens on it while lb-bot counts something to fix.
	 */
	@Test
	fun toFixIsTheFirstSectionForAnAdmin() {
		assertEquals(listOf(MatchesSection.TO_FIX) + all, matchesSections(toCheck = true, albums = true, merges = true, toFix = true))
		assertEquals(all, matchesSections(toCheck = true, albums = true, merges = true, toFix = false), "not for anyone else")
	}

	@Test
	fun thePageOpensOnToFixWhileThereIsSomethingToFix() {
		val withFix = listOf(MatchesSection.TO_FIX) + all
		assertEquals(MatchesSection.TO_FIX, matchesSectionToShow(null, withFix, toCheckCount = 106, toFixCount = 9))
		assertEquals(MatchesSection.TO_CHECK, matchesSectionToShow(null, withFix, toCheckCount = 106, toFixCount = 0), "nothing to fix: as before")
		assertEquals(MatchesSection.TO_CHECK, matchesSectionToShow(null, all, toCheckCount = 106, toFixCount = 9), "not offered: as before")
		// The old Needs attention entry opens the page on To fix whatever it counts.
		assertEquals(MatchesSection.TO_FIX, matchesSectionToShow(MatchesSection.TO_FIX, withFix, toCheckCount = 106, toFixCount = 0))
	}

	/*
	 * B-406 review M3: the page followed the live counts for the whole visit. An admin opened it on To fix (1),
	 * tapped Retry, the to-fix number dropped to 0 and the whole list swapped to To check under the snackbar.
	 * The first choice made on known numbers is kept (the screen writes it into `chosen`).
	 */
	@Test
	fun thePagesFirstChoiceIsFinalOnceItsNumbersAreKnown() {
		val withFix = listOf(MatchesSection.TO_FIX) + all
		assertTrue(matchesFirstChoiceFinal(withFix, toFixCount = 1, toCheckRead = false, toFixRead = true, rolesKnown = true), "To fix wins whatever To check says")
		assertFalse(matchesFirstChoiceFinal(withFix, toFixCount = 0, toCheckRead = false, toFixRead = true, rolesKnown = true), "To check vs Artists not decided yet")
		assertTrue(matchesFirstChoiceFinal(withFix, toFixCount = 0, toCheckRead = true, toFixRead = true, rolesKnown = true))
		assertTrue(matchesFirstChoiceFinal(all, toFixCount = 0, toCheckRead = true, toFixRead = false, rolesKnown = true))
		// Kept: once `chosen` holds the first choice, a count that drops does not move the page.
		val first = matchesSectionToShow(null, withFix, toCheckCount = 106, toFixCount = 1)
		assertEquals(MatchesSection.TO_FIX, first)
		assertEquals(MatchesSection.TO_FIX, matchesSectionToShow(first, withFix, toCheckCount = 106, toFixCount = 0), "after Retry: stays")
	}

	@Test
	fun theToFixChipCountsLbBotsToFixNumber() {
		assertEquals("To fix (9)", matchesSectionLabel(MatchesSection.TO_FIX, 9))
		assertEquals("To fix", matchesSectionLabel(MatchesSection.TO_FIX, null), "no answer yet: no number")
	}

	// --- what a row draws, and whether it is a link ------------------------------------------

	private val artists = mapOf("ar-1" to "ar-ar-1_0", "ar-2" to null)
	private val albums = mapOf<String, String?>("al-1" to "al-al-1_0")
	private val cover = "https://coverartarchive.org/release/x/1.jpg"

	@Test
	fun anArtistRowShowsTheLibraryArtistsImage() {
		assertEquals(LbArt.Library("ar-ar-1_0", isArtist = true), libraryArtistArt("ar-1", artists))
		// Held, with no picture of its own: the art component's placeholder, still as an artist.
		assertEquals(LbArt.Library(null, isArtist = true), libraryArtistArt("ar-2", artists))
	}

	@Test
	fun anArtistGoneFromTheLibraryShowsThePlaceholder() {
		assertEquals(LbArt.Placeholder(isArtist = true), libraryArtistArt("ar-gone", artists))
		assertEquals(LbArt.Placeholder(isArtist = true), libraryArtistArt("", artists))
	}

	@Test
	fun anAlbumRowShowsTheAlbumsCover() {
		assertEquals(LbArt.Library("al-al-1_0", isArtist = false), matchAlbumArt("al-1", cover, albums))
	}

	@Test
	fun anAlbumWhoseIdIsNotKnownYetShowsTheCoverLbBotWasGiven() {
		assertEquals(LbArt.Remote(cover), matchAlbumArt("", cover, albums))
		assertEquals(LbArt.Placeholder(isArtist = false), matchAlbumArt("", "", albums))
	}

	@Test
	fun anAlbumGoneFromTheLibraryShowsThePlaceholder() {
		// The id is named and the library does not hold it: not the cover of something else.
		assertEquals(LbArt.Placeholder(isArtist = false), matchAlbumArt("al-gone", cover, albums))
	}

	@Test
	fun aRowIsALinkOnlyWhenTheLibraryHoldsItsId() {
		assertEquals(true, heldInLibrary("ar-1", artists))
		assertEquals(true, heldInLibrary("ar-2", artists))
		assertEquals(false, heldInLibrary("ar-gone", artists))
		assertEquals(false, heldInLibrary("", artists))
		assertEquals(false, heldInLibrary("", mapOf("" to null)))
	}
}
