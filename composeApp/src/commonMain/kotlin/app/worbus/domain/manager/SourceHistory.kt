package app.worbus.domain.manager

import kotlinx.serialization.Serializable

/*
 * navi-connect P6: Source history, as rules. What lb-bot's fill ledger still holds (`GET /lb/activity`,
 * PROTOCOL §15.6) joined with this device's own ledger, which outlives lb-bot's (it keeps a verified
 * row 6 h and a failed one 24 h; the device keeps a week).
 *
 * The newer row wins the state (by each row's own time, the follow-ups review L2, as Need you decides); the
 * device's keeps what lb-bot has forgotten and is the only one with buttons: a row only lb-bot knows offers Open
 * album and nothing else (no Retry, no Cancel, and no Dismiss, since it expires there by itself).
 */

/** One request in lb-bot's ledger. `state` is the §15.2 server state. */
@Serializable
data class ActivityRequest(
	val releaseMbid: String = "",
	val rgid: String = "",
	val groupId: String = "",
	val albumId: String = "",
	val artist: String = "",
	val album: String = "",
	val state: String = "",
	val percent: Int = 0,
	val done: Int = 0,
	val total: Int = 0,
	val failureKind: String = "",
	val reason: String = "",
	val retryable: Boolean = false,
	val gapFill: Boolean = false,
	/** Epoch seconds, lb-bot's clock. */
	val updatedAt: Double = 0.0
)

/** One file-level transfer. Never a peer's filename or a path. */
@Serializable
data class ActivityTransfer(
	val id: String = "",
	val kind: String = "",
	val album: String = "",
	val artist: String = "",
	val groupId: String = "",
	val state: String = "",
	val pct: Int = 0,
	val peer: String = ""
)

/** `GET /lb/activity`'s answer. */
@Serializable
data class LbActivity(
	val version: Int = 0,
	val serverTime: Double = 0.0,
	val requests: List<ActivityRequest> = emptyList(),
	val transfers: List<ActivityTransfer> = emptyList()
)

/**
 * Review M1 (second re-walk): lb-bot's last good `/lb/activity` answer and when this device read it (epoch ms,
 * its own clock), kept by [LbBotManager.activityAnswer] so Source history and its menu badge join the ledger
 * with the same answer.
 */
data class ActivityAnswer(
	val requests: List<ActivityRequest>,
	val transfers: List<ActivityTransfer>,
	val readAt: Long
)

sealed interface ActivityRead {
	data class Ok(val requests: List<ActivityRequest>, val transfers: List<ActivityTransfer>) : ActivityRead
	data object Older : ActivityRead
	data object Unavailable : ActivityRead
}

/** One answer read as [ActivityRead], by [attentionRead]'s rules. */
fun activityRead(status: Int, body: String): ActivityRead = when (status) {
	in 200..299 -> {
		val parsed = try {
			lbWireJson.decodeFromString(LbActivity.serializer(), body)
		} catch (e: Exception) {
			null
		}
		if (parsed != null && parsed.version >= 1) ActivityRead.Ok(parsed.requests, parsed.transfers)
		else ActivityRead.Unavailable
	}
	404 -> if (bodyCode(body).isBlank()) ActivityRead.Older else ActivityRead.Unavailable
	405 -> ActivityRead.Older
	// Follow-up 2026-11-14 (the desktop's `screenRead`): the hub calling lb-bot too old for this user is "no
	// answer" (Need you is then the ledger's), not a failed read that keeps the last list.
	503 -> if (bodyCode(body) == "lbbot_too_old") ActivityRead.Older else ActivityRead.Unavailable
	else -> ActivityRead.Unavailable
}

/**
 * Source history's chips. Saved by position with a back stack (CLAUDE.md §2 rule 15), so only ever appended
 * to: [NeedYou] (review M1, second re-walk) is drawn second, after Requests, as the desktop's Activity has it.
 */
@Serializable
enum class SourceHistorySection { Requests, Transfers, Wishlist, NeedYou }

/**
 * One row of Requests. [state] is the ledger's own state string (lb-bot's when its row decided); [entry] is this
 * device's row, the one with buttons; [server] is lb-bot's activity row of the request, when it lists one.
 */
