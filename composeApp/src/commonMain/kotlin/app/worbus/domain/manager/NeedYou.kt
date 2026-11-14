package app.worbus.domain.manager

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/*
 * Source history's Need you: one set, one number, for the chip and the menu badge, from any client (the owner,
 * 2026-11-13: "no separation for requests between clients"). A port of Worbus for desktop's rule
 * (`feishin/src/renderer/features/lbbot/utils/need-you.ts`, `needYouItems`, at `51a62a38c`), so both clients count
 * the same set. Keep the two in step.
 *
 * Three sources, in the vocabulary each actually speaks (lb-bot's `_activity_view` / `_attention_view`; the desktop
 * review's H1):
 * - `/lb/activity`: lb-bot's album-fill LEDGER, every client's requests. Its states are searching, queued,
 *   downloading, placing, placed, verified and the two side exits `failed` and `needs_match`, both needing someone.
 *   Never `picking`. A gap fill writes a row only once its placement runs, so for a gap the row is the last fill that
 *   reached placement, not the gap's state now.
 * - `/lb/attention`'s `albums`: review groups whose own status is `failed` or `picking` NOW (`kind: gap`), and failed
 *   requests (`kind: request`). Another client's pick comes from here.
 * - this device's ledger.
 *
 * Rows of one request are joined into one item: by release, by group, by release group, and (only for a gap row lb-bot
 * names but can no longer place in a group) by artist and album. Then ONE state decides each item:
 * - lb-bot's side: the gap's own status (attention) when it lists one, unless a fill of it is in flight and newer;
 *   otherwise lb-bot's newest row. A finished fill row never overrules a gap's status now.
 * - lb-bot wins over this device's row only when its row is the NEWER state of the request (timestamps: lb-bot's
 *   `updatedAt` against the device row's settle, else start).
 * - a Dismiss (or Add to wishlist) here hides lb-bot's rows of that request that are not newer than the dismissal.
 * With no answer from lb-bot (an older one, a non-admin, not read yet) it is the ledger's set.
 */

/** A request this device dismissed (Dismiss, or Add to wishlist), remembered by its identity. */
@Serializable
data class DismissedRequest(
	val releaseMbid: String = "",
	val groupId: String = "",
	val rgid: String = "",
	val artist: String = "",
	val album: String = "",
	val isGap: Boolean = false,
	/** Epoch ms, this device's clock. */
	val at: Long = 0L
)

/** What [LbBotManager.dismiss] remembers of a ledger row (the desktop's `dismiss`). */
fun dismissalOf(e: LbFillEntry, now: Long): DismissedRequest =
	if (e.isGap) DismissedRequest(groupId = e.groupId, artist = e.artist, album = e.album, isGap = true, at = now)
	else DismissedRequest(e.releaseMbid, e.groupId, e.rgid, e.artist, e.album, false, now)

/** One lb-bot row, from either read, in one shape. */
data class NeedYouServerEntry(
	val releaseMbid: String,
	val groupId: String,
	val rgid: String,
	val artist: String,
	val album: String,
	val state: String,
	/** A gap: an attention `gap` row, or an activity row lb-bot names as a gap fill. */
	val gap: Boolean,
	val fromAttention: Boolean,
	val failureKind: String = "",
	val reason: String = "",
	/** Stage 2 M4: the library album lb-bot names (the desktop's `albumId`), or "". */
	val albumId: String = "",
	/** Review L3: null for an attention album, which says nothing (read as [gap], the desktop's `retryable ?? gap`). */
	val retryable: Boolean? = null,
	/** Epoch seconds, lb-bot's clock. */
	val updatedAt: Double,
	/** The row it came from, for Requests' words (percent, totals): exactly one of the two is set. */
	val activity: ActivityRequest? = null,
	val attention: AttentionAlbum? = null
) {
	val updatedAtMs: Long get() = (updatedAt * 1000).toLong()
}

