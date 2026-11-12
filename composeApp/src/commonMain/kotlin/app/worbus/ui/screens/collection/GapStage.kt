package app.worbus.ui.screens.collection

import app.worbus.domain.manager.LbBotManager
import app.worbus.domain.manager.LbDownloads
import app.worbus.domain.manager.LbFillEntry
import app.worbus.domain.manager.LbGap
import app.worbus.domain.manager.LbGapSource
import app.worbus.domain.manager.LbGapTrack
import app.worbus.domain.manager.gapReason
import app.worbus.ui.screens.external.RequestStep
import app.worbus.ui.screens.external.preselectedSource
import app.worbus.ui.screens.external.requestSteps

/*
 * navi-connect P4, round 2: the album page's gaps box, which retired `GapFillSheet` (the owner's
 * storyboard D, "the gaps box does everything"). The "N tracks missing" line unfolds to the missing
 * songs; Fill gaps turns the same box into the ranked sources for them; a request turns it into the
 * steps; the finish folds it to a result row and then to nothing. A failure keeps it open, saying why.
 *
 * Which of those it shows is one function of what is known (lb-bot's gap record, this device's
 * ledger row for the group, where the user has the box, a tap in flight), so the box cannot be in two
 * states at once and every precedence is a test (`GapStageTest`). As with the request box, a transfer
 * in flight is the truth whatever else is set: it may have been started on another client.
 */

/** Where the user has put the box. */
enum class GapView { FOLDED, TRACKS, SOURCES }

/** How long the result row stays before the box folds away. */
const val GAP_RESULT_HOLD_MS = 6_000L

data class GapInput(
	/** lb-bot's record for the group; null until the first read lands. */
	val gap: LbGap?,
	/** The index mirror's count, for the folded line before [gap] lands. */
	val mirroredMissing: Int,
	/** This device's ledger row for the group (keyed by the group id), if any. */
	val fill: LbFillEntry?,
	val view: GapView,
	/** A fetch lb-bot accepted that its status does not show yet. */
	val committed: Boolean,
	/** A search tap whose answer, or the poll that shows it running, has not come yet. */
	val searchAsked: Boolean,
	val now: Long,
	/**
	 * Worbus P1: lb-bot can download ([LbDownloads.isOn]). Off, the box keeps what lb-bot detected (the
	 * line and the missing songs) and loses every way to fetch them: no sources view, no failed fill to
	 * search again from ([gapFillOffered] takes the line's Fill gaps away).
	 */
	val downloadsOn: Boolean = true,
	/**
	 * B-231 round 3: the tracks ([gapTrackKey]) a pending match held as `downloaded` when this device
	 * last saw it, carried across the fetch that follows ([heldForMatch]). Not fetched by it, so not
	 * counted by it.
	 */
	val heldForMatch: Set<String> = emptySet()
)

/** Worbus P1: whether the gaps box's line offers Fill gaps (lb-bot can download; an older peer can). */
fun gapFillOffered(downloads: LbDownloads): Boolean = downloads.isOn

sealed interface GapStage {
	/** Nothing missing, or the result has been shown: the box folds away. */
	data object Absent : GapStage
	/** One line: how many are missing, Fill gaps, a chevron. */
	data class Folded(val missing: Int) : GapStage
	/** The box is open and lb-bot's record has not come yet. */
	data object Loading : GapStage
	/** Unfolded to the missing songs and what they are measured against. */
	data class Tracks(val missing: List<LbGapTrack>, val total: Int) : GapStage
	data object Searching : GapStage
	data class Choosing(val sources: List<LbGapSource>, val preselected: Int?) : GapStage
	/** A search that ended with nothing; lb-bot's reason when it gave one. */
	data class NoSources(val reason: String, val mp3WouldHelp: Boolean) : GapStage
	data class Active(val done: Int, val wanted: Int, val failed: Int, val steps: List<RequestStep>) : GapStage
	/** Files that arrived and need filing in lb-bot: no search or fetch from here can help. */
	data class Attention(val reason: String) : GapStage
	data class Failed(val reason: String, val mp3WouldHelp: Boolean) : GapStage
	data class Done(val added: Int, val present: Int, val total: Int) : GapStage
}