data class HistoryRow(
	val key: String,
	val artist: String,
	val album: String,
	val rgid: String,
	val state: String,
	val percent: Int?,
	/** Epoch ms. */
	val updatedAt: Long,
	val fromDevice: Boolean,
	val entry: LbFillEntry?,
	val server: ActivityRequest? = null,
	/**
	 * The fill the row is drawn as, words, pill and buttons ([shown]); built by [mergeHistory] and
	 * [NeedYouItem.asHistoryRow] from the state that decided the request (review M1 of the second re-walk; review L1
	 * and L2 of the follow-ups).
	 */
	val drawn: LbFillEntry? = null,
	/** [drawn] is [entry] with lb-bot's newer state laid on it ([withServerState]): its buttons are this device's. */
	val overlaid: Boolean = false,
	/** Stage 2 M4: the library album lb-bot names for the request (a gap's), when it names one. */
	val libraryAlbum: RequestAlbum = RequestAlbum()
) {
	val shown: LbFillEntry get() = drawn ?: entry ?: server!!.asLedgerRow()

	/** Drawn as this device's row (itself, or with lb-bot's newer state on it): its buttons act on [entry]. */
	val mine: Boolean get() = entry != null && (shown === entry || overlaid)

	/** A request drawn as failed or waiting on a pick (Need you itself is [needYouItems]). */
	val needsYou: Boolean
		get() = shown.settled &&
			(shown.outcome == LbBotManager.OUTCOME_FAILED || shown.outcome == LbBotManager.OUTCOME_NEEDS_PICK)

	/**
	 * The Running group: this device's row drawn as itself while it is unsettled; lb-bot's row while it is
	 * searching, queued, downloading or placing.
	 */
	val running: Boolean get() = if (entry != null && shown === entry) !entry.settled else !shown.settled && shown.state in RUNNING_STATES
}

private val RUNNING_STATES = setOf("searching", "queued", "downloading", "placing")

/**
 * Stage 2 M4: the library album a request is about, as lb-bot names it (`albumId` on an activity or attention row),
 * with a gap's missing-track count and lb-bot's cover for it. Empty when lb-bot names none.
 */
data class RequestAlbum(val albumId: String = "", val missingCount: Int = 0, val coverUrl: String = "")

/** Where a request's Open album leads. */
sealed interface HistoryOpen {
	/** The library's own album page; [gaps]: with its gaps box open (a gap). */
	data class Library(val albumId: String, val gaps: Boolean) : HistoryOpen

	/** The release group's page, for an album the library does not hold. */
	data class External(val rgid: String) : HistoryOpen
}

/**
 * Where [row]'s Open album leads, or null when nowhere. To fix's order (stage 2 M4): a gap is an album the library
 * partly holds, so its own page with the gaps box open; else the release group's page; else the library album.
 */
fun historyOpenTarget(row: HistoryRow): HistoryOpen? {
	val albumId = row.libraryAlbum.albumId
	return when {
		row.shown.isGap && albumId.isNotBlank() -> HistoryOpen.Library(albumId, gaps = true)
		row.rgid.isNotBlank() -> HistoryOpen.External(row.rgid)
		albumId.isNotBlank() -> HistoryOpen.Library(albumId, gaps = false)
		else -> null
	}
}

/**
 * Stage 2 M4: the library album lb-bot names for a request ([theirs]: lb-bot's rows of it), the deciding row's first
 * ([decided]), then the newest that names one; a gap's missing count and cover from its attention row.
 */
internal fun requestAlbum(theirs: List<NeedYouServerEntry>, decided: NeedYouServerEntry?): RequestAlbum {
	val byNewest = theirs.sortedByDescending { it.updatedAt }
	val albumId = decided?.albumId?.ifBlank { null } ?: byNewest.firstOrNull { it.albumId.isNotBlank() }?.albumId.orEmpty()
	val attention = byNewest.mapNotNull { it.attention }
	return RequestAlbum(
		albumId = albumId,
		missingCount = attention.firstOrNull { it.kind == "gap" }?.missingCount ?: 0,
		coverUrl = attention.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty()
	)
}

/** Whether lb-bot's [state] says what a settled ledger row's [outcome] says. */
private fun serverAgrees(state: String, outcome: String): Boolean = when (state) {
	"verified", "placed" -> outcome == LbBotManager.OUTCOME_DONE
	"failed", "needs_match" -> outcome == LbBotManager.OUTCOME_FAILED
	"picking" -> outcome == LbBotManager.OUTCOME_NEEDS_PICK
	"cancelled" -> outcome == LbBotManager.OUTCOME_CANCELLED
	else -> false
}

/**
 * Whether the page reads lb-bot again every few seconds: while a running row is one lb-bot lists. A
 * Retry'd row is such a row, and the read is what finally replaces lb-bot's stale failed state; a
 * running row only this phone holds moves with the ledger and needs no read.
 */
fun historyRereadWanted(groups: HistoryGroups): Boolean = groups.running.any { it.server != null }

/** How the page's read of lb-bot stands: before the first answer, or what the last one was. */
enum class HistoryRead { NotYet, Reading, Answered, Unavailable, Older }