/** One Need you item. [ledger]: this device's row of it (its buttons act on it). [server]: lb-bot's row when it decided. */
data class NeedYouItem(
	val key: String,
	val ledger: LbFillEntry?,
	val server: NeedYouServerEntry?,
	/** Stage 2 M4: the library album lb-bot names for the request. */
	val album: RequestAlbum = RequestAlbum()
)

/** What lb-bot's state means for Need you: failed, waiting on a pick, or nobody's. */
fun serverNeedOutcome(state: String): String? = when (state) {
	"failed", "needs_match" -> LbBotManager.OUTCOME_FAILED
	"picking" -> LbBotManager.OUTCOME_NEEDS_PICK
	else -> null
}

/** The fill-ledger states of a fill in flight (`_album_fill_view`). */
internal val LIVE_STATES = setOf("downloading", "placed", "placing", "queued", "searching")

/**
 * Review L3: the inputs as the desktop allowlists them (`activityRequests`, `attentionAlbums`): an activity row with
 * no state, or with neither a release nor a group to join it by, is dropped; so is an attention album of another
 * kind, with no state, or with neither id.
 */
internal fun ActivityRequest.kept(): Boolean = state.isNotEmpty() && (releaseMbid.isNotEmpty() || groupId.isNotEmpty())

internal fun AttentionAlbum.kept(): Boolean =
	(kind == "gap" || kind == "request") && state.isNotEmpty() && (groupId.isNotEmpty() || releaseMbid.isNotEmpty())

private fun ActivityRequest.entry() = NeedYouServerEntry(
	releaseMbid, groupId, rgid, artist, album, state, gap = gapFill, fromAttention = false,
	failureKind = failureKind, reason = reason, albumId = albumId, retryable = retryable, updatedAt = updatedAt, activity = this
)

private fun AttentionAlbum.entry() = NeedYouServerEntry(
	releaseMbid, groupId, rgid, artist, album, state, gap = kind == "gap", fromAttention = true, albumId = albumId,
	updatedAt = updatedAt, attention = this
)

/** When a device row last moved, as the desktop's `sortAt`: its settle, else its start. */
internal fun LbFillEntry.sortAt(): Long = if (finishedAt > 0L) finishedAt else startedAt

private fun norm(s: String) = s.trim().lowercase()
private fun nameId(artist: String, album: String) =
	if (artist.isNotBlank() && album.isNotBlank()) "n:${norm(artist)}\u0000${norm(album)}" else ""

private fun ids(releaseMbid: String, groupId: String, rgid: String): List<String> = buildList {
	if (releaseMbid.isNotEmpty()) add("r:$releaseMbid")
	if (groupId.isNotEmpty()) add("g:$groupId")
	if (rgid.isNotEmpty()) add("rg:$rgid")
}

private fun serverEntries(activity: List<ActivityRequest>?, attention: List<AttentionAlbum>?) =
	activity.orEmpty().filter { it.kept() }.map { it.entry() } + attention.orEmpty().filter { it.kept() }.map { it.entry() }

private sealed interface Node {
	data class Ledger(val row: LbFillEntry) : Node
	data class Server(val entry: NeedYouServerEntry) : Node
	data class Dismissed(val d: DismissedRequest) : Node
}

/** One request: every row of it, joined ([requestGroups]). [ids]: every identity its rows carry. */
internal class RequestGroup(
	val here: List<LbFillEntry>,
	val theirs: List<NeedYouServerEntry>,
	val dismissedAt: Long,
	val ids: List<String>
)

/**
 * Rows of one request joined into one group (union-find): by release, by group, by release group, and, only for a
 * gap row lb-bot names but can no longer place in a group, by artist and album. Need you and Requests read the same
 * groups (review L2), lb-bot's rows first, in order, then this device's, then the dismissals.
 */