/** States a track is in while a fill is moving it. */
private val IN_FLIGHT_TRACK_STATES = setOf("queued", "downloading")

/** Tracks that are no longer missing: in the library before, or put there by a fill. */
private val HELD_TRACK_STATES = setOf("present", "done")

fun gapStage(input: GapInput): GapStage {
	val gap = input.gap
	val fill = input.fill
	if (gap == null) {
		return when {
			input.mirroredMissing <= 0 -> GapStage.Absent
			input.view == GapView.FOLDED -> GapStage.Folded(input.mirroredMissing)
			else -> GapStage.Loading
		}
	}
	val transferring = input.committed || gapTransferring(gap)
	// Worbus P1: with downloading off nothing is transferring, whatever a record from before says.
	if (transferring && input.downloadsOn) {
		return gapActive(gap, input.heldForMatch)
	}
	val all = missingTracks(gap)
	if (fill != null && fill.settled && fill.outcome == LbBotManager.OUTCOME_DONE) {
		if (input.now - fill.finishedAt < GAP_RESULT_HOLD_MS) {
			return GapStage.Done(
				added = fill.done.takeIf { it > 0 } ?: fill.total,
				present = gap.present,
				total = gap.total
			)
		}
		if (all.isEmpty()) return GapStage.Absent
	}
	if (gap.status == "complete" || (all.isEmpty() && gap.total > 0)) return GapStage.Absent
	val searching = input.searchAsked || gap.sourceTask?.status.orEmpty() in LbBotManager.SEARCH_IN_FLIGHT
	// B-231, ruling (a), PROTOCOL §15.2: a pending match (files downloaded and waiting to be matched in
	// lb-bot's workspace) is lb-bot's to sort out for THOSE files only. The tracks that are not
	// `downloaded` can still be searched for, fetched and Auto-filled from here, as with any gap; the
	// box says Open in lb-bot above its stage (`gapAwaitingMatch`). With nothing else to fetch it is
	// Attention, as a stalled placement always is: no search or fetch from here can help then.
	// B-231 round 2: the pending match does not depend on a search (the desktop's `gapMatchPending`,
	// point 1): only Attention waits for no search in flight (point 3). Gated on `searching` it fell
	// away while another device searched, and the box counted and listed the downloaded tracks too.
	val awaiting = gapMatchPending(gap)
	val missing = gapTracksToFetch(gap)
	// Worbus P1: with downloading off the box is the user's fold over what is missing, whatever view
	// or fill is left from before; none of the stages below can be acted on, and their words are a
	// download's (Attention's "Downloaded, but needs sorting out in lb-bot" included).
	if (!input.downloadsOn) {
		return if (input.view == GapView.FOLDED) GapStage.Folded(missing.size.takeIf { it > 0 } ?: input.mirroredMissing)
		else GapStage.Tracks(missing, gap.total)
	}
	if (gap.stalledPlacement || (!searching && awaiting && missing.isEmpty())) return GapStage.Attention(gapReason(gap))
	// A fill that failed keeps the box open, saying why, until it is dismissed or tried again: also
	// over the sources it was requested from (seen on the AVD: the sources view, with no rows left
	// and no search running, read as searching for good). A new search tap is the way out.
	// B-231 (the shared rule, point 4): a pending match's ledger settle (`failed` at `needs_match`) is the
	// match pending, not a failed fill; the box goes on over the tracks still to fetch. Any other failed
	// fill is a failure, pending match or not (the coordinator's ruling for both clients, round 2).
	val matchPendingSettle = awaiting && fill?.state == "needs_match"
	if (fill != null && fill.settled && fill.outcome == LbBotManager.OUTCOME_FAILED && !input.searchAsked && !matchPendingSettle) {
		return GapStage.Failed(gapReason(gap).ifBlank { fill.reason }, gap.mp3WouldHelp || fill.mp3WouldHelp)
	}
	if (input.view == GapView.SOURCES) {
		return when {
			searching -> GapStage.Searching
			gap.sources.isNotEmpty() -> GapStage.Choosing(gap.sources, preselectedSource(gap.sources))
			gap.noSourceReason.isNotBlank() || gap.sourceTask != null ->
				GapStage.NoSources(gap.noSourceReason, gap.mp3WouldHelp)
			// Nothing asked yet: the box asks as it opens on the sources.
			else -> GapStage.Searching
		}
	}
	return when (input.view) {
		GapView.TRACKS -> GapStage.Tracks(missing, gap.total)
		else -> GapStage.Folded(missing.size.takeIf { it > 0 } ?: input.mirroredMissing)
	}
}