fun historyRead(status: ActivityRead?, loading: Boolean): HistoryRead = when (status) {
	null -> if (loading) HistoryRead.Reading else HistoryRead.NotYet
	is ActivityRead.Ok -> HistoryRead.Answered
	ActivityRead.Older -> HistoryRead.Older
	ActivityRead.Unavailable -> HistoryRead.Unavailable
}

/** What Requests says besides its rows. */
sealed interface HistoryEmpty {
	/** No answer yet and nothing to list: placeholder rows, not "No requests yet". */
	data object Skeleton : HistoryEmpty
	data object NoRequests : HistoryEmpty
	/** An older hub or lb-bot: only this phone's requests are listed. */
	data object Older : HistoryEmpty
	/** lb-bot did not answer: what is listed was read at [asOf] (epoch ms), or never when null. */
	data class Unreachable(val asOf: Long?) : HistoryEmpty
	data object None : HistoryEmpty
}

/**
 * B4 (audit L4b-08): "No requests yet" only after lb-bot answered with none; placeholders before the
 * first answer; a list lb-bot could not refresh says when it was read.
 */
fun historyEmptyState(read: HistoryRead, rows: Int, asOf: Long? = null): HistoryEmpty = when (read) {
	HistoryRead.NotYet, HistoryRead.Reading -> if (rows == 0) HistoryEmpty.Skeleton else HistoryEmpty.None
	HistoryRead.Answered -> if (rows == 0) HistoryEmpty.NoRequests else HistoryEmpty.None
	HistoryRead.Older -> HistoryEmpty.Older
	HistoryRead.Unavailable -> HistoryEmpty.Unreachable(asOf)
}

/**
 * lb-bot's list joined with the device's, each request once. Review L2 of the follow-ups: **the same requests, joined
 * the same way and decided by the same state as Need you** ([requestGroups], [decide]): rows of one request by release,
 * group, release group and (an orphan gap) names; lb-bot's state decides only when its row is newer than this
 * device's, by lb-bot's own row time, never by when it was read. A device row lb-bot no longer lists stays, however
 * old. `/lb/attention`'s albums ([attention]) are lb-bot's side too: a gap's own status, another client's pick.
 *
 * Drawn: this device's row when its state decided; this device's row with lb-bot's newer state on it when that state
 * needs someone (as Need you draws it, review L1); this device's running row while lb-bot's newer row is a fill in
 * flight; lb-bot's row (Open album only) when lb-bot's newer state is another ending (a retry from another client
 * that landed). Dismissals are not applied here: Requests lists a dismissed request while lb-bot does (an open
 * question for the owner; the desktop's Activity lists its own ledger alone).
 */
fun mergeHistory(
	server: List<ActivityRequest>,
	device: List<LbFillEntry>,
	attention: List<AttentionAlbum> = emptyList()
): List<HistoryRow> = requestGroups(server, attention, emptyList(), device).map(::historyRow)

private fun NeedYouServerEntry.plainKey(): String =
	activity?.let { it.releaseMbid.ifBlank { it.groupId.ifBlank { it.rgid } } }
		?: attention?.let { it.key.ifBlank { it.groupId.ifBlank { it.releaseMbid } } }
		?: releaseMbid.ifBlank { groupId }

private fun historyRow(g: RequestGroup): HistoryRow {
	val d = decide(g.here, g.theirs)
	val mine = d.mine
	val lbbot = d.lbbot
	val (drawn, overlaid) = when {
		mine == null -> lbbot!!.ownRow() to false
		!d.lbbotDecides -> mine to false
		serverNeedOutcome(lbbot!!.state) != null -> mine.withServerState(lbbot) to true
		!mine.settled && lbbot.state in LIVE_STATES -> mine to false
		mine.settled && serverAgrees(lbbot.state, mine.outcome) -> mine to false
		else -> lbbot.ownRow() to false
	}
	val asMine = drawn === mine
	val activity = lbbot?.activity ?: g.theirs.filter { it.activity != null }.maxByOrNull { it.updatedAt }?.activity
	return HistoryRow(
		key = mine?.key ?: g.theirs.map { it.plainKey() }.filter { it.isNotBlank() }.minOrNull().orEmpty(),
		artist = lbbot?.artist?.ifBlank { null } ?: mine?.artist.orEmpty(),
		album = lbbot?.album?.ifBlank { null } ?: mine?.album.orEmpty(),
		rgid = lbbot?.rgid?.ifBlank { null } ?: mine?.rgid.orEmpty(),
		state = if (d.lbbotDecides) lbbot!!.state else mine!!.state,
		percent = if (asMine) mine!!.percent.takeIf { !mine.settled && mine.state == "downloading" }
		else lbbot?.activity?.percent?.takeIf { lbbot.state == "downloading" },
		updatedAt = if (asMine) deviceClock(mine!!) else lbbot!!.updatedAtMs,
		fromDevice = mine != null,
		entry = mine,
		server = activity,
		drawn = drawn,
		overlaid = overlaid,
		libraryAlbum = requestAlbum(g.theirs, lbbot)
	)
}

