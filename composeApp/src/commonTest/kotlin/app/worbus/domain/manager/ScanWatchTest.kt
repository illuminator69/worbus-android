package app.worbus.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * B-429: a scan Navidrome's watcher runs on a few changed files lasts milliseconds and moves neither `scanning`
 * (between two 30 s readings) nor `count` (a cover replaced, a file retagged); it moves `lastScan`. And albums
 * pulled into Room by id did not bring their artist with them: a new artist appeared only after a full sync.
 */
class ScanWatchTest {
	private fun steps(start: ScanWatchState, vararg readings: ScanReading): List<Boolean> {
		var s = start
		return readings.map { r -> scanWatchStep(s, r).also { s = it.first }.second }
	}

	@Test
	fun aWatcherScanSeenOnlyByLastScanStartsASweep() {
		val due = steps(
			ScanWatchState(),
			ScanReading(false, 100, "2026-11-14T10:00:00Z"),
			ScanReading(false, 100, "2026-11-14T10:00:00Z"),
			ScanReading(false, 100, "2026-11-14T10:00:31Z")
		)
		assertEquals(listOf(false, false, true), due)
	}

	@Test
	fun aScanWhileTheAppWasClosedIsSeenAtTheFirstReading() {
		// The watch starts from the lastScan the app last acted on (persisted).
		val due = steps(ScanWatchState(lastScan = "2026-11-14T09:00:00Z"), ScanReading(false, 100, "2026-11-14T10:00:00Z"))
		assertEquals(listOf(true), due)
		assertEquals(listOf(false), steps(ScanWatchState(lastScan = "2026-11-14T10:00:00Z"), ScanReading(false, 100, "2026-11-14T10:00:00Z")))
	}

	@Test
	fun aRunningScanWaitsForItsEndAndAServerWithoutLastScanKeepsTheOldSignals() {
		val due = steps(
			ScanWatchState(lastScan = "a"),
			ScanReading(true, 100, "b"),
			ScanReading(false, 120, "c")
		)
		assertEquals(listOf(false, true), due, "never mid-scan; once at its end")
		assertEquals(listOf(false, true), steps(ScanWatchState(), ScanReading(false, 1, null), ScanReading(false, 2, null)))
		assertEquals(listOf(false, false), steps(ScanWatchState(), ScanReading(false, 1, null), ScanReading(false, 1, null)))
	}

	@Test
	fun theLastScanActedOnIsRemembered() {
		val (next, _) = scanWatchStep(ScanWatchState(), ScanReading(false, 1, "t1"))
		assertEquals("t1", next.lastScan)
		val (mid, _) = scanWatchStep(next, ScanReading(true, 1, "t2"))
		assertEquals("t1", mid.lastScan, "not while a scan runs")
	}

	@Test
	fun aNewAlbumOrAnArtistRoomDoesNotListMakesTheArtistListStale() {
		assertTrue(artistListStale(newAlbums = 0, albumArtistIds = listOf("ar-new"), heldArtistIds = emptySet()))
		assertTrue(artistListStale(newAlbums = 1, albumArtistIds = listOf("ar-1"), heldArtistIds = setOf("ar-1")), "its album count moved")
		assertFalse(artistListStale(newAlbums = 0, albumArtistIds = listOf("ar-1"), heldArtistIds = setOf("ar-1")), "a changed cover alone")
		assertFalse(artistListStale(newAlbums = 0, albumArtistIds = listOf(""), heldArtistIds = emptySet()), "no artist named")
	}
}
