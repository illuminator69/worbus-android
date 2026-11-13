package app.worbus.ui.screens.artist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.worbus.ui.util.ArrivalGate
import app.worbus.ui.util.ClaimAfterFirstFrame

/**
 * What the lazy discography page remembers for its list (B-405, review M2, M3, m2): which rows have
 * arrived and which groups Show all has opened.
 *
 * - Saved with the page's entry, so a Back from an album, or a rotation, does not replay the rows'
 *   arrival (M2: a plain `remember` lost it whenever the page was covered).
 * - Started over by a new filter (chip, sort, ownership): the rows' arrival plays again (round 2's note)
 *   and every group closes, as `key(filter)` did around the eager list (m2).
 * - Once the list's first frame is up every row has arrived ([ClaimAfterFirstFrame], as Home's shelves and
 *   Search do), so a row scrolled into view later is simply there, never a late fade (M3: "lists must not
 *   flash").
 */
class DiscographyListMemory(val gate: ArrivalGate, expanded: MutableState<List<String>>) {
	/** The groups Show all has opened, by type. */
	var expanded by expanded
}

/** Every row key [view] lists, in order, once each ([ClaimAfterFirstFrame]'s list). */
fun discographyArrivalKeys(view: DiscographyView): List<String> =
	view.sections.flatMap { section -> section.entries.map { it.key } }.distinct()

@Composable
fun rememberDiscographyListMemory(
	filter: DiscographyFilter,
	view: DiscographyView,
	/**
	 * B-405: the page had its rows on its first frame (opened on the artist page's state). They are then
	 * there as the page slides in, not arriving one after another once it has; a filter changed later still
	 * plays the arrival, as before.
	 */
	presentAtOpen: Boolean = false
): DiscographyListMemory {
	val openingFilter = remember { filter }
	val gate = rememberSaveable(filter, saver = listSaver(save = { it.toSaved() }, restore = { ArrivalGate.fromSaved(it) })) {
		ArrivalGate().also { if (presentAtOpen && filter == openingFilter) it.claimAll(discographyArrivalKeys(view)) }
	}
	val expanded = rememberSaveable(filter) { mutableStateOf(listOf<String>()) }
	ClaimAfterFirstFrame(gate, remember(view) { discographyArrivalKeys(view) })
	return remember(gate, expanded) { DiscographyListMemory(gate, expanded) }
}
