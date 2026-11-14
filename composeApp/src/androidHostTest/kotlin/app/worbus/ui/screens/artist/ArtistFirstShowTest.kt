package app.worbus.ui.screens.artist

import app.worbus.sourceFile
import app.worbus.ui.screens.artist.viewmodels.FirstShowHold
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.measureTime

/*
 * B-446 (the owner, 2026-11-14): the first opening of an artist page stuttered and a cached one did not. Measured
 * on the VM AVD (release, speed-profile, simpleperf on a profileable copy): with nothing in ArtistPageMemory the
 * page's Room read landed during the slide, and the whole page was composed in one frame of it (60-250 ms). A
 * page opened on the memory composes in its first frame, before anything moves. Now a page with nothing to open
 * on shows its read once its slide has ended (FirstShowHold).
 */
class ArtistFirstShowTest {
	private val vm = sourceFile("src/commonMain/kotlin/app/worbus/ui/screens/artist/viewmodels/ArtistDetailViewModel.kt").readText()

	@Test
	fun anArtistPageWithNothingToOpenOnShowsItsReadOnceItsSlideHasEnded() {
		val init = vm.substringAfter("\tinit {").substringBefore("\n\t}\n")
		val unseeded = init.substringAfter("} else {").substringBefore("}")
		assertTrue("firstShow.hold()" in unseeded, unseeded)
		assertTrue(unseeded.indexOf("firstShow.hold()") < unseeded.indexOf("loadArtistData()"), unseeded)
		// The gate is created from the page's settle, above `init` (a property declared below is null there).
		assertTrue("private val firstShow = FirstShowHold(settled, SEEDED_READ_WAIT_MS)" in vm)
		assertTrue(vm.indexOf("private val firstShow") < vm.indexOf("\tinit {"))
		// In the read: waited for right before the first Success the read publishes.
		val read = vm.substringAfter("private fun loadArtistData() {").substringBefore("observeMirror()")
		val wait = read.indexOf("firstShow.beforeFirstShow()")
		assertTrue(wait >= 0, "loadArtistData does not wait for the slide")
		assertTrue(wait < read.indexOf("artistState.value = UiState.Success("), "the first Success is published before the wait")
	}

	@Test
	fun aHeldFirstShowWaitsForTheSlideOnce() = runBlocking {
		val settled = CompletableDeferred<Unit>()
		val hold = FirstShowHold(settled, waitMs = 5_000)
		hold.hold()
		val first = async { hold.beforeFirstShow() }
		repeat(5) { yield() }
		delay(50)
		assertFalse(first.isCompleted, "shown before the slide ended")
		settled.complete(Unit)
		first.await()
		// After the first show nothing waits.
		val later = measureTime { hold.beforeFirstShow() }
		assertTrue(later.inWholeMilliseconds < 50)
	}

	@Test
	fun aPageOpenedOnItsMemoryOrSettledAlreadyDoesNotWait() = runBlocking {
		val never = CompletableDeferred<Unit>()
		val seeded = FirstShowHold(never, waitMs = 5_000)
		assertTrue(measureTime { seeded.beforeFirstShow() }.inWholeMilliseconds < 50, "a page not held waited")
		val done = CompletableDeferred(Unit)
		val held = FirstShowHold(done, waitMs = 5_000).also { it.hold() }
		assertTrue(measureTime { held.beforeFirstShow() }.inWholeMilliseconds < 50, "a settled page waited")
	}

	@Test
	fun aSlideThatNeverEndsHoldsTheFirstShowOnlyUpToItsBound() = runBlocking {
		val hold = FirstShowHold(CompletableDeferred(), waitMs = 120).also { it.hold() }
		val took = measureTime { hold.beforeFirstShow() }.inWholeMilliseconds
		assertTrue(took in 100..2_000, "waited $took ms")
	}
}