/** A gap track's identity across reads: its disc and position. */
fun gapTrackKey(track: LbGapTrack): String = "${track.disc}-${track.position}"

/** lb-bot is moving tracks for this gap. */
private fun gapTransferring(gap: LbGap): Boolean =
	gap.status == "downloading" || gap.tracks.any { it.state in IN_FLIGHT_TRACK_STATES }

/**
 * B-231, the shared rule's point 1: a pending match is `picking` with a track `downloaded`, not a
 * stalled placement, whether or not a search is in flight (the desktop's `gapMatchPending`).
 */
fun gapMatchPending(gap: LbGap): Boolean = !gap.stalledPlacement && gap.awaitingMatch(searching = false)

/**
 * The shared rule's point 2: the tracks a search or fetch is for, the missing ones less those a
 * pending match holds as `downloaded` (the desktop's `gapTracksToFetch`). Every count the box shows
 * is of these (round 3, the VM walk).
 */
fun gapTracksToFetch(gap: LbGap): List<LbGapTrack> {
	val missing = missingTracks(gap)
	return if (gapMatchPending(gap)) missing.filter { it.state != "downloaded" } else missing
}

/**
 * The in-flight stage's counts ("Getting N tracks", "x of N") over the tracks the fetch is for:
 * every track not `present`, less the ones a pending match held as `downloaded` before it began
 * ([heldForMatch]) and still holds. lb-bot's record says `downloading` during the fetch, so the
 * pending match cannot be read off it then.
 */
internal fun gapActive(gap: LbGap, heldForMatch: Set<String>): GapStage.Active {
	val held = gap.copy(tracks = gap.tracks.filterNot { it.state == "downloaded" && gapTrackKey(it) in heldForMatch })
	// Never "Getting 0 tracks": a fetch accepted before its record moves counts what the record says.
	val fetched = if (held.tracksWanted > 0) held else gap
	return GapStage.Active(
		done = fetched.tracksDone,
		wanted = fetched.tracksWanted,
		failed = fetched.tracksFailed,
		steps = requestSteps(gapStepState(fetched), fetched.tracksDone, fetched.tracksWanted)
	)
}

/** How many tracks the sources view's header and its Request button count: the tracks to fetch. */
fun gapFetchCount(gap: LbGap?): Int = gap?.let { gapTracksToFetch(it).size } ?: 0

/**
 * The tracks ([gapTrackKey]) a pending match holds as `downloaded`, as this device last saw them,
 * for [GapInput.heldForMatch]: read afresh while the gap is a pending match, kept (those still
 * `downloaded`) while a fetch runs, and nothing once the gap is neither. A fetch this device first
 * sees already running (started elsewhere, the page opened mid-fetch) has nothing remembered and
 * counts every track not `present`, as before.
 */
fun heldForMatch(previous: Set<String>, gap: LbGap?, fetching: Boolean = false): Set<String> = when {
	gap == null -> previous
	gapMatchPending(gap) -> gap.tracks.filter { it.state == "downloaded" }.map(::gapTrackKey).toSet()
	gapTransferring(gap) -> gap.tracks.filter { it.state == "downloaded" && gapTrackKey(it) in previous }.map(::gapTrackKey).toSet()
	else -> emptySet()
}

