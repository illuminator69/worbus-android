package app.worbus.ui.screens.external

import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.request_step_count
import worbus.composeapp.generated.resources.request_step_downloading
import worbus.composeapp.generated.resources.request_step_finding
import worbus.composeapp.generated.resources.request_step_in_library
import worbus.composeapp.generated.resources.request_step_placing
import worbus.composeapp.generated.resources.request_step_queued
import worbus.composeapp.generated.resources.request_step_source_chosen
import app.worbus.domain.manager.LbBotManager
import app.worbus.domain.manager.LbFillEntry
import app.worbus.domain.manager.LbFillStatus
import app.worbus.domain.manager.LbGapSource
import app.worbus.domain.manager.LbRelease
import app.worbus.ui.components.common.FillButton
import app.worbus.ui.components.common.FillPresentation
import app.worbus.ui.components.common.FillText
import app.worbus.ui.components.common.describeFill

/*
 * navi-connect P4: which stage the album page's request box is in (`RequestBox`). One function of
 * what is known — the hub's support, this device's ledger row for the release-group, lb-bot's index
 * row, the source search, the edition on screen, a tap in flight — so the box cannot be in two
 * stages at once and every precedence below is a test (`RequestStageTest`).
 *
 * A running fill is the truth whatever else is set: it may have been started on another device or
 * before a restart, and a second request is the one thing the box must not offer then.
 */

/** Where the source search for this album is. Keyed by the edition it was asked for. */
sealed interface SourceSearch {
	data object Idle : SourceSearch
	data class Searching(val editionMbid: String) : SourceSearch
	data class Found(val editionMbid: String, val sources: List<LbGapSource>) : SourceSearch
	data class Failed(val error: LbBotManager.LbError) : SourceSearch
}

/**
 * Whether this hub can take a request at all: lb-bot off, a hub too old for the source picker, or yes.
 * [DOWNLOADS_OFF] (Worbus P1) is lb-bot saying it cannot download (`downloads: "off"`, or its 409
 * `downloads_off`): appended, so the order of the others is unchanged. [ADMIN_ONLY] (B-408): the
 * server can download, and this user may not (lb-bot's route table keeps downloading to admins).
 */
enum class Support { ABSENT, NEEDS_HUB_RESTART, YES, DOWNLOADS_OFF, ADMIN_ONLY }

data class RequestInput(
	val support: Support,
	/** This device's ledger row for the release-group (its key is the rgid), if any. */
	val fill: LbFillEntry?,
	/** lb-bot's index row for the release-group, if it has one. */
	val indexed: LbRelease?,
	val search: SourceSearch,
	/** The edition the box is showing; a search for another one is not this box's. */
	val editionMbid: String,
	/** A request tap whose answer has not come back yet. */
	val starting: Boolean,
	val wishlistAvailable: Boolean,
	val now: Long,
	/** NB2: the hub calls lb-bot too old for this user (R26). */
	val tooOld: Boolean = false,
	/**
	 * B-396: lb-bot's own status for a fill of this release-group that this device did not start
	 * (another device, or lb-bot's web page), read without a watch ([fillElsewhere]). Null when none
	 * is running.
	 */
	val elsewhere: LbFillStatus? = null
)

enum class StepStatus { DONE, NOW, PENDING }

data class RequestStep(val label: FillText, val status: StepStatus, val detail: FillText? = null)

sealed interface RequestStage {
	/** lb-bot is off: the box is not drawn. */
	data object Absent : RequestStage
	/** The hub does not proxy the source picker yet. */
	data object NeedsHubRestart : RequestStage
	/**
	 * Worbus P1: lb-bot cannot download on this server. One plain line, nothing offered: the page's
	 * Add to wishlist and Find it are the ways on.
	 */
	data object DownloadsOff : RequestStage
	/**
	 * B-408: the server can get albums and this user is not an admin, whom lb-bot keeps downloading
	 * (and the source list) to. Said as that, never as "this server isn't set up"; nothing offered.
	 */
	data object AdminOnly : RequestStage
	/** Nothing asked yet; [cancelledBefore] when the last request was cancelled. */
	data class Idle(val cancelledBefore: Boolean) : RequestStage
	data object Searching : RequestStage
	data class SearchFailed(val error: LbBotManager.LbError) : RequestStage
	data object NoSources : RequestStage
	/** [preselected] only when [preselectedSource] found one safe to offer as the default. */
	data class Choosing(val sources: List<LbGapSource>, val preselected: Int?) : RequestStage
	data object Starting : RequestStage
	/**
	 * A fill is running. [startedElsewhere] (B-396): it is not this device's (no ledger row here), so
	 * the box shows its progress and offers nothing to press.
	 */
	data class Active(
		val view: FillPresentation,
		val steps: List<RequestStep>,
		val startedElsewhere: Boolean = false
	) : RequestStage
	/** Settled, waiting on the user: a source to pick, or a match to sort out in lb-bot. */
	data class Attention(val view: FillPresentation) : RequestStage
	data class Failed(val view: FillPresentation) : RequestStage
	data class Done(val view: FillPresentation) : RequestStage
	/**
	 * lb-bot's index says the library holds it (or, for a partly-owned album with no fill, part of it);
	 * Room has not caught up yet.
	 */
	data object InLibrary : RequestStage
}

