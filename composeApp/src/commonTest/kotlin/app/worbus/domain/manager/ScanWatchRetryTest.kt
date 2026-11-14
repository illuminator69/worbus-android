package app.worbus.domain.manager

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Stage 2 review M6 and M7 (B-429 follow-ups).
 * - M6: the lastScan the watch acted on was one value for every server and user, so the first reading after a login
 *   elsewhere always differed (a whole-library sweep right after the login's own pull); and it moved before the
 *   sweep, so a failed sweep was not tried again while the app ran.
 * - M7: a failed `getArtists` after albums were pulled was not tried again: on the next pass the album was no longer
 *   new, so a new artist stayed out of Library > Artists until a full pull.
 */
class ScanWatchRetryTest {
	@Test
	fun m6_aFailedSweepIsTriedAgainAtTheNextReading() {
		var state = ScanWatchState(lastScan = "t1", lastCount = 100)
		val readings = listOf(ScanReading(false, 100, "t2"), ScanReading(false, 100, "t2"), ScanReading(false, 100, "t2"))
		val swept = listOf(false, true, true)
		val due = readings.mapIndexed { i, r ->
			val (next, d) = scanWatchStep(state, r)
			state = scanWatchAfterSweep(state, next, d, swept = if (d) swept[i] else true)
			d
		}
		assertEquals(listOf(true, true, false), due, "the failed sweep, its retry 30 s on, then nothing new")
		assertEquals("t2", state.lastScan)
	}

	@Test
	fun m6_aScanEndOrACountMoveIsAlsoKeptUntilSwept() {
		val scanning = ScanWatchState(wasScanning = true, lastCount = 100, lastScan = "t1")
		val (next, due) = scanWatchStep(scanning, ScanReading(false, 101, "t2"))
		assertTrue(due)
		val kept = scanWatchAfterSweep(scanning, next, due, swept = false)
		assertTrue(scanWatchStep(kept, ScanReading(false, 101, "t2")).second, "seen again at the next reading")
	}

	@Test
	fun m6_theLastScanSeenIsTheLibraryOwners() {
		val a = "https://music.a|alice"
		val stored = scanSeenWrite(a, "2026-11-14T10:00:00Z")
		assertEquals("2026-11-14T10:00:00Z", scanSeenRead(stored, a))
		assertNull(scanSeenRead(stored, "https://music.b|alice"), "another server's is not this one's")
		assertNull(scanSeenRead(stored, "https://music.a|bob"), "nor another user's")
		assertNull(scanSeenRead("", a))
	}

	@Test
	fun m7_aFailedArtistReadIsOwedUntilOneSucceeds() = runBlocking {
		val debt = ArtistListDebt()
		var reads = 0
		var fail = true
		val read: suspend () -> Unit = { reads++; if (fail) error("getArtists: 503") }
		assertFalse(debt.settle(stale = true, read), "the landing's read fails")
		assertTrue(debt.owed)
		fail = false
		// The next pass pulls nothing new (or the next scan tick): the debt alone makes it read.
		assertTrue(debt.settle(stale = false, read))
		assertFalse(debt.owed)
		assertFalse(debt.settle(stale = false, read), "paid: nothing more")
		assertEquals(2, reads)
	}
}
