package app.worbus.domain.manager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.info_status_idle
import org.jetbrains.compose.resources.StringResource
import app.worbus.data.database.dao.AlbumDao
import app.worbus.data.database.entities.SyncActionType
import app.worbus.domain.repositories.DbRepository
import app.worbus.util.Logger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

data class SyncState(
	val isSyncing: Boolean = false,
	val progress: Float = 0f,
	val message: StringResource = Res.string.info_status_idle,
	/**
	 * True when the last full-library sync attempt failed at the top level (e.g. server unreachable),
	 * so the UI can say "showing cached library, sync failed" instead of implying the library is
	 * empty/broken. Cleared the moment a sync succeeds or a new one starts.
	 */
	val lastSyncFailed: Boolean = false
)

class SyncManager(
	private val repository: DbRepository,
	/** B-373: the offline write queue, in `writes.db` (see WriteQueue.kt for its rules). */
	private val writeQueue: WriteQueue,
	private val albumDao: AlbumDao,
	private val connectivityManager: ConnectivityManager,
	private val sessionManager: SessionManager,
	private val preferenceManager: PreferenceManager,
	private val lbBotManager: LbBotManager,
	private val lbIndexSync: LbIndexSync,
	private val foregroundController: DownloadForegroundController
) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private var syncJob: Job? = null
	private val syncMutex = Mutex()

	private val fullSyncThreshold = 1.hours

	private companion object {
		/** An `albumIndexed` with no ids: Navidrome has it, only which album is unknown. */
		val LB_BOT_NEWEST_DEBOUNCE_INDEXED = 2.seconds

		/**
		 * An `albumPlaced` with no `albumIndexed` behind it (an lb-bot predating the event):
		 * give Navidrome's quick scan time to pick the folder up before looking.
		 */
		val LB_BOT_NEWEST_DEBOUNCE_PLACED = 20.seconds

		/** Feishin showed the real cover about ten seconds after landing; allow for slower. */
		val ARTWORK_RESYNC_DELAYS = listOf(15.seconds, 45.seconds)

		/** How often to ask Navidrome whether a scan ran. One tiny request. */
		val SCAN_WATCH_INTERVAL = 30.seconds

		/** After a scan ends: long enough for Navidrome to settle the album's cover too. */
		val SCAN_SETTLE_DELAY = 5.seconds
	}

	private val _syncState = MutableStateFlow(SyncState())
	val syncState = _syncState.asStateFlow()

	/**
	 * Q-165: each library pull that finished, for [DownloadUpkeep]: a full pull (with the song ids it
	 * listed when it was complete and stable) and a changed-albums sweep that moved something.
	 */
	private val _pulls = MutableSharedFlow<PullSeen>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
	val pulls: SharedFlow<PullSeen> = _pulls.asSharedFlow()

	/**
	 * Pull a landed album into Room the moment Navidrome has it.
	 *
	 * This used to answer every fill with a FULL library pull (a `getAlbum` per album in
	 * the library) behind a 90 s debounce and a 5-minute floor — minutes between Navidrome
	 * showing an album and this app showing it. lb-bot's verifier now announces
	 * `albumIndexed` with the Navidrome album ids once the scan has picked the files up, so
	 * a landing costs one request per album and happens as soon as the frame arrives.
	 *
	 * Events are handled one at a time (the collector is sequential), which is what keeps
	 * a burst of fills from racing each other's upserts. Removals are still the hourly full
	 * sync's job.
	 */
	private var lbBotNewestJob: Job? = null

	private fun observeLbBotFills() {
		scope.launch {
			lbBotManager.libraryEvents.collect { event ->
				// A discography scan changes lb-bot's index only; the artist page gets it
				// from the index mirror (or, for an artist not mirrored yet, re-reads it off
				// its own filtered bump). Nothing in Navidrome moved.
				if (event.event == EVENT_ARTIST_SCANNED) return@collect
				if (event.ndAlbumIds.isNotEmpty()) {
					repository.syncAlbumsById(event.ndAlbumIds)
						.onSuccess {
							Logger.i("SyncManager", "lb-bot landing synced: ${event.ndAlbumIds}")
							lbBotManager.onLocalLibrarySynced(event.ndAlbumIds)
							scheduleArtworkResync(event.ndAlbumIds)
						}
						.onFailure {
							Logger.w("SyncManager", "lb-bot landing sync failed, falling back", it)
							scheduleNewestSync(LB_BOT_NEWEST_DEBOUNCE_INDEXED)
						}
				} else {
					scheduleNewestSync(
						if (event.event == EVENT_INDEXED) LB_BOT_NEWEST_DEBOUNCE_INDEXED
						else LB_BOT_NEWEST_DEBOUNCE_PLACED
					)
				}
			}
		}
	}

	/**
	 * Pull a landed album again once Navidrome has had time to find its artwork.
	 *
	 * The landing sync runs the moment the scan indexes the tracks, which is often before
	 * Navidrome has resolved the cover — so the album arrived with no art, and the placeholder
	 * was cached under that cover id for good. Navidrome's cover id changes when the art does,
	 * so re-reading the album gives every cover view a new key and the real image loads.
	 * Idempotent upserts; only the ids from the event are touched.
	 */
	private fun scheduleArtworkResync(ids: List<String>) {
		scope.launch {
			for (wait in ARTWORK_RESYNC_DELAYS) {
				delay(wait)
				repository.syncAlbumsById(ids)
					.onSuccess { lbBotManager.onLocalLibrarySynced(ids) }
					.onFailure { Logger.w("SyncManager", "artwork re-sync failed for $ids", it) }
			}
		}
	}

	/**
	 * Debounced: a burst of id-less events collapses into one walk.
	 *
	 * A changed-albums sweep, not a `newest` one: an id-less event is as likely to be a gap
	 * fill (an album that already existed and gained tracks) as a new album, and `newest`
	 * can't see the first kind at all.
	 */
	private fun scheduleNewestSync(after: kotlin.time.Duration) {
		lbBotNewestJob?.cancel()
		lbBotNewestJob = scope.launch {
			delay(after)
			syncChangedAlbums("lb-bot event")
		}
	}

	private val changedSyncMutex = Mutex()

	/** A changed-albums sweep; false when it failed (a full pull under way counts as done: it re-reads all). */
	private suspend fun syncChangedAlbums(why: String): Boolean {
		// Never alongside the full pull, which re-reads everything anyway.
		if (syncMutex.isLocked) return true
		return changedSyncMutex.withLock {
			repository.syncChangedAlbums()
				.onSuccess { count ->
					if (count > 0) {
						Logger.i("SyncManager", "$why: synced $count changed album(s)")
						lbBotManager.onLocalLibrarySynced()
						// Q-165: songs may have changed; no list, so nothing is judged gone from it.
						_pulls.tryEmit(PullSeen(listed = null))
					}
				}
				.onFailure { Logger.w("SyncManager", "$why: changed-album sync failed", it) }
				.isSuccess
		}
	}

	/**
	 * Notice Navidrome scans that nobody told us about.
	 *
	 * Every way music reaches the library ends in a Navidrome scan, whether or not lb-bot
	 * or the hub announced it: lb-bot's own page, a fill started from Worbus for desktop, a folder
	 * matched by hand, a scheduled scan. Watching `getScanStatus` catches all of them. A
	 * scan short enough to fall between two polls still changes `count`, which is why
	 * either signal triggers the sweep.
	 */
	private var scanWatchJob: Job? = null

	private fun startScanWatch() {
		if (scanWatchJob?.isActive == true) return
		scanWatchJob = scope.launch {
			// B-429: from the lastScan last acted on, so a scan that ran while the app was closed is seen at the
			// first reading (at once, not 30 s in).
			var state = ScanWatchState(lastScan = preferenceManager.lastScanSeen.ifBlank { null })
			var first = true
			while (isActive) {
				if (!first) delay(SCAN_WATCH_INTERVAL)
				first = false
				if (!connectivityManager.isOnline.value) continue
				val reading = try {
					sessionManager.fetchScanStatus()
				} catch (e: Exception) {
					if (e is kotlin.coroutines.cancellation.CancellationException) throw e
					continue
				}
				val (next, due) = scanWatchStep(state, reading)
				state = next
				if (due) {
					delay(SCAN_SETTLE_DELAY)
					// Kept for the next start only once acted on: a sweep that failed is tried again then.
					if (!syncChangedAlbums("Navidrome scan")) continue
				}
				next.lastScan?.let { preferenceManager.lastScanSeen = it }
			}
		}
	}

	init {
		repository.clearStaleSyncNotification()
		observeLbBotFills()
		scope.launch {
			connectivityManager.isOnline.collect { isOnline ->
				if (!syncMutex.isLocked && isOnline) {
					syncMutex.withLock { processQueue() }
				}
			}
		}
		// B-098: a logout stops the cycle; the login starts it again once its own pull is done
		// (LoginManager), so the two never pull the library side by side.
		scope.launch {
			sessionManager.isLoggedIn.collect { loggedIn ->
				if (!loggedIn) stopPeriodicSync()
			}
		}
	}

	/**
	 * The 15-minute cycle and the scan watch. Called at start (ManagerModule) and after a login's
	 * pull; does nothing without a session (B-098): a logged-out launch used to pull twice against
	 * an empty address and put up the sync notification before anyone had logged in.
	 */
	fun startPeriodicSync() {
		if (!periodicSyncAllowed(sessionManager.isLoggedIn.value)) {
			Logger.i("SyncManager", "No session: periodic sync not started.")
			return
		}
		Logger.i("SyncManager", "Starting periodic sync cycle.")
		if (syncJob?.isActive == true) return

		// The first pass runs at once, and pulls the whole library when Room holds none or no
		// pull has ever finished (fullPullDue): what the separate "haven't synced before" launch
		// that used to sit here was for.
		syncJob = scope.launch {
			while (isActive) {
				// The lb-bot index mirror's backstop pull (the hub's `welcome` and `index` frames
				// are the fast paths). Fire-and-forget onto its own single worker, BEFORE the
				// library cycle, so a long full pull never delays it and it never holds syncMutex.
				lbIndexSync.requestSync("periodic")
				runSyncCycle()
				delay(15.minutes)
			}
		}
		startScanWatch()
	}

	/**
	 * The login's library pull (B-100, B-109). It used to go straight to the repository: outside
	 * the foreground hold, without stamping `lastFullSyncTime` (so the next launch pulled the
	 * whole library again) and without touching [syncState] (so a failure left over from before
	 * the login stayed on the Library page). It is the full pull of [runSyncCycle], under the same
	 * lock, with the login page's progress reported to [onProgress] as well.
	 */
	suspend fun firstPull(onProgress: (Float, StringResource) -> Unit): Result<Unit> =
		syncMutex.withLock { fullPull(Clock.System.now(), onProgress) }

	fun triggerManualSync() {
		scope.launch {
			preferenceManager.lastFullSyncTime = 0
			runSyncCycle()
		}
	}

	/** Logout (B-373): writes not yet sent belong to the account that made them, not the next one. */
	suspend fun dropPendingWrites() = writeQueue.clear()

	fun stopPeriodicSync() {
		syncJob?.cancel()
		scanWatchJob?.cancel()
		_syncState.value = SyncState(isSyncing = false)
	}

	/**
	 * Queue a write and send what the queue holds. [time] is when the user did it: a scrobble goes
	 * out with it (rule 4), so a scrobble's caller passes the moment of the listen, not "now" after
	 * a failed attempt; a star's is the star's own time, which a pull's overlay writes back. The pass waits for the lock instead of giving up while a pull holds it: a write made
	 * during a full pull used to wait for the next 15-minute cycle.
	 */
	/**
	 * Queue a write and return once it is in the queue, then send what the queue holds in the
	 * background. For a caller that writes the same change to Room: it calls this first (review
	 * minor 6), so a pull's batch either sees the write in its overlay or has finished before the
	 * caller's own Room write lands ([WriteQueue.withOverlay]).
	 */
	suspend fun enqueue(actionType: SyncActionType, itemId: String, time: Instant = Clock.System.now()) {
		writeQueue.enqueue(actionType, itemId, time)
		scope.launch { syncMutex.withLock { processQueue() } }
	}

	fun enqueueAction(actionType: SyncActionType, itemId: String, time: Instant = Clock.System.now()) {
		scope.launch {
			writeQueue.enqueue(actionType, itemId, time)
			syncMutex.withLock { processQueue() }
		}
	}

	private suspend fun runSyncCycle() {
		// A logout while the cycle slept: nothing to sync for (B-098).
		if (!sessionManager.isLoggedIn.value) return
		syncMutex.withLock {
			processQueue()

			val currentTime = Clock.System.now()
			if (fullPullDue(
					loggedIn = sessionManager.isLoggedIn.value,
					albumCount = albumDao.getAlbumCount(),
					lastFullMs = preferenceManager.lastFullSyncTime,
					nowMs = currentTime.toEpochMilliseconds(),
					thresholdMs = fullSyncThreshold.inWholeMilliseconds
				)
			) {
				fullPull(currentTime, onProgress = null)
			}
		}
	}

	/** One whole-library pull. The caller holds [syncMutex]. */
	private suspend fun fullPull(
		startedAt: Instant,
		onProgress: ((Float, StringResource) -> Unit)?
	): Result<Unit> {
		Logger.i("SyncManager", "Starting full library pull...")

		// A new attempt clears any prior failure flag while it runs.
		_syncState.update { it.fullPullStarted() }
		// Follow-up 2 (minor 3): the library as last cached, before this pull changes it.
		val songsBefore = try { repository.cachedSongCount() } catch (e: Exception) { if (e is kotlin.coroutines.cancellation.CancellationException) throw e; null }

		// Held in the foreground for the length of the pull. Without it the process is
		// frozen as soon as the app is left, and the pull sat half-done under a
		// notification that still said it was running.
		foregroundController.start(ForegroundReason.SYNC)
		val result = try {
			repository.syncEverything { progress, message ->
				_syncState.update {
					it.copy(isSyncing = true, progress = progress, message = message)
				}
				onProgress?.invoke(progress, message)
			}
		} finally {
			foregroundController.stop(ForegroundReason.SYNC)
		}

		if (result.isSuccess) {
			preferenceManager.lastFullSyncTime = startedAt.toEpochMilliseconds()
			Logger.i("SyncManager", "Full library sync complete.")
			// Q-165: what the pull listed when it was complete and stable, else null.
			_pulls.tryEmit(PullSeen(result.getOrNull(), songsBefore))
		} else {
			// With the cause. Without it the log said a sync failed and nothing about why.
			Logger.e("SyncManager", "Full library sync failed; keeping cached library.", result.exceptionOrNull())
		}

		_syncState.update { it.fullPullEnded(result.isSuccess) }
		return result.map { }
	}

	private suspend fun processQueue() {
		// Offline (or "Offline mode" forced): nothing is sent; the queue is drained when it flips back.
		if (!connectivityManager.isOnline.value) return
		writeQueue.drain { write -> sendWrite(write) }
	}

	private suspend fun sendWrite(write: QueuedWrite) {
		when (write.type) {
			SyncActionType.STAR -> sessionManager.api.star(write.itemId)
			SyncActionType.UNSTAR -> sessionManager.api.unstar(write.itemId)
			SyncActionType.DELETE_PLAYLIST -> sessionManager.api.deletePlaylist(write.itemId)
			SyncActionType.SCROBBLE -> sessionManager.api.scrobble(
				write.itemId,
				submission = true,
				time = write.time // i'm sorry for you if this defaults to "now"
			)

			SyncActionType.STAR_0 -> sessionManager.api.setRating(write.itemId, 0)
			SyncActionType.STAR_1 -> sessionManager.api.setRating(write.itemId, 1)
			SyncActionType.STAR_2 -> sessionManager.api.setRating(write.itemId, 2)
			SyncActionType.STAR_3 -> sessionManager.api.setRating(write.itemId, 3)
			SyncActionType.STAR_4 -> sessionManager.api.setRating(write.itemId, 4)
			SyncActionType.STAR_5 -> sessionManager.api.setRating(write.itemId, 5)
		}
	}
}
