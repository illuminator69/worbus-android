package app.worbus.domain.manager

import kotlinx.serialization.Serializable

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
	val retryable: Boolean = false,
	/** Epoch seconds, lb-bot's clock. */
	val updatedAt: Double
) {
	val updatedAtMs: Long get() = (updatedAt * 1000).toLong()
}

/** One Need you item. [ledger]: this device's row of it (its buttons act on it). [server]: lb-bot's row when it decided. */
data class NeedYouItem(val key: String, val ledger: LbFillEntry?, val server: NeedYouServerEntry?)

/** What lb-bot's state means for Need you: failed, waiting on a pick, or nobody's. */
fun serverNeedOutcome(state: String): String? = when (state) {
	"failed", "needs_match" -> LbBotManager.OUTCOME_FAILED
	"picking" -> LbBotManager.OUTCOME_NEEDS_PICK
	else -> null
}

/** The fill-ledger states of a fill in flight (`_album_fill_view`). */
private val LIVE_STATES = setOf("downloading", "placed", "placing", "queued", "searching")

private fun ActivityRequest.entry() = NeedYouServerEntry(
	releaseMbid, groupId, rgid, artist, album, state, gap = gapFill, fromAttention = false,
	failureKind = failureKind, reason = reason, retryable = retryable, updatedAt = updatedAt
)

private fun AttentionAlbum.entry() = NeedYouServerEntry(
	releaseMbid, groupId, rgid, artist, album, state, gap = kind == "gap", fromAttention = true, updatedAt = updatedAt
)

/** When a device row last moved, as the desktop's `sortAt`: its settle, else its start. */
private fun LbFillEntry.sortAt(): Long = if (finishedAt > 0L) finishedAt else startedAt

private fun norm(s: String) = s.trim().lowercase()
private fun nameId(artist: String, album: String) =
	if (artist.isNotBlank() && album.isNotBlank()) "n:${norm(artist)}\u0000${norm(album)}" else ""

private fun ids(releaseMbid: String, groupId: String, rgid: String): List<String> = buildList {
	if (releaseMbid.isNotEmpty()) add("r:$releaseMbid")
	if (groupId.isNotEmpty()) add("g:$groupId")
	if (rgid.isNotEmpty()) add("rg:$rgid")
}

private fun serverEntries(activity: List<ActivityRequest>?, attention: List<AttentionAlbum>?) =
	activity.orEmpty().map { it.entry() } + attention.orEmpty().map { it.entry() }

private sealed interface Node {
	data class Ledger(val row: LbFillEntry) : Node
	data class Server(val entry: NeedYouServerEntry) : Node
	data class Dismissed(val d: DismissedRequest) : Node
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
	val server = serverEntries(activity, attention)
	// A gap lb-bot names but no longer holds a group for: joined by its names, and only to a gap row or dismissal.
	val orphanNames = server.filter { it.gap && it.groupId.isEmpty() }.map { nameId(it.artist, it.album) }
		.filter { it.isNotEmpty() }.toSet()
	fun named(isGap: Boolean, artist: String, album: String): List<String> {
		val id = if (isGap) nameId(artist, album) else ""
		return if (id.isNotEmpty() && id in orphanNames) listOf(id) else emptyList()
	}

	val nodes: List<Node> = ledger.map { Node.Ledger(it) } + server.map { Node.Server(it) } + dismissed.map { Node.Dismissed(it) }
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

	val out = mutableListOf<Pair<Long, NeedYouItem>>()
	for (members in groups.values) {
		val here = mutableListOf<LbFillEntry>()
		val theirs = mutableListOf<NeedYouServerEntry>()
		var dismissedAt = Long.MIN_VALUE
		for (i in members) when (val node = nodes[i]) {
			is Node.Ledger -> here += node.row
			is Node.Server -> theirs += node.entry
			is Node.Dismissed -> dismissedAt = maxOf(dismissedAt, node.d.at)
		}
		// What was there when this device dismissed the request stays dismissed here.
		val shownHere = here.filter { it.sortAt() > dismissedAt }
		val shownTheirs = theirs.filter { it.updatedAtMs > dismissedAt }
		val mine = shownHere.maxByOrNull { it.sortAt() }
		val gapNow = shownTheirs.filter { it.fromAttention && it.gap }.maxByOrNull { it.updatedAt }
		val live = shownTheirs.filter { !it.fromAttention && it.state in LIVE_STATES }.maxByOrNull { it.updatedAt }
		val lbbot = if (gapNow != null) {
			if (live != null && live.updatedAt > gapNow.updatedAt) live else gapNow
		} else shownTheirs.maxByOrNull { it.updatedAt }
		val lbbotDecides = lbbot != null && (mine == null || lbbot.updatedAtMs > mine.sortAt())
		val counts = if (lbbotDecides) serverNeedOutcome(lbbot!!.state) != null
		else mine != null && mine.settled &&
			(mine.outcome == LbBotManager.OUTCOME_FAILED || mine.outcome == LbBotManager.OUTCOME_NEEDS_PICK)
		if (!counts) continue
		// A request this device holds no row of is keyed by its smallest identity: the same on every read.
		val key = if (mine != null) "here:${mine.key}" else "lb:${members.flatMap { nodeIds[it] }.sorted().firstOrNull().orEmpty()}"
		out += (if (lbbotDecides) lbbot!!.updatedAtMs else mine!!.sortAt()) to
			NeedYouItem(key, mine, if (lbbotDecides) lbbot else null)
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

/** A request only lb-bot knows, as a ledger row for the fill vocabulary: lb-bot's words, settled, no buttons. */
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
	retryable = retryable,
	attempts = 0,
	settled = true,
	startedAt = 0L,
	finishedAt = updatedAtMs,
	cancellable = false
)

/**
 * The row an item is drawn as: this device's row when its state decided, or when lb-bot's newer state says the same
 * (so its buttons stay); lb-bot's row when lb-bot's newer state differs (Open album only, as any row lb-bot alone
 * knows).
 */
fun NeedYouItem.drawn(): LbFillEntry {
	val e = server ?: return ledger!!
	val mine = ledger ?: return e.asLedgerRow()
	return if (mine.settled && serverNeedOutcome(e.state) == mine.outcome) mine else e.asLedgerRow()
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
		updatedAt = server?.updatedAtMs ?: ledger?.let { if (it.finishedAt > 0L) it.finishedAt else it.startedAt } ?: 0L,
		fromDevice = ledger != null,
		entry = ledger,
		drawn = row
	)
}
