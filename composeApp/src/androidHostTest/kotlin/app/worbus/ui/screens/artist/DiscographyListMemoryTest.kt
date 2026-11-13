package app.worbus.ui.screens.artist

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.snapshots.Snapshot
import app.worbus.ui.screens.artist.viewmodels.DiscographyEntry
import app.worbus.ui.screens.artist.viewmodels.DiscographySection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/*
 * B-405 review (2026-11-13) M2, M3, m2, driven through Compose's runtime itself (a Recomposer on a frame
 * clock, a saveable-state registry saved and restored as a covered or rotated page's is; no UI):
 *
 * - M2: the lazy page's arrival gate was a plain `remember(filter)`, so a Back from an album (the covered
 *   entry is disposed, only saved state kept) or a rotation replayed every row's arrival.
 * - M3: rows a lazy list composes later claimed themselves when first composed, so each row scrolled into
 *   view sat blank 150 ms and faded in: the list flashed as it scrolled.
 * - m2: groups opened with Show all used to close when the filter changed, and stayed open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiscographyListMemoryTest {
	private class NoApplier : AbstractApplier<Unit>(Unit) {
		override fun insertTopDown(index: Int, instance: Unit) {}
		override fun insertBottomUp(index: Int, instance: Unit) {}
		override fun remove(index: Int, count: Int) {}
		override fun move(from: Int, to: Int, count: Int) {}
		override fun onClear() {}
	}

	/** ~300 releases in three groups, as the stand-in's artist: far more than a screen of rows. */
	private val view = DiscographyView(
		sections = listOf("album" to 120, "single" to 150, "ep" to 40).map { (type, n) ->
			DiscographySection(type, (1..n).map { DiscographyEntry(key = "$type-$it", title = "$type $it", year = "2000") })
		},
		flat = false, matched = 310, total = 310
	)
	private val aRowFarDown = "single-140"

	private class Page(val scope: CoroutineScope, val clock: BroadcastFrameClock, val recomposer: Recomposer) {
		val filter: MutableState<DiscographyFilter> = mutableStateOf(DiscographyFilter())
		var memory: DiscographyListMemory? = null

		suspend fun frame() {
			repeat(3) { yield() }
			clock.sendFrame(System.nanoTime())
			repeat(10) { yield() }
		}
	}

	/** Composes the page's list memory under [registry], as the page does, and returns the composition. */
	private fun Page.compose(registry: SaveableStateRegistry, view: DiscographyView, presentAtOpen: Boolean = false): Composition {
		val composition = Composition(NoApplier(), recomposer)
		composition.setContent {
			CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
				memory = rememberDiscographyListMemory(filter.value, view, presentAtOpen)
			}
		}
		return composition
	}

	private fun page(test: suspend Page.() -> Unit) = runBlocking<Unit> {
		val clock = BroadcastFrameClock()
		withContext(clock) {
			val recomposer = Recomposer(coroutineContext)
			val loop = launch { recomposer.runRecomposeAndApplyChanges() }
			Page(this, clock, recomposer).test()
			recomposer.cancel()
			loop.cancel()
		}
	}

	@Test
	fun aRowScrolledIntoViewLaterHasAlreadyArrived() = page {
		compose(SaveableStateRegistry(null) { true }, view)
		frame()
		// The lazy list composes this row only when it is scrolled to: it must not play an arrival then.
		assertFalse(memory!!.gate.claim(aRowFarDown), "a row far down was not claimed after the first frame")
	}

	@Test
	fun aBackOrARotationDoesNotReplayTheArrival() = page {
		val registry = SaveableStateRegistry(null) { true }
		val first = compose(registry, view)
		frame()
		memory!!.expanded = listOf("single")
		// The page is covered (an album pushed) or rotated: its composition goes, its saved state stays.
		val saved = registry.performSave()
		first.dispose()
		compose(SaveableStateRegistry(saved) { true }, view)
		assertFalse(memory!!.gate.claim("album-1"), "the restored gate still knows the rows arrived")
		assertEquals(listOf("single"), memory!!.expanded, "the open groups come back")
	}

	@Test
	fun aNewFilterPlaysTheArrivalAgainAndClosesTheGroups() = page {
		compose(SaveableStateRegistry(null) { true }, view)
		frame()
		val before = memory!!
		before.expanded = listOf("album", "single")
		filter.value = DiscographyFilter(sort = DiscographySort.NEWEST)
		Snapshot.sendApplyNotifications()
		frame()
		val after = memory!!
		assertNotSame(before.gate, after.gate, "a new filter, a new gate")
		assertTrue(after.expanded.isEmpty(), "every group closes: ${after.expanded}")
		frame()
		assertFalse(after.gate.claim(aRowFarDown), "and its rows are claimed after its first frame too")
	}

	@Test
	fun theKeysAreEveryRowOnce() {
		val keys = discographyArrivalKeys(view)
		assertEquals(310, keys.size)
		assertTrue(aRowFarDown in keys)
	}

	/*
	 * B-405 (the owner's re-walk, 2026-11-13): rows the page has on its first frame (it opened on the
	 * artist page's state) slid in with the page and then arrived again, one after another, after the
	 * slide: a stage of its own. They are there from the first frame; a later filter still plays the
	 * arrival; a page that had to wait for its rows still plays it too.
	 */
	@Test
	fun rowsThereOnThePagesFirstFrameDoNotArriveAgain() = page {
		compose(SaveableStateRegistry(null) { true }, view, presentAtOpen = true)
		assertFalse(memory!!.gate.claim("album-1"), "before any frame, a row on the first frame has arrived")
		assertFalse(memory!!.gate.claim(aRowFarDown), "and one far down")
	}

	@Test
	fun aNewFilterStillPlaysTheArrivalOnAPageOpenedWithItsRows() = page {
		compose(SaveableStateRegistry(null) { true }, view, presentAtOpen = true)
		frame()
		filter.value = DiscographyFilter(sort = DiscographySort.NEWEST)
		Snapshot.sendApplyNotifications()
		frame()
		assertTrue(memory!!.gate.claim("album-1"), "the new filter's rows arrive")
	}

	@Test
	fun rowsThatComeAfterThePageStillArrive() = page {
		compose(SaveableStateRegistry(null) { true }, view, presentAtOpen = false)
		assertTrue(memory!!.gate.claim("album-1"), "a page that waited for its rows: they arrive")
	}
}
