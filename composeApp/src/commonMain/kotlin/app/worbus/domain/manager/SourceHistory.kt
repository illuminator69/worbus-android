package app.worbus.domain.manager

import kotlinx.serialization.Serializable

/*
 * navi-connect P6: Source history, as rules. What lb-bot's fill ledger still holds (`GET /lb/activity`,
 * PROTOCOL §15.6) joined with this device's own ledger, which outlives lb-bot's (it keeps a verified
 * row 6 h and a failed one 24 h; the device keeps a week).
 *
 * lb-bot's row wins the state; the device's keeps what lb-bot has forgotten and is the only one with
 * buttons: a row only lb-bot knows offers Open album and nothing else (no Retry, no Cancel, and no
 * Dismiss, since it expires there by itself).
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
	else -> ActivityRead.Unavailable
}

/**
 * Source history's chips. Saved by position with a back stack (CLAUDE.md §2 rule 15), so only ever appended
 * to: [NeedYou] (review M1, second re-walk) is drawn second, after Requests, as the desktop's Activity has it.
 */
@Serializable
enum class SourceHistorySection { Requests, Transfers, Wishlist, NeedYou }

/**
 * One row of Requests. [state] is the ledger's own state string (lb-bot's when it lists the row);
 * [entry] is this device's row, the one with buttons; [server] is lb-bot's.
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
	 * Review M1: the row's state is the ledger's although lb-bot lists it: a Retry lb-bot has not been read
	 * since, or a row that settled here after lb-bot's answer was read. Follow-up 2026-11-14: also this device's
	 * settled GAP row, which lb-bot's fill ledger does not track (below).
	 */
	val ledgerWins: Boolean = false,
	/**
	 * Follow-up 2026-11-14: the gap's own state from `/lb/attention`'s `albums` (`kind: "gap"`, `failed` or
	 * `picking`), the one place lb-bot says a gap waits on a pick. `/lb/activity` is lb-bot's FILL ledger: a gap
	 * gets a row there only once a fill of it reaches placement, so for a gap that row is the last fill that got
	 * that far, never where the gap is now, and it never says `picking`.
	 */
	val gap: AttentionAlbum? = null,
	/** [gap] was read after this device's row settled and says otherwise: lb-bot's later state, shown and counted. */
	val gapLater: Boolean = false
) {
	/**
	 * Review M1 (second re-walk): the fill the row is drawn as, words, pill and buttons. This device's row
	 * ([entry]) while it is running, while the ledger is newer than lb-bot's answer ([ledgerWins]) and while
	 * the two agree; **lb-bot's row when lb-bot's later state differs** (a fill retried from another client
	 * that has landed, or one it is working on again), which then offers Open album alone, as any row only
	 * lb-bot knows. It used to be the ledger's always, while [state] (and so the Running group) was lb-bot's.
	 */
	val shown: LbFillEntry
		get() {
			if (gap != null && (entry == null || gapLater)) return gap.asLedgerRow()
			val s = server ?: return entry!!
			val e = entry ?: return s.asLedgerRow()
			if (!e.settled || ledgerWins || serverAgrees(s.state, e.outcome)) return e
			return s.asLedgerRow()
		}

	/**
	 * Follow-up 2026-11-14: what makes two rows one request for Need you's count: the review group when one is
	 * known (lb-bot keys its fill ledger by release, so one gap can be several rows), else the release, else the key.
	 */
	val identity: String
		get() = sequenceOf(entry?.groupId, server?.groupId, gap?.groupId).firstOrNull { !it.isNullOrBlank() }
			?.let { "g:$it" }
			?: sequenceOf(entry?.releaseMbid, server?.releaseMbid, gap?.releaseMbid).firstOrNull { !it.isNullOrBlank() }
				?.let { "r:$it" }
			?: "k:$key"

	/**
	 * Review M1: a request drawn as failed or waiting on a pick: Source history's Need you chip and its menu
	 * badge ([sourceHistoryBadge]), desktop `needsYou`.
	 *
	 * Follow-up 2026-11-14 (the owner: "no separation for requests between clients"): **any client's**. A row
	 * only lb-bot lists (another client's request, or one this phone's ledger has forgotten) counts when
	 * `/lb/activity` lists it as `failed` or `needs_match` (its files are in and wait to be matched), or
	 * `/lb/attention` lists the gap as `failed` or `picking` ([gap]); this phone's rows lb-bot does not list count by
	 * the ledger, as before. lb-bot's later state still wins ([shown]); one request counts once
	 * ([HistoryGroups.needYou], by [identity]).
	 */
	val needsYou: Boolean
		get() = shown.settled &&
			(shown.outcome == LbBotManager.OUTCOME_FAILED || shown.outcome == LbBotManager.OUTCOME_NEEDS_PICK)

	/**
	 * Searching, queued, downloading or placing: the Running group. A device row lb-bot does not list,
	 * or one it lists as finished while the ledger runs it again (a Retry), is running by its ledger.
	 */
	val running: Boolean get() = when {
		entry != null && !entry.settled && (server == null || server.state in TERMINAL_SERVER_STATES) -> true
		server == null && entry != null -> false
		else -> state in RUNNING_STATES
	}
}

