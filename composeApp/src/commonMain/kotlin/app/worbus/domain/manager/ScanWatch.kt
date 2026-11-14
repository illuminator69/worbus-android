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

/**
 * The watch's state after a reading [next] made a sweep [due] and the sweep [swept] or failed. Stage 2 review M6: a
 * failed sweep keeps the state from before the reading ([prev]), so the next reading (30 s on, not a busy loop) sees
 * the same change and tries again. The new state was taken before the sweep, so a failed one was tried again only at
 * the next start or the hourly pull.
 */
internal fun scanWatchAfterSweep(prev: ScanWatchState, next: ScanWatchState, due: Boolean, swept: Boolean): ScanWatchState =
	if (due && !swept) prev else next

/**
 * Stage 2 review M6: the [lastScanSeen][PreferenceManager.lastScanSeen] for [owner] (whose library: server and user,
 * `scanSeenOwner`). One value for every server and user meant the first reading after a login elsewhere always
 * differed: a whole-library sweep right after the login's own full pull. A value stored for another owner, or in
 * this branch's first format (no owner), is none.
 */
internal fun scanSeenRead(stored: String, owner: String): String? {
	val cut = stored.lastIndexOf(SCAN_SEEN_SEP)
	if (cut < 0 || owner.isEmpty() || stored.substring(0, cut) != owner) return null
	return stored.substring(cut + 1).ifBlank { null }
}

/** What [lastScanSeen][PreferenceManager.lastScanSeen] stores for [owner]'s [lastScan]. */
internal fun scanSeenWrite(owner: String, lastScan: String): String = "$owner$SCAN_SEEN_SEP$lastScan"

/** Whose library a `lastScan` is about: the server and the user ("" without a session). */
internal fun scanSeenOwner(server: String?, user: String?): String =
	if (server.isNullOrBlank() || user.isNullOrBlank()) "" else "${server.trimEnd('/')}\n$user"

private const val SCAN_SEEN_SEP = '\u0000'

/**
 * Stage 2 review M7: whether Room's artist list is owed a re-read ([owed]), and the re-read. A `getArtists` that
 * failed after a landing's albums were written was not tried again: on the next pass the album was no longer new, so
 * the new artist stayed out of Library > Artists until a full pull. Now a failure is a debt, paid by the next pass
 * that may read ([settle], each scan tick and each targeted sync), whether or not its own albums make the list stale.
 * Held in memory: a process that dies owing it leaves the artist to the next full pull.
 */
internal class ArtistListDebt {
	@kotlin.concurrent.Volatile
	private var owes = false
	val owed: Boolean get() = owes

	/** True when [read] ran and succeeded. A failure is reported to [onFailure], kept as owed, and not thrown. */
	suspend fun settle(stale: Boolean, read: suspend () -> Unit, onFailure: (Exception) -> Unit = {}): Boolean {
		if (!stale && !owes) return false
		owes = true
		return try {
			read()
			owes = false
			true
		} catch (e: Exception) {
			if (e is kotlin.coroutines.cancellation.CancellationException) throw e
			onFailure(e)
			false
		}
	}

	/** A full pull read the whole list: nothing is owed. */
	fun paid() {
		owes = false
	}
}
