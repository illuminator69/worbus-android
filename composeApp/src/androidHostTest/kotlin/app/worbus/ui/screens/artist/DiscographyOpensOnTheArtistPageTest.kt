package app.worbus.ui.screens.artist

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertTrue

/*
 * B-405 (the owner's re-walk, 2026-11-13): wiring checks around ArtistPageMemoryTest (the view model needs
 * Room and Koin): the artist view model opens on the memory and reads Room only after its page settles,
 * the two artist pages say when they have settled, and the memory is one per process.
 */
class DiscographyOpensOnTheArtistPageTest {
	private val vm = sourceFile("src/commonMain/kotlin/app/worbus/ui/screens/artist/viewmodels/ArtistDetailViewModel.kt").readText()

	@Test
	fun aPageShownBeforeOpensOnItAndReadsRoomAfterItsSlide() {
		val init = vm.substringAfter("\tinit {").substringBefore("\n\t}\n")
		assertTrue("val seed = pageMemory[artistId]" in init, init)
		assertTrue("artistState.value = UiState.Success(seed.state)" in init, init)
		assertTrue("withTimeoutOrNull(SEEDED_READ_WAIT_MS) { settled.await() }" in init, init)
		assertTrue("pageMemory.put(artistId, snapshot)" in init, "every page writes what it shows")
	}

	@Test
	fun bothArtistPagesSayWhenTheyHaveSettled() {
		for (page in listOf("ArtistDiscographyScreen.kt", "ArtistDetailScreen.kt")) {
			val text = sourceFile("src/commonMain/kotlin/app/worbus/ui/screens/artist/$page").readText()
			assertTrue("OnPageSettled { viewModel.pageSettled() }" in text, page)
		}
		val di = sourceFile("src/commonMain/kotlin/app/worbus/di/ManagerModule.kt").readText()
		assertTrue("single { ArtistPageMemory { get<SessionManager>().sessionGeneration } }" in di, "one per process, per session (review L3)")
	}

	/*
	 * B-405 review L1, L2: a seeded page offered Favourite on a favourite until its deferred read, and in that
	 * window ran without what that read had worked out: a pending-sync row missed its stray album and opened
	 * the unowned page, and a library bump took the network branch for an artist the mirror holds.
	 */
	@Test
	fun aSeededPageHasWhatItsDeferredReadIsNeededFor() {
		val init = vm.substringAfter("\tinit {").substringBefore("\n\t}\n")
		for (line in listOf(
			"starred.value = seed.starred",
			"lbData = seed.lbData",
			"fromMirror = seed.fromMirror",
			"resolvedStrayAlbumIds.addAll(seed.strayAlbumIds)",
			"starred = star,",
			"strayAlbumIds = resolvedStrayAlbumIds.toSet()"
		)) assertTrue(line in init, line)
		// Declared above `init`, which writes it (a property declared below is still null there).
		assertTrue(vm.indexOf("private val resolvedStrayAlbumIds") < vm.indexOf("\tinit {"))
		// A library bump waits for the page's own Room read.
		val bumps = init.substringAfter("lbBotManager.libraryBumps.collect { bump ->")
		assertTrue(bumps.trimStart().startsWith("roomRead.await()"), bumps.take(80))
	}
}
