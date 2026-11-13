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
	 * since, or a row that settled here after lb-bot's answer was read.
	 */
	val ledgerWins: Boolean = false
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
			val s = server ?: return entry!!
			val e = entry ?: return s.asLedgerRow()
			if (!e.settled || ledgerWins || serverAgrees(s.state, e.outcome)) return e
			return s.asLedgerRow()
		}

	/**
	 * Review M1: a request drawn as failed or waiting on a pick: Source history's Need you chip and its menu
	 * badge ([sourceHistoryBadge]), desktop `needsYou`.
	 *
	 * Follow-up 2026-11-14 (the owner: "no separation for requests between clients"): **any client's**. A row
	 * only lb-bot lists (another client's request, or one this phone's ledger has forgotten) counts when lb-bot
	 * lists it as failed, `needs_match` (its files are in and wait to be matched) or `picking`; this phone's
	 * rows lb-bot does not list count by the ledger, as before. lb-bot's later state still wins ([shown]).
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
fun mergeHistory(server: List<ActivityRequest>, device: List<LbFillEntry>, readAt: Long? = null): List<HistoryRow> {
	val claimed = mutableSetOf<String>()
	val fromServer = server.map { s ->
		val entry = device.firstOrNull { it.key !in claimed && sameFill(s.releaseMbid, s.groupId, it) }
		if (entry != null) claimed += entry.key
		// B2 (audit L4b-01): a running ledger row against a finished lb-bot row is a Retry lb-bot has
		// not been read since. The ledger is newer for the rows it names (a fill frame or a poll moves
		// it at once), as on Needs attention: the row is running, in the device's own state.
		val retried = entry != null && !entry.settled && s.state in TERMINAL_SERVER_STATES
		val settledSince = entry != null && entry.settled && readAt != null && entry.finishedAt > readAt
		val state = if (retried || settledSince) entry.state else s.state
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
			ledgerWins = retried || settledSince
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
	return fromServer + deviceOnly
}

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
		needYou = rows.filter { it.needsYou }.sortedByDescending { it.updatedAt }
	)
}

/** The Transfers chip is drawn only when lb-bot lists any. */
fun transfersShown(transfers: List<ActivityTransfer>): Boolean = transfers.isNotEmpty()

/** `picking` is not one of `/lb/activity`'s states today (a pick is a gap group's); read as waiting on a pick if it comes. */
private val SETTLED_SERVER_STATES = setOf("verified", "failed", "cancelled", "needs_match", "picking")

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
			"picking" -> LbBotManager.OUTCOME_NEEDS_PICK
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
