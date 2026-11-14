package app.worbus.domain.manager

/*
 * B-429 (the owner, 2026-11-14): the phone's incremental sync picks small library changes up by itself. A new
 * artist only appeared after a manual full sync (the first album of an artist downloaded, say), and an outside
 * cover change waited for the hourly full pull: the scan watch saw a scan only by `scanning` or `count`, and
 * Navidrome's watcher scan of a few changed files lasts milliseconds and moves neither. It moves `lastScan`.
 */

/** One `getScanStatus` answer. [lastScan] is Navidrome's `lastScan` as sent (an ISO time), null when absent. */
data class ScanReading(val scanning: Boolean, val count: Int, val lastScan: String?)

/**
 * What the watch remembers between two readings. [lastScan] starts as the value persisted at the last reading
 * the app acted on, so a scan that ran while the app was not running is seen at the first reading.
 */
data class ScanWatchState(val wasScanning: Boolean = false, val lastCount: Int? = null, val lastScan: String? = null)

/** The state after [reading], and whether a changed-albums sweep is due. */
internal fun scanWatchStep(prev: ScanWatchState, reading: ScanReading): Pair<ScanWatchState, Boolean> {
	val finished = prev.wasScanning && !reading.scanning
	val countMoved = prev.lastCount != null && prev.lastCount != reading.count && !reading.scanning
	// Never mid-scan: the scan's end is its own signal. A server that sends no lastScan keeps the two above.
	val scanMoved = !reading.scanning && reading.lastScan != null && prev.lastScan != null && reading.lastScan != prev.lastScan
	val next = ScanWatchState(
		wasScanning = reading.scanning,
		lastCount = if (!reading.scanning) reading.count else prev.lastCount,
		lastScan = if (!reading.scanning && reading.lastScan != null) reading.lastScan else prev.lastScan
	)
	return next to (finished || countMoved || scanMoved)
}

/**
 * Whether Room's artist list is stale after albums were pulled into it: an album new to Room, or one whose
 * artist Room does not list. [newAlbums]: how many of the pulled albums Room did not hold; [albumArtistIds]:
 * the pulled albums' artist ids; [heldArtistIds]: which of those Room lists.
 */
internal fun artistListStale(newAlbums: Int, albumArtistIds: Collection<String>, heldArtistIds: Set<String>): Boolean =
	newAlbums > 0 || albumArtistIds.any { it.isNotBlank() && it !in heldArtistIds }