internal fun requestGroups(
	activity: List<ActivityRequest>?,
	attention: List<AttentionAlbum>?,
	dismissed: List<DismissedRequest>,
	ledger: List<LbFillEntry>
): List<RequestGroup> {
	val server = serverEntries(activity, attention)
	// A gap lb-bot names but no longer holds a group for: joined by its names, and only to a gap row or dismissal.
	val orphanNames = server.filter { it.gap && it.groupId.isEmpty() }.map { nameId(it.artist, it.album) }
		.filter { it.isNotEmpty() }.toSet()
	fun named(isGap: Boolean, artist: String, album: String): List<String> {
		val id = if (isGap) nameId(artist, album) else ""
		return if (id.isNotEmpty() && id in orphanNames) listOf(id) else emptyList()
	}

	val nodes: List<Node> = server.map { Node.Server(it) } + ledger.map { Node.Ledger(it) } + dismissed.map { Node.Dismissed(it) }
	val nodeIds = nodes.map { node ->
		when (node) {
			is Node.Ledger -> node.row.let { ids(it.releaseMbid, it.groupId, it.rgid) + named(it.isGap, it.artist, it.album) }
			is Node.Server -> node.entry.let {
				ids(it.releaseMbid, it.groupId, it.rgid) + (if (it.gap && it.groupId.isEmpty()) named(true, it.artist, it.album) else emptyList())
			}
			is Node.Dismissed -> node.d.let { ids(it.releaseMbid, it.groupId, it.rgid) + named(it.isGap, it.artist, it.album) }
		}
	}

	// Union-find over the identities.
	val parent = IntArray(nodes.size) { it }
	fun find(i0: Int): Int {
		var i = i0
		while (parent[i] != i) {
			parent[i] = parent[parent[i]]
			i = parent[i]
		}
		return i
	}
	val owner = HashMap<String, Int>()
	nodeIds.forEachIndexed { i, list ->
		for (id in list) {
			val j = owner[id]
			if (j == null) owner[id] = i else parent[find(i)] = find(j)
		}
	}
	val groups = LinkedHashMap<Int, MutableList<Int>>()
	nodes.indices.forEach { i -> groups.getOrPut(find(i)) { mutableListOf() } += i }

	return groups.values.map { members ->
		val here = mutableListOf<LbFillEntry>()
		val theirs = mutableListOf<NeedYouServerEntry>()
		var dismissedAt = Long.MIN_VALUE
		for (i in members) when (val node = nodes[i]) {
			is Node.Ledger -> here += node.row
			is Node.Server -> theirs += node.entry
			is Node.Dismissed -> dismissedAt = maxOf(dismissedAt, node.d.at)
		}
		RequestGroup(here, theirs, dismissedAt, members.flatMap { nodeIds[it] })
	}
}

/** Which state decides a request: this device's newest row ([mine]), lb-bot's side ([lbbot]), and which one won. */
internal class RequestDecision(val mine: LbFillEntry?, val lbbot: NeedYouServerEntry?) {
	/** lb-bot decides only when its row is the NEWER state of the request (timestamps). */
	val lbbotDecides: Boolean = lbbot != null && (mine == null || lbbot.updatedAtMs > mine.sortAt())
}

/**
 * The desktop's decision: lb-bot's side is the gap's own status (attention) when it lists one, unless a fill of it in
 * flight is newer; otherwise lb-bot's newest row.
 */
internal fun decide(here: List<LbFillEntry>, theirs: List<NeedYouServerEntry>): RequestDecision {
	val mine = here.maxByOrNull { it.sortAt() }
	val gapNow = theirs.filter { it.fromAttention && it.gap }.maxByOrNull { it.updatedAt }
	val live = theirs.filter { !it.fromAttention && it.state in LIVE_STATES }.maxByOrNull { it.updatedAt }
	val lbbot = if (gapNow != null) {
		if (live != null && live.updatedAt > gapNow.updatedAt) live else gapNow
	} else theirs.maxByOrNull { it.updatedAt }
	return RequestDecision(mine, lbbot)
}