fun requestStage(input: RequestInput): RequestStage {
	if (input.support == Support.ABSENT) return RequestStage.Absent
	// Worbus P1: decided before the fill ledger. A settled fill's Retry would be refused, and a fill
	// lb-bot cannot carry on will not move again; what the library holds is still said.
	if (input.support == Support.DOWNLOADS_OFF || input.support == Support.ADMIN_ONLY) {
		if (input.indexed != null && !input.indexed.isMissing) return RequestStage.InLibrary
		return if (input.support == Support.ADMIN_ONLY) RequestStage.AdminOnly else RequestStage.DownloadsOff
	}
	val fill = input.fill
	if (fill != null && !fill.settled) {
		return RequestStage.Active(
			describeFill(fill, input.now, input.wishlistAvailable, input.tooOld),
			requestSteps(fill.state, fill.done, fill.total)
		)
	}
	// B-396: a fill this device did not start, running as lb-bot reports it. It is shown as this
	// device's would be, with nothing to press: its Cancel, Retry and the rest act on a ledger row,
	// and this device keeps none for it (no Downloads row, no notification: the owner's ruling).
	val elsewhere = input.elsewhere?.takeIf { it.state in ELSEWHERE_LIVE_STATES }
	if (elsewhere != null) {
		val entry = elsewhereEntry(elsewhere)
		return RequestStage.Active(
			describeFill(entry, input.now, wishlistAvailable = false, tooOld = input.tooOld).copy(buttons = emptyList()),
			requestSteps(entry.state, entry.done, entry.total),
			startedElsewhere = true
		)
	}
	// An `incomplete` album is partly owned: a settled fill for it (failed, a pick waiting) still shows
	// (audit L4b-05); with no fill, what the library holds of it is what there is to say.
	if (input.indexed != null && !input.indexed.isMissing &&
		(input.indexed.status != "incomplete" || fill == null)
	) return RequestStage.InLibrary
	var cancelledBefore = false
	if (fill != null) {
		val view = describeFill(fill, input.now, input.wishlistAvailable, input.tooOld)
		when {
			fill.outcome == "cancelled" -> cancelledBefore = true
			fill.outcome == "done" -> return RequestStage.Done(view)
			fill.outcome == "needs_pick" || fill.state == "needs_match" -> return RequestStage.Attention(view)
			else -> return RequestStage.Failed(view)
		}
	}
	if (input.support == Support.NEEDS_HUB_RESTART) return RequestStage.NeedsHubRestart
	if (input.starting) return RequestStage.Starting
	return when (val search = input.search) {
		SourceSearch.Idle -> RequestStage.Idle(cancelledBefore)
		is SourceSearch.Searching ->
			if (search.editionMbid == input.editionMbid) RequestStage.Searching else RequestStage.Idle(cancelledBefore)
		is SourceSearch.Found -> when {
			search.editionMbid != input.editionMbid -> RequestStage.Idle(cancelledBefore)
			search.sources.isEmpty() -> RequestStage.NoSources
			else -> RequestStage.Choosing(search.sources, preselectedSource(search.sources))
		}
		is SourceSearch.Failed -> RequestStage.SearchFailed(search.error)
	}
}

/**
 * The five steps a request walks through, for a fill in [state]. The captions are Worbus's own
 * (PROTOCOL §15.2 names states, not steps); a state that is not a step of a running request gives
 * no steps at all.
 */
