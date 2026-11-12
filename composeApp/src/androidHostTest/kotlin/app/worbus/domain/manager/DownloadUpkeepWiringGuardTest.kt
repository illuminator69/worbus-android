package app.worbus.domain.manager

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertTrue

/*
 * Q-165: where DownloadUpkeep meets the app. Its rules run in DownloadUpkeepTest on fakes; these hold
 * the seams the fakes stand for, which need Room, Koin and a server to run: a pull reports itself, a
 * complete pull with its list, the upkeep follows the reports, a first download records what it was
 * made from, and a re-fetch is the atomic swap.
 */
class DownloadUpkeepWiringGuardTest {
	private val sync = sourceFile("src/commonMain/kotlin/app/worbus/domain/manager/SyncManager.kt").readText()
	private val manager = sourceFile("src/commonMain/kotlin/app/worbus/domain/manager/DownloadManager.kt").readText()
	private val module = sourceFile("src/commonMain/kotlin/app/worbus/di/ManagerModule.kt").readText()

	@Test
	fun aFullPullReportsItsListOnlyWhenComplete() {
		val full = sync.substringAfter("private suspend fun fullPull(").substringBefore("private suspend fun processQueue")
		assertTrue("_pulls.tryEmit(PullSeen(" in full, "the full pull reports itself:\n$full")
		val repo = sourceFile("src/commonMain/kotlin/app/worbus/domain/repositories/DbRepository.kt").readText()
		val everything = repo.substringAfter("suspend fun syncEverything(").substringBefore("data class LibrarySync")
		assertTrue("if (libraryResult.complete) validSongIds" in everything.replace("\n", " ").replace(Regex("\\s+"), " ") ||
			"listed = if (libraryResult.complete)" in everything, "the list is a complete pull's only:\n$everything")
	}

	@Test
	fun aChangedAlbumsSweepReportsWithNoList() {
		val changed = sync.substringAfter("private suspend fun syncChangedAlbums(").substringBefore("/**")
		assertTrue("_pulls.tryEmit(PullSeen(listed = null))" in changed, changed)
	}

	@Test
	fun theUpkeepFollowsThePullsAndAsksNavidromeBySongId() {
		val upkeep = module.substringAfter("DownloadUpkeep(").substringBefore("\n\t}\n")
		assertTrue("createdAtStart = true) {\n\t\tDownloadUpkeep(" in module, "made at start:\n$module")
		assertTrue("getSong(" in upkeep && "songAnswerOf(" in upkeep, "asks getSong and reads it as songAnswerOf:\n$upkeep")
		assertTrue(".pulls" in upkeep, "follows SyncManager.pulls:\n$upkeep")
		assertTrue("isOnline.value" in upkeep, "online is the app's own (Navidrome answers, Q-165):\n$upkeep")
	}

	@Test
	fun aDownloadRecordsWhatItWasMadeFrom() {
		val first = manager.substringAfter("private suspend fun downloadAudioFile(").substringBefore("private fun now()")
		assertTrue("origins.recordFetched(" in first, "a finished download records its origin:\n$first")
	}

	@Test
	fun aRefetchIsTheSwap() {
		val refetch = manager.substringAfter("override fun refetch(").substringBefore("override suspend fun remove(")
		assertTrue("refetchOver(" in refetch && "fetchVerified(" in refetch, refetch)
		assertTrue("awaitDownloadConstraints()" in refetch, "Wi-Fi only and charging only hold a re-fetch too:\n$refetch")
		assertTrue("insertDownload(DownloadEntity(" !in refetch && "DownloadStatus.QUEUED" !in refetch,
			"the row stays DOWNLOADED on the old copy while the new one comes:\n$refetch")
	}

	// ---- the follow-up ---- //

	@Test
	fun theUpkeepKnowsWhoseItIsAndWhatPlays() {
		val upkeep = module.substringAfter("DownloadUpkeep(").substringBefore("\n\t}\n")
		assertTrue("owner = { get<SessionManager>().downloadOwner() }" in upkeep, upkeep)
		assertTrue("memory = " in upkeep && "inUse = " in upkeep && "songsAt = " in upkeep, upkeep)
		assertTrue("localUiState" in upkeep, "the LOCAL player's queue is what reads the files:\n$upkeep")
		val session = sourceFile("src/commonMain/kotlin/app/worbus/domain/manager/SessionManager.kt").readText()
		assertTrue("fun downloadOwner(): DownloadOwner?" in session)
		val upkeepFile = sourceFile("src/commonMain/kotlin/app/worbus/domain/manager/DownloadUpkeep.kt").readText()
		val record = upkeepFile.substringAfter("suspend fun recordFetched(").substringBefore("\n\t}\n")
		assertTrue("owner()" in record && "server = " in record && "account = " in record, "a fetched file is recorded with its owner:\n$record")
	}

	@Test
	fun aMoveIsTheRenameAndStartSweepsScratchFirst() {
		val move = manager.substringAfter("override suspend fun move(").substringBefore("\n\t}\n")
		assertTrue("moveDownload(" in move, move)
		val init = manager.substringAfter("init {").substringBefore("private suspend fun backfillMissingFileSizes")
		assertTrue(init.indexOf("sweepScratch") in 0 until init.indexOf("reconcileInterruptedDownloads()"),
			"the sweep runs before anything is started again:\n$init")
	}
}