/**
 * Need you's items, newest first. [activity] / [attention]: lb-bot's last good answers, null when there is none.
 * The desktop's `needYouItems`, case for case.
 */
fun needYouItems(
	activity: List<ActivityRequest>?,
	attention: List<AttentionAlbum>?,
	dismissed: List<DismissedRequest>,
	ledger: List<LbFillEntry>
): List<NeedYouItem> {
	val out = mutableListOf<Pair<Long, NeedYouItem>>()
	for (g in requestGroups(activity, attention, dismissed, ledger)) {
		// What was there when this device dismissed the request stays dismissed here.
		val d = decide(g.here.filter { it.sortAt() > g.dismissedAt }, g.theirs.filter { it.updatedAtMs > g.dismissedAt })
		val mine = d.mine
		val lbbot = d.lbbot
		val counts = if (d.lbbotDecides) serverNeedOutcome(lbbot!!.state) != null
		else mine != null && mine.settled &&
			(mine.outcome == LbBotManager.OUTCOME_FAILED || mine.outcome == LbBotManager.OUTCOME_NEEDS_PICK)
		if (!counts) continue
		// A request this device holds no row of is keyed by its smallest identity: the same on every read.
		val key = if (mine != null) "here:${mine.key}" else "lb:${g.ids.sorted().firstOrNull().orEmpty()}"
		out += (if (d.lbbotDecides) lbbot!!.updatedAtMs else mine!!.sortAt()) to
			NeedYouItem(key, mine, if (d.lbbotDecides) lbbot else null, requestAlbum(g.theirs, lbbot))
	}
	return out.sortedWith(compareByDescending<Pair<Long, NeedYouItem>> { it.first }.thenBy { it.second.key }).map { it.second }
}

/**
 * The dismissals lb-bot still lists something for. Only a full answer forgets one: with either read missing (not
 * read yet, failed, an older lb-bot) every dismissal is kept. The desktop's `dismissalsStillListed`.
 */
fun dismissalsStillListed(
	dismissed: List<DismissedRequest>,
	activity: List<ActivityRequest>?,
	attention: List<AttentionAlbum>?
): List<DismissedRequest> {
	if (activity == null || attention == null) return dismissed
	val listed = serverEntries(activity, attention).flatMap {
		ids(it.releaseMbid, it.groupId, it.rgid) + (if (it.gap && it.groupId.isEmpty()) listOf(nameId(it.artist, it.album)) else emptyList())
	}.toSet()
	return dismissed.filter { d ->
		(ids(d.releaseMbid, d.groupId, d.rgid) + (if (d.isGap) listOf(nameId(d.artist, d.album)) else emptyList()))
			.any { it.isNotEmpty() && it in listed }
	}
}

/** Bounds that only stop a never-answering lb-bot growing the dismissals (the desktop's). */
const val DISMISSED_RETAIN_MS = 30L * 24 * 60 * 60 * 1000
const val DISMISSED_MAX = 200

/** The dismissals within [DISMISSED_RETAIN_MS] of [now], the newest [DISMISSED_MAX] (the desktop's `pruneDismissed`). */
fun boundDismissals(dismissed: List<DismissedRequest>, now: Long): List<DismissedRequest> =
	dismissed.filter { now - it.at < DISMISSED_RETAIN_MS }.takeLast(DISMISSED_MAX)

/**
 * `lbDismissedRequests` as stored: a JSON list of [DismissedRequest]. Review L4: also the first format of this
 * branch (`0c1f5593`, run on the VM's AVD), an object of `"r:<release>"` (an album fill) or `"g:<group>"` (a gap)
 * to epoch ms, read into the same requests rather than lost. Anything else is none.
 */