private val RUNNING_STATES = setOf("searching", "queued", "downloading", "placing")

/** Whether lb-bot's [state] says what a settled ledger row's [outcome] says. */
private fun serverAgrees(state: String, outcome: String): Boolean = when (state) {
	"verified", "placed" -> outcome == LbBotManager.OUTCOME_DONE
	"failed", "needs_match" -> outcome == LbBotManager.OUTCOME_FAILED
	"picking" -> outcome == LbBotManager.OUTCOME_NEEDS_PICK
	"cancelled" -> outcome == LbBotManager.OUTCOME_CANCELLED
	else -> false
}

/** lb-bot's states a request ends in. A running ledger row against one of these is a Retry. */
private val TERMINAL_SERVER_STATES = setOf("failed", "placed", "verified", "cancelled")

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
 * lb-bot's list joined with the device's, each request once. A server row and a device row are the
 * same request by release MBID, else by group id ([sameFill]); lb-bot's state wins, the device's row
 * comes along. A device row lb-bot no longer lists stays, however old.
 *
 * Review M1 (second re-walk): **lb-bot's later state wins**. A ledger row that settled after lb-bot's
 * answer was read ([readAt], epoch ms on this device's clock; null is "not known", and lb-bot's state wins)
 * keeps its own state: that answer predates it.
 */
fun mergeHistory(
	server: List<ActivityRequest>,
	device: List<LbFillEntry>,
	readAt: Long? = null,
	/** Follow-up 2026-11-14: `/lb/attention`'s albums (the gaps' own state) and when they were read. */
	attention: List<AttentionAlbum> = emptyList(),
	attentionReadAt: Long? = null,
	/** Follow-up 2026-11-14: releases and groups this device dismissed ([LbBotManager.dismiss]). */
	dismissed: Set<String> = emptySet()
): List<HistoryRow> {
	val claimed = mutableSetOf<String>()
	val fromServer = server.mapNotNull { s ->
		val entry = device.firstOrNull { it.key !in claimed && sameFill(s.releaseMbid, s.groupId, it) }
			// A gap fill whose review group is gone reaches here with no groupId (lb-bot names it from its
			// labels): this device's gap row for it is found by its names, or it would count twice.
			?: if (s.gapFill && s.groupId.isBlank()) device.firstOrNull {
				it.key !in claimed && it.isGap && sameNames(it.artist, it.album, s.artist, s.album)
			} else null
		if (entry == null) {
			// One gap, several fill-ledger rows (lb-bot keys them by release): the device's gap row already
			// stands for the group, so another row of it is not a request of its own.
			if (s.groupId.isNotBlank() && device.any { it.isGap && it.groupId == s.groupId && it.key in claimed }) return@mapNotNull null
			// Dismissed here: lb-bot keeps its row (24 h for a failure), and it must not come back as someone else's.
			if (isDismissed(dismissed, s.releaseMbid, s.groupId, s.gapFill)) return@mapNotNull null
		}
		if (entry != null) claimed += entry.key
		// B2 (audit L4b-01): a running ledger row against a finished lb-bot row is a Retry lb-bot has
		// not been read since. The ledger is newer for the rows it names (a fill frame or a poll moves
		// it at once), as on Needs attention: the row is running, in the device's own state.
		val retried = entry != null && !entry.settled && s.state in TERMINAL_SERVER_STATES
		val settledSince = entry != null && entry.settled && readAt != null && entry.finishedAt > readAt
		// Follow-up 2026-11-14: a gap's fill-ledger row is the last fill that reached placement, not the gap:
		// an older `verified` or `placed` row must not hide this device's gap that failed or waits on a pick.
		val gapHeld = entry != null && entry.isGap && entry.settled
		val state = if (retried || settledSince || gapHeld) entry.state else s.state
		HistoryRow(
			key = entry?.key ?: s.releaseMbid.ifBlank { s.groupId.ifBlank { s.rgid } },
			artist = s.artist.ifBlank { entry?.artist.orEmpty() },
			album = s.album.ifBlank { entry?.album.orEmpty() },
			rgid = s.rgid.ifBlank { entry?.rgid.orEmpty() },
			state = state,
			percent = if (retried) entry.percent.takeIf { state == "downloading" } else s.percent.takeIf { s.state == "downloading" },
			updatedAt = if (retried) maxOf((s.updatedAt * 1000).toLong(), deviceClock(entry)) else (s.updatedAt * 1000).toLong(),
			fromDevice = entry != null,
			entry = entry,
			server = s,
			ledgerWins = retried || settledSince || gapHeld
		)
	}
	val deviceOnly = device.filter { it.key !in claimed }.map { e ->
		HistoryRow(
			key = e.key,
			artist = e.artist,
			album = e.album,
			rgid = e.rgid,
			state = e.state,
			percent = e.percent.takeIf { !e.settled && e.state == "downloading" },
			updatedAt = deviceClock(e),
			fromDevice = true,
			entry = e
		)
	}
	return withAttention(fromServer + deviceOnly, attention, attentionReadAt, dismissed)
}

