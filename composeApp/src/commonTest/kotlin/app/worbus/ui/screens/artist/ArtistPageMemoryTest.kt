package app.worbus.ui.screens.artist

import app.worbus.domain.models.DomainArtist
import app.worbus.ui.screens.artist.viewmodels.ArtistMbidUi
import app.worbus.ui.screens.artist.viewmodels.ArtistPageMemory
import app.worbus.ui.screens.artist.viewmodels.ArtistPageSnapshot
import app.worbus.ui.screens.artist.viewmodels.ArtistState
import app.worbus.ui.screens.artist.viewmodels.DiscographyEntry
import app.worbus.ui.screens.artist.viewmodels.DiscographySection
import app.worbus.ui.screens.artist.viewmodels.DiscographyUi
import app.worbus.ui.screens.artist.viewmodels.seeded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/*
 * B-405 (the owner's re-walk, 2026-11-13, `011b738d`): a large discography still opened in two stages. On
 * the VM AVD (release, The Long Players, ~300 releases) the page's own view model read the whole artist
 * again before it had anything: the page slid in on a spinner, then its list was composed mid-slide when
 * the read landed (one 70-89 ms UI-thread frame 130-300 ms into the transition), and its rows arrived one
 * after another after that. The page now opens on what the artist page showed.
 */
class ArtistPageMemoryTest {
	private fun artist(id: String) = DomainArtist(id = id, name = "A $id")
	private fun snapshot(id: String, discography: DiscographyUi = DiscographyUi()) =
		ArtistPageSnapshot(ArtistState(artist(id), emptyList(), emptyList()), discography, ArtistMbidUi(effective = "mb-$id"))

	@Test
	fun theNextPageOfAnArtistGetsWhatTheLastOneShowed() {
		val memory = ArtistPageMemory()
		assertNull(memory["x"], "nothing shown yet: the page reads Room as before")
		val shown = snapshot("x")
		memory.put("x", shown)
		assertSame(shown, memory["x"])
	}

	@Test
	fun aFewArtistsAreKeptTheOldestGoesFirst() {
		val memory = ArtistPageMemory()
		(1..9).forEach { memory.put("a$it", snapshot("a$it")) }
		assertNull(memory["a1"], "the ninth pushes out the first")
		memory.put("a2", snapshot("a2"))
		memory.put("a10", snapshot("a10"))
		assertEquals("a2", memory["a2"]?.state?.artist?.id, "a page shown again is the newest")
		assertNull(memory["a3"])
	}

	@Test
	fun aNewPageTakesTheIndexNotTheOtherPagesWorkInFlight() {
		val sections = listOf(DiscographySection("album", listOf(DiscographyEntry(key = "rg1", title = "One", year = "2001"))))
		val other = DiscographyUi(
			available = true, indexed = true, indexing = true, scannedAt = 5L, stale = true,
			scanError = "boom", scanShared = true, sections = sections, scanDone = 3, scanTotal = 9,
			unresolved = true, ndIdScanRefused = true, justScanned = true
		)
		assertEquals(
			DiscographyUi(available = true, indexed = true, scannedAt = 5L, stale = true, sections = sections, unresolved = true),
			other.seeded()
		)
	}
}