fun decodeDismissedRequests(stored: String): List<DismissedRequest> = try {
	when (val e = lbWireJson.parseToJsonElement(stored)) {
		is JsonArray -> lbWireJson.decodeFromJsonElement(ListSerializer(DismissedRequest.serializer()), e)
		is JsonObject -> e.mapNotNull { (k, v) ->
			val at = (v as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
			when {
				k.startsWith("r:") && k.length > 2 -> DismissedRequest(releaseMbid = k.drop(2), at = at)
				k.startsWith("g:") && k.length > 2 -> DismissedRequest(groupId = k.drop(2), isGap = true, at = at)
				else -> null
			}
		}
		else -> emptyList()
	}
} catch (e: Exception) {
	emptyList()
}

/**
 * A request only lb-bot knows, as a ledger row for the fill vocabulary: lb-bot's words, settled, no buttons (the
 * desktop's `serverLedgerRow`).
 */
fun NeedYouServerEntry.asLedgerRow(): LbFillEntry = LbFillEntry(
	key = releaseMbid.ifBlank { groupId.ifBlank { rgid } },
	isGap = gap,
	rgid = rgid,
	groupId = groupId,
	releaseMbid = releaseMbid,
	artist = artist,
	album = album,
	quality = "",
	state = state,
	outcome = serverNeedOutcome(state) ?: LbBotManager.OUTCOME_FAILED,
	reason = reason,
	percent = 0,
	done = 0,
	total = 0,
	mp3WouldHelp = false,
	failureKind = failureKind,
	retryable = retryable ?: gap,
	attempts = 0,
	settled = true,
	startedAt = 0L,
	finishedAt = updatedAtMs,
	cancellable = false
)

/** lb-bot's own row of a request, worded from the read it came from: Requests and Need you draw it the same way. */
internal fun NeedYouServerEntry.ownRow(): LbFillEntry = activity?.asLedgerRow() ?: attention?.asLedgerRow() ?: asLedgerRow()

/**
 * Review L1, the desktop's `drawnRow`: this device's row with lb-bot's newer state, words and failure laid on it. It
 * keeps its key, kind and peers, so its buttons (Dismiss at least) act on this device's row.
 */
fun LbFillEntry.withServerState(e: NeedYouServerEntry): LbFillEntry = copy(
	attempts = 0,
	cancellable = false,
	failureKind = e.failureKind,
	mp3WouldHelp = false,
	outcome = serverNeedOutcome(e.state) ?: outcome,
	percent = 0,
	reason = e.reason,
	retryable = e.retryable ?: retryable,
	retryAt = 0L,
	settled = true,
	finishedAt = e.updatedAtMs,
	state = e.state,
	verifyGaveUp = false
)

/**
 * The row an item is drawn as (review L1, the desktop's `drawnRow`): this device's row when its state decided; this
 * device's row with lb-bot's state laid on it ([withServerState]) when lb-bot's newer state decided, so its buttons
 * stay; lb-bot's own row (Open album only) for a request this device holds no row of.
 */
fun NeedYouItem.drawn(): LbFillEntry {
	val e = server ?: return ledger!!
	val mine = ledger ?: return e.ownRow()
	return mine.withServerState(e)
}

/** Need you's items as Source history's rows (each drawn by [drawn]). */
fun NeedYouItem.asHistoryRow(): HistoryRow {
	val row = drawn()
	return HistoryRow(
		key = key,
		artist = row.artist.ifBlank { ledger?.artist.orEmpty() },
		album = row.album.ifBlank { ledger?.album.orEmpty() },
		rgid = row.rgid.ifBlank { ledger?.rgid.orEmpty() },
		state = row.state,
		percent = null,
		updatedAt = server?.updatedAtMs ?: ledger?.sortAt() ?: 0L,
		fromDevice = ledger != null,
		entry = ledger,
		server = server?.activity,
		drawn = row,
		overlaid = ledger != null && server != null,
		libraryAlbum = album
	)
}