/**
 * Follow-up 2026-11-14: `/lb/attention`'s failed and picking albums joined to the rows. A gap lb-bot lists is its
 * current state: it is laid on a row only lb-bot knows (whose fill-ledger row may be an older fill of that gap), and
 * on this device's row when it was read after that row settled and says otherwise ([HistoryRow.gapLater]). An album
 * no row names (another client's gap at a pick, a gap that failed at search, which writes no fill-ledger row) is a
 * row of its own, drawn from lb-bot's words, Open album only. A failed request is `/lb/activity`'s row already.
 */
private fun withAttention(
	rows: List<HistoryRow>,
	attention: List<AttentionAlbum>,
	readAt: Long?,
	dismissed: Set<String>
): List<HistoryRow> {
	if (attention.isEmpty()) return rows
	val out = rows.toMutableList()
	for (a in attention) {
		if (a.state != "failed" && a.state != "picking") continue
		val i = out.indexOfFirst { r -> namesSameRequest(r, a) }
		if (i >= 0) {
			val r = out[i]
			if (a.kind != "gap") continue
			val e = r.entry
			when {
				e == null -> out[i] = r.copy(gap = a, state = a.state)
				e.isGap && e.settled && readAt != null && e.finishedAt <= readAt && !gapAgrees(a.state, e.outcome) ->
					out[i] = r.copy(gap = a, gapLater = true, state = a.state)
			}
			continue
		}
		if (isDismissed(dismissed, a.releaseMbid, a.groupId, a.kind == "gap")) continue
		out += HistoryRow(
			key = a.key.ifBlank { a.groupId.ifBlank { a.releaseMbid } },
			artist = a.artist,
			album = a.album,
			rgid = a.rgid,
			state = a.state,
			percent = null,
			updatedAt = (a.updatedAt * 1000).toLong(),
			fromDevice = false,
			entry = null,
			gap = a
		)
	}
	return out
}

/** Whether a row is the request an attention album names: by release, else by group. */
private fun namesSameRequest(r: HistoryRow, a: AttentionAlbum): Boolean {
	val rels = listOfNotNull(r.entry?.releaseMbid, r.server?.releaseMbid, r.gap?.releaseMbid).filter { it.isNotBlank() }
	val groups = listOfNotNull(r.entry?.groupId, r.server?.groupId, r.gap?.groupId).filter { it.isNotBlank() }
	return (a.releaseMbid.isNotBlank() && a.releaseMbid in rels) || (a.groupId.isNotBlank() && a.groupId in groups)
}

