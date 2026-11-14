package app.worbus.ui.screens.artist

import app.worbus.domain.models.DomainArtist
import app.worbus.ui.screens.artist.viewmodels.ArtistMbidUi
import app.worbus.ui.screens.artist.viewmodels.ArtistPageMemory
import app.worbus.ui.screens.artist.viewmodels.ArtistPagePrefetcher
import app.worbus.ui.screens.artist.viewmodels.ArtistPageSnapshot
import app.worbus.ui.screens.artist.viewmodels.ArtistState
import app.worbus.ui.screens.artist.viewmodels.DiscographyUi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/*
 * B-446 follow-up (the owner, 2026-11-14): after B-446 every artist page with nothing in ArtistPageMemory opened
 * on a short loading screen. The album page now reads its artist's page ahead into the memory, in a lane of its
 * own, so the artist link opens seeded (complete in its first frame, as a revisit) without pushing out the
 * artists the user has visited.
 */
class ArtistPagePrefetchTest {
	private fun snapshot(id: String, starred: Boolean = false) = ArtistPageSnapshot(
		ArtistState(DomainArtist(id = id, name = "A $id"), emptyList(), emptyList()),
		DiscographyUi(),
		ArtistMbidUi(effective = "mb-$id"),
		starred = starred
	)

	@Test
	fun aPageReadAheadOpensSeeded() {
		val memory = ArtistPageMemory()
		val ahead = snapshot("x")
		assertTrue(memory.putPrefetched("x", ahead, readIn = memory.currentSession))
		assertSame(ahead, memory["x"], "the artist page opens on the read-ahead page")
	}

	@Test
	fun readingAheadNeverPushesOutAVisitedArtist() {
		val memory = ArtistPageMemory()
		(1..8).forEach { memory.put("v$it", snapshot("v$it")) }
		(1..10).forEach { memory.putPrefetched("p$it", snapshot("p$it"), memory.currentSession) }
		(1..8).forEach { assertEquals("v$it", memory["v$it"]?.state?.artist?.id, "visited v$it was pushed out") }
		// The read-ahead lane is bounded on its own: the four newest.
		(1..6).forEach { assertNull(memory["p$it"], "p$it kept beyond the bound") }
		(7..10).forEach { assertEquals("p$it", memory["p$it"]?.state?.artist?.id) }
	}

	@Test
	fun aVisitedPageIsNotReplacedByAReadAhead() {
		val memory = ArtistPageMemory()
		val shown = snapshot("x", starred = true)
		memory.put("x", shown)
		assertFalse(memory.putPrefetched("x", snapshot("x"), memory.currentSession))
		assertSame(shown, memory["x"])
	}

	@Test
	fun aPageOpenedOnAReadAheadKeepsItsOwnState() {
		val memory = ArtistPageMemory()
		memory.putPrefetched("x", snapshot("x"), memory.currentSession)
		val shown = snapshot("x", starred = true)
		memory.put("x", shown) // the page writes what it shows
		(1..8).forEach { memory.putPrefetched("p$it", snapshot("p$it"), memory.currentSession) }
		assertSame(shown, memory["x"], "the opened page moved to the visited lane")
	}

	@Test
	fun aReadAheadThatEndsInAnotherSessionIsDropped() {
		var session = 1L
		val memory = ArtistPageMemory { session }
		val readIn = memory.currentSession
		session = 2L // a logout or a switch while Room was read
		assertFalse(memory.putPrefetched("x", snapshot("x"), readIn))
		assertNull(memory["x"])
		memory.putPrefetched("y", snapshot("y"), memory.currentSession)
		session = 3L
		assertNull(memory["y"], "a new session sees nothing the old one read ahead")
	}

	@Test
	fun theAlbumPageReadsItsArtistOnceAndNotWhenKept() = runBlocking {
		val memory = ArtistPageMemory()
		val gate = CompletableDeferred<Unit>()
		val reads = mutableListOf<String>()
		val prefetcher = ArtistPagePrefetcher(read = { id -> reads += id; gate.await(); snapshot(id) }, memory = memory)
		val first = async { prefetcher.warm("x") }
		yield()
		assertNull(prefetcher.warm("x"), "a second album page of the artist while the first read runs")
		gate.complete(Unit)
		assertEquals("x", first.await()?.state?.artist?.id)
		assertNull(prefetcher.warm("x"), "kept already")
		memory.put("v", snapshot("v"))
		assertNull(prefetcher.warm("v"), "visited already")
		assertNull(prefetcher.warm(""), "no artist")
		assertEquals(listOf("x"), reads)
	}

	@Test
	fun aReadAheadAcrossASessionChangeKeepsNothing() = runBlocking {
		var session = 1L
		val memory = ArtistPageMemory { session }
		val prefetcher = ArtistPagePrefetcher(read = { id -> session = 2L; snapshot(id) }, memory = memory)
		assertNull(prefetcher.warm("x"))
		assertNull(memory["x"])
	}
}