/** The tracks the album still lacks, in tracklist order. */
fun missingTracks(gap: LbGap): List<LbGapTrack> = gap.tracks.filter { it.state !in HELD_TRACK_STATES }

/**
 * A gap fill's tracks as the request box's step names (`requestSteps`): one downloading is
 * Downloading; queued ones with none moving yet are Queued; all fetched and some not yet filed is
 * Placing in your library.
 */
fun gapStepState(gap: LbGap): String {
	val wanted = gap.tracks.filter { it.state != "present" }
	return when {
		wanted.any { it.state == "downloading" } -> "downloading"
		wanted.any { it.state == "queued" || it.state == "picked" } -> "queued"
		wanted.any { it.state == "downloaded" } -> "placing"
		else -> "queued"
	}
}

/**
 * A fill has put its tracks in the library: this device's ledger row settled done, or lb-bot counts
 * the album whole (a fill started on another client has no ledger row here). The album page re-reads
 * its tracklist when this turns true (`CollectionDetailViewModel.watchGapLanding`).
 */
fun gapFillLanded(gap: LbGap, fill: LbFillEntry?): Boolean =
	(fill != null && fill.settled && fill.outcome == LbBotManager.OUTCOME_DONE) ||
		gap.status == "complete" || (gap.total > 0 && missingTracks(gap).isEmpty())

/** The tracks the album holds by lb-bot's count: what a re-read tracklist should have at least. */
fun heldTracks(gap: LbGap): Int = gap.tracks.count { it.state in HELD_TRACK_STATES }

/**
 * What the gaps box keeps when the card at [index] of [sources] is chosen (a tap or the
 * preselection): the source's `id`, the folder's identity (peer + folder, the same across searches,
 * lb-bot Phase A W6), never its position (B-370). A search replaces the list whole, and a position
 * then names another folder, which Request fetched with no error.
 */
fun gapSelection(sources: List<LbGapSource>, index: Int): Int? = sources.getOrNull(index)?.id

/**
 * The card [selection] (a source id) names in [sources]: the one highlighted, and the one Request
 * sends. Null when the list no longer holds that folder: nothing is selected then.
 */
fun gapChosenSource(sources: List<LbGapSource>, selection: Int?): LbGapSource? =
	selection?.let { id -> sources.firstOrNull { it.id == id } }

/** Where [selection] sits in [sources] now, for `SourceCards`' highlight; null when it is gone. */
fun gapSelectedIndex(sources: List<LbGapSource>, selection: Int?): Int? =
	selection?.let { id -> sources.indexOfFirst { it.id == id }.takeIf { it >= 0 } }

/**
 * The source id the box chooses for the user when the sources land (the safe default at
 * [preselected], a position in [sources]), or null to leave the choice alone: a choice whose folder
 * is still in the list stands, and one whose folder left it is no choice (B-370); none at all
 * while [pickAgain].
 */
fun gapPreselection(sources: List<LbGapSource>, preselected: Int?, selectedId: Int?, pickAgain: Boolean): Int? {
	// After lb-bot refused the chosen folder as stale the user picks again: a default standing in
	// would sit highlighted under lb-bot's sentence, and a quick second Request would send a folder
	// the user never chose (B-370 review).
	if (pickAgain) return null
	val id = preselected?.let { gapSelection(sources, it) } ?: return null
	return id.takeIf { gapChosenSource(sources, selectedId) == null }
}

/** lb-bot's 409 when a source id is not in today's list: a newer search replaced it (Phase A W6). */
const val STALE_SOURCE_CODE = "stale_source"

/**
 * B-231: the gap has files waiting to be matched in lb-bot's workspace while the box still offers to
 * fetch the rest; the box then shows Open in lb-bot prominently above its stage. Not while it is
 * [GapStage.Attention] (which says it itself) or downloading is off.
 */
fun gapAwaitingMatch(gap: LbGap?, stage: GapStage, downloadsOn: Boolean): Boolean =
	gap != null && downloadsOn && stage !is GapStage.Attention && stage !is GapStage.Active && stage !is GapStage.Absent &&
		gap.awaitingMatch(searching = false)