fun requestSteps(state: String, done: Int, total: Int): List<RequestStep> {
	val now = when (state) {
		"searching" -> 0
		"queued" -> 1
		"downloading" -> 2
		"placing" -> 3
		"placed" -> 4
		"verified" -> 5
		else -> return emptyList()
	}
	val labels = listOf(
		if (state == "searching") Res.string.request_step_finding else Res.string.request_step_source_chosen,
		Res.string.request_step_queued,
		Res.string.request_step_downloading,
		Res.string.request_step_placing,
		Res.string.request_step_in_library
	)
	return labels.mapIndexed { index, res ->
		RequestStep(
			label = FillText.Res(res),
			status = when {
				index < now -> StepStatus.DONE
				index == now -> StepStatus.NOW
				else -> StepStatus.PENDING
			},
			detail = if (index == 2 && index == now && total > 0) {
				FillText.Res(Res.string.request_step_count, listOf(done, total))
			} else null
		)
	}
}

/**
 * The source the box may select on the user's behalf: lb-bot's recommendation, and only when lb-bot
 * also found the artist in the folder and reads the folder as this album. Anything less stays
 * unselected, and "Request this album" waits for a tap: a tap on an unverified folder is consent,
 * and a default is not.
 */
fun preselectedSource(sources: List<LbGapSource>): Int? =
	sources.indexOfFirst { it.recommended && it.artistVerified && it.albumMatchOk }.takeIf { it >= 0 }

private val ACTION_ORDER = listOf(
	FillButton.RETRY,
	FillButton.TRY_ANOTHER,
	FillButton.ALLOW_MP3,
	FillButton.WISHLIST,
	FillButton.CANCEL,
	FillButton.DISMISS
)

/** The box's recovery rows: [view]'s buttons, in the box's order, without Open album (the box redirects). */
fun requestActions(view: FillPresentation): List<FillButton> = ACTION_ORDER.filter { it in view.buttons }

/**
 * The idle box's line when Statistics' Find sources asked for a search that cannot start: the page's
 * releases are loaded and no edition is chosen. The search waits for one, and the box said nothing.
 */
fun chooseEditionLineShown(arrivalWanted: Boolean, detailLoaded: Boolean, editionMbid: String): Boolean =
	arrivalWanted && detailLoaded && editionMbid.isBlank()

/** lb-bot's states for a whole-album fill that is still moving (PROTOCOL §15.2): the box shows these. */
val ELSEWHERE_LIVE_STATES = setOf("searching", "queued", "downloading", "placing", "placed")

/**
 * B-396 (the owner's ruling, 2026-11-12): the fill of [rgid] another device started, as lb-bot reports
 * it, or null when none is running.
 *
 * [views] are what this device has heard of the release-group's fills: `/lb/fills` read over the
 * group's pressings (the other device may have asked for any of them), and the hub's `fill` frame,
 * which lb-bot pushes for every step keyed by the rgid. The NEWEST view that says anything decides
 * (`updatedAt`, lb-bot's last state change or progress): a frame left over from a step that has
 * since finished must not keep the box on Downloading once a read says verified. `unknown` is
 * lb-bot's "nothing for this pressing" and says nothing about another pressing, so it is never the
 * newest. A view of another release-group is not this page's.
 */
fun fillElsewhere(rgid: String, views: Collection<LbFillStatus>): LbFillStatus? =
	views.asSequence()
		.map { it.normalized() }
		.filter { it.state != "unknown" && (it.rgid.isBlank() || it.rgid == rgid) }
		.maxByOrNull { it.updatedAt }
		?.takeIf { it.state in ELSEWHERE_LIVE_STATES }

/** A read-only ledger-shaped view of lb-bot's [status], for [describeFill]. Never stored. */
internal fun elsewhereEntry(status: LbFillStatus): LbFillEntry = LbFillEntry(
	key = status.rgid,
	isGap = false,
	rgid = status.rgid,
	groupId = status.groupId,
	releaseMbid = status.releaseMbid,
	artist = status.artist,
	album = status.album,
	quality = status.quality,
	state = status.state,
	outcome = "running",
	reason = status.reason,
	percent = status.percent,
	done = status.done,
	total = status.total,
	mp3WouldHelp = status.mp3WouldHelp,
	failureKind = status.failureKind,
	retryable = status.retryable,
	attempts = status.attempts,
	settled = false,
	startedAt = 0L,
	finishedAt = 0L,
	cancellable = false,
	failedFiles = status.failed,
	bytesDone = status.bytesDone,
	bytesTotal = status.bytesTotal,
	speedBps = status.speedBps,
	lastSource = status.source,
	verifyGaveUp = status.verifyGaveUp,
	leftOut = status.leftOut ?: 0
)
