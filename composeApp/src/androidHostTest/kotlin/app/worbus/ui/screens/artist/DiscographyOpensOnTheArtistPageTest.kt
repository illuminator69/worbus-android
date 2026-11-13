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
		assertTrue("singleOf(::ArtistPageMemory)" in di)
	}
}