private fun gapAgrees(state: String, outcome: String): Boolean = when (state) {
	"failed" -> outcome == LbBotManager.OUTCOME_FAILED
	"picking" -> outcome == LbBotManager.OUTCOME_NEEDS_PICK
	else -> false
}

/** A dismissed album fill is remembered by its release, a dismissed gap by its group ([LbBotManager.dismiss]). */
internal fun isDismissed(dismissed: Set<String>, releaseMbid: String, groupId: String, gap: Boolean): Boolean =
	dismissed.isNotEmpty() &&
		((releaseMbid.isNotBlank() && "r:$releaseMbid" in dismissed) || (gap && groupId.isNotBlank() && "g:$groupId" in dismissed))

/** The ids a dismissed ledger row is remembered by ([isDismissed]). */
internal fun dismissalIds(e: LbFillEntry): Set<String> = buildSet {
	if (e.releaseMbid.isNotBlank()) add("r:${e.releaseMbid}")
	if (e.isGap && e.groupId.isNotBlank()) add("g:${e.groupId}")
}

private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

private fun sameNames(a1: String, b1: String, a2: String, b2: String): Boolean =
	norm(b1).isNotEmpty() && norm(b1) == norm(b2) && norm(a1) == norm(a2)

/**
 * Follow-up 2026-11-14: Source history's rows, the one join the page and its menu badge both read: lb-bot's last
 * `/lb/activity` answer, its last `/lb/attention` answer (the gaps), this device's ledger and what it dismissed.
 */
fun sourceHistoryRows(
	ledger: List<LbFillEntry>,
	activity: ActivityAnswer?,
	attention: AttentionState? = null,
	dismissed: Set<String> = emptySet()
): List<HistoryRow> = mergeHistory(
	activity?.requests.orEmpty(), ledger, activity?.readAt,
	attention?.last?.albums.orEmpty(), attention?.lastAt?.takeIf { it > 0L }, dismissed
)

/** When a device row last moved: its settle, else the later of its start and its last answer. */
internal fun deviceClock(e: LbFillEntry): Long =
	if (e.settled && e.finishedAt > 0L) e.finishedAt else maxOf(e.startedAt, e.lastCheckedAt)

/**
 * Requests' two groups, and (review M1) the Need you chip's rows: every request drawn as failed or waiting on
 * a pick, from any client ([HistoryRow.needsYou]), which Requests also lists under Earlier.
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
		// Follow-up 2026-11-14: one request counts once (one gap can be several of lb-bot's rows); this device's
		// row first, so the one kept is the one with buttons.
		needYou = rows.filter { it.needsYou }
			.sortedWith(compareBy<HistoryRow> { it.entry == null }.thenByDescending { it.updatedAt })
			.distinctBy { it.identity }
			.sortedByDescending { it.updatedAt }
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
	retryable = false,
	attempts = 0,
	settled = true,
	startedAt = 0L,
	finishedAt = (updatedAt * 1000).toLong(),
	cancellable = false
)

/** How long a dismissal is kept at most: lb-bot keeps a failed fill-ledger row 24 h. */
const val DISMISSAL_KEEP_MS = 48L * 60 * 60 * 1000

/**
 * Follow-up 2026-11-14: the dismissals still needed after a good `/lb/activity` read: those lb-bot still lists
 * (in [activity], or a gap in [attention]'s albums; with no attention answer a gap's is kept), and none older than
 * [DISMISSAL_KEEP_MS].
 */
fun prunedDismissals(
	dismissed: Map<String, Long>,
	activity: List<ActivityRequest>,
	attention: List<AttentionAlbum>?,
	now: Long
): Map<String, Long> {
	val listed = buildSet {
		activity.forEach { r ->
			if (r.releaseMbid.isNotBlank()) add("r:${r.releaseMbid}")
			if (r.gapFill && r.groupId.isNotBlank()) add("g:${r.groupId}")
		}
		attention?.forEach { a ->
			if (a.releaseMbid.isNotBlank()) add("r:${a.releaseMbid}")
			if (a.kind == "gap" && a.groupId.isNotBlank()) add("g:${a.groupId}")
		}
	}
	return dismissed.filter { (id, at) ->
		now - at < DISMISSAL_KEEP_MS && (id in listed || (attention == null && id.startsWith("g:")))
	}
}