/**
 * Follow-up 2026-11-14: Source history's groups, the one reading the page and its menu badge share. Requests is
 * lb-bot's last `/lb/activity` answer joined with this device's ledger and `/lb/attention`'s albums ([mergeHistory]);
 * Need you is [needYouItems] over the same answers and what was dismissed here (the desktop's rule).
 */
fun sourceHistoryGroups(
	ledger: List<LbFillEntry>,
	activity: ActivityAnswer?,
	attention: AttentionState? = null,
	dismissed: List<DismissedRequest> = emptyList()
): HistoryGroups {
	val albums = attention?.last?.albums
	val rows = mergeHistory(activity?.requests.orEmpty(), ledger, albums.orEmpty())
	return historyGroups(rows).copy(
		needYou = needYouItems(activity?.requests, albums, dismissed, ledger).map { it.asHistoryRow() }
	)
}

/** When a device row last moved: its settle, else the later of its start and its last answer. */
internal fun deviceClock(e: LbFillEntry): Long =
	if (e.settled && e.finishedAt > 0L) e.finishedAt else maxOf(e.startedAt, e.lastCheckedAt)

/**
 * Requests' two groups, and the rows drawn as failed or waiting on a pick ([HistoryRow.needsYou]). Source history's
 * Need you chip is [sourceHistoryGroups]' (the desktop's rule, [needYouItems]), not this list.
 */
data class HistoryGroups(
	val running: List<HistoryRow>,
	val earlier: List<HistoryRow>,
	val needYou: List<HistoryRow> = emptyList()
)

/** Running over Earlier, newest first inside each; a tie keeps the merged order. */
fun historyGroups(rows: List<HistoryRow>): HistoryGroups {
	val (running, earlier) = rows.partition { it.running }
	return HistoryGroups(
		running = running.sortedByDescending { it.updatedAt },
		earlier = earlier.sortedByDescending { it.updatedAt },
		needYou = rows.filter { it.needsYou }.sortedByDescending { it.updatedAt }
	)
}

/** The Transfers chip is drawn only when lb-bot lists any. */
fun transfersShown(transfers: List<ActivityTransfer>): Boolean = transfers.isNotEmpty()

private val SETTLED_SERVER_STATES = setOf("verified", "failed", "cancelled", "needs_match")

/**
 * A row only lb-bot knows as a ledger row, so the fill vocabulary words it ([app.worbus.ui.components.common.describeFill]).
 * Only its words are used: such a row offers Open album and nothing else, whatever its buttons say.
 */
fun ActivityRequest.asLedgerRow(): LbFillEntry {
	val settled = state in SETTLED_SERVER_STATES
	return LbFillEntry(
		key = releaseMbid.ifBlank { groupId.ifBlank { rgid } },
		isGap = gapFill,
		rgid = rgid,
		groupId = groupId,
		releaseMbid = releaseMbid,
		artist = artist,
		album = album,
		quality = "",
		state = state,
		outcome = when (state) {
			"verified" -> LbBotManager.OUTCOME_DONE
			"failed", "needs_match" -> LbBotManager.OUTCOME_FAILED
			"cancelled" -> LbBotManager.OUTCOME_CANCELLED
			else -> LbBotManager.OUTCOME_RUNNING
		},
		reason = reason,
		percent = percent,
		done = done,
		total = total,
		mp3WouldHelp = false,
		failureKind = failureKind,
		retryable = retryable,
		attempts = 0,
		settled = settled,
		startedAt = 0L,
		finishedAt = (updatedAt * 1000).toLong(),
		cancellable = false
	)
}

/** An attention album lb-bot holds and this device does not, worded the same way. */
fun AttentionAlbum.asLedgerRow(): LbFillEntry = LbFillEntry(
	key = key.ifBlank { groupId.ifBlank { releaseMbid } },
	isGap = kind == "gap",
	rgid = rgid,
	groupId = groupId,
	releaseMbid = releaseMbid,
	artist = artist,
	album = album,
	quality = "",
	state = state,
	outcome = if (picking) LbBotManager.OUTCOME_NEEDS_PICK else LbBotManager.OUTCOME_FAILED,
	reason = "",
	percent = 0,
	done = 0,
	total = total,
	mp3WouldHelp = false,
	failureKind = "",
	// Review L3: the desktop's `retryable ?? gap` (an attention album says nothing of it).
	retryable = kind == "gap",
	attempts = 0,
	settled = true,
	startedAt = 0L,
	finishedAt = (updatedAt * 1000).toLong(),
	cancellable = false
)

