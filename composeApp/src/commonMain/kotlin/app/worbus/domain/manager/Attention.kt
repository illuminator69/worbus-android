package app.worbus.domain.manager

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/*
 * navi-connect P6: Needs attention, as rules. lb-bot's own "to fix" (PROTOCOL §15.6), read through the
 * hub's `GET /lb/attention`, joined with this device's request ledger.
 *
 * lb-bot's to-fix number is its web UI's, `failedGaps + failedRequests + folders` (+ `imports` since
 * Worbus P4, the import records it could not finish filing) ([toFixCount]). Since the owner's second
 * re-walk (B-406, 2026-11-13 evening) the phone's To fix number, for the chip, the headline and Library
 * Matches' menu badge alike, is **Worbus for desktop's**: lb-bot's imports plus the folders waiting to be
 * filed ([libraryToFixCount], desktop `libraryToFixCount`). The failed albums, the picks and this phone's
 * own rows are still listed in the section, and not counted there; this phone's own failures and picks
 * are Source history's badge ([needsAttentionCount], desktop Activity's `attentionCount`). Artists to
 * check are Library Matches' To check chip. Where lb-bot has not answered (older, unreachable, nothing
 * read yet) the section has no number at all, as the desktop has no To fix chip then.
 */

/**
 * Review L7 (second re-walk): one of lb-bot's counts as Worbus for desktop reads it (`attentionImports`,
 * `attentionFolderCount`): a whole number above 0, else 0. A negative number, a fraction, a string or a
 * null is 0 for that count alone; it used to be taken as it came (a negative count) or to fail the
 * whole answer (a fraction), so a malformed answer showed different numbers on the two clients.
 */
internal object LbCountSerializer : KSerializer<Int> {
	override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("app.worbus.LbCount", PrimitiveKind.INT)

	override fun deserialize(decoder: Decoder): Int {
		if (decoder !is JsonDecoder) return decoder.decodeInt().coerceAtLeast(0)
		val p = decoder.decodeJsonElement() as? JsonPrimitive ?: return 0
		if (p.isString) return 0
		val d = p.doubleOrNull ?: return 0
		return if (d.isFinite() && d > 0 && d == kotlin.math.floor(d) && d <= Int.MAX_VALUE) d.toInt() else 0
	}

	override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

/** lb-bot's numbers, the keys of `/lb/attention`'s `counts`, each read by [LbCountSerializer]. */
@Serializable
data class AttentionCounts(
	@Serializable(with = LbCountSerializer::class) val failedGaps: Int = 0,
	@Serializable(with = LbCountSerializer::class) val failedRequests: Int = 0,
	@Serializable(with = LbCountSerializer::class) val folders: Int = 0,
	@Serializable(with = LbCountSerializer::class) val foldersUnidentified: Int = 0,
	@Serializable(with = LbCountSerializer::class) val picks: Int = 0,
	/**
	 * Worbus P4: import records (`_albums`) lb-bot could not finish filing, `needs_review` or `failed`.
	 * Counted in the to-fix number; absent (an older lb-bot) is 0.
	 */
	@Serializable(with = LbCountSerializer::class) val imports: Int = 0
)

/** One album lb-bot holds as failed or waiting on a pick: a review group (`gap`) or a whole-album request. */
@Serializable
data class AttentionAlbum(
	val kind: String = "",
	val key: String = "",
	val groupId: String = "",
	val releaseMbid: String = "",
	val rgid: String = "",
	val albumId: String = "",
	val artist: String = "",
	val album: String = "",
	val coverUrl: String = "",
	/** `failed` or `picking`. */
	val state: String = "",
	val missingCount: Int = 0,
	val total: Int = 0,
	/** Epoch seconds, lb-bot's clock. */
	val updatedAt: Double = 0.0
) {
	val picking: Boolean get() = state == "picking"
}

/**
 * A downloaded folder waiting to be filed. Never its name or its path (host paths, PROTOCOL §15.6):
 * what it was identified as, or nothing, and how many files it holds.
 */
@Serializable
data class AttentionFolder(
	val id: String = "",
	val fileCount: Int = 0,
	/** lb-bot's own summary of the folder's formats, e.g. "flac". */
	val formats: String = "",
	/** "Artist — Album", or "" for a folder lb-bot could not identify. */
	val matchLabel: String = "",
	/** lb-bot's word for how sure the match is: `likely`, `possible`, `unknown`, … */
	val confidence: String = "",
	val canConfirm: Boolean = false,
	val groupId: String = "",
	val releaseMbid: String = "",
	val identified: Boolean = false
)

@Serializable
data class AttentionTruncated(val albums: Int = 0, val folders: Int = 0)

/** `GET /lb/attention`'s answer. */
@Serializable
data class LbAttention(
	val version: Int = 0,
	val serverTime: Double = 0.0,
	val counts: AttentionCounts? = null,
	val albums: List<AttentionAlbum> = emptyList(),
	val folders: List<AttentionFolder> = emptyList(),
	val truncated: AttentionTruncated = AttentionTruncated()
)

/** What one attention read came to. */
sealed interface AttentionRead {
	data class Ok(val attention: LbAttention) : AttentionRead

	/** The hub or lb-bot is older than the route: a bare 404, or a 405. */
	data object Older : AttentionRead

	/** Anything else: a 5xx, a transport failure, a 200 that is not an attention answer. */
	data object Unavailable : AttentionRead
}

/**
 * One answer read as [AttentionRead]. A 404 with no `code` (or a 405) is "older": a 404 that carries
 * one is lb-bot's own answer about something else. `{}`, `[]` and any 200 without `version` and
 * `counts` is not an answer at all.
 */
fun attentionRead(status: Int, body: String): AttentionRead = when (status) {
	in 200..299 -> {
		val parsed = try {
			lbWireJson.decodeFromString(LbAttention.serializer(), body)
		} catch (e: Exception) {
			null
		}
		if (parsed != null && parsed.version >= 1 && parsed.counts != null) AttentionRead.Ok(parsed)
		else AttentionRead.Unavailable
	}
	404 -> if (bodyCode(body).isBlank()) AttentionRead.Older else AttentionRead.Unavailable
	405 -> AttentionRead.Older
	else -> AttentionRead.Unavailable
}

/** The `code` of an error body, or "" when there is none or the body is not an object. */
internal fun bodyCode(body: String): String = try {
	(lbWireJson.parseToJsonElement(body).jsonObject["code"] as? JsonPrimitive)?.contentOrNull.orEmpty()
} catch (e: Exception) {
	""
}

/**
 * lb-bot's to-fix number: failed gaps, failed requests, folders waiting to be filed and (Worbus P4)
 * imports that need the user. Worbus P1: while
 * downloading is off ([downloadsOn] false) the folders are not counted, since the page lists none (a
 * folder's one action is filing a download in lb-bot); the failed albums still are, since their rows
 * stay with Open album, Wishlist and Dismiss.
 */
fun toFixCount(c: AttentionCounts, downloadsOn: Boolean = true): Int =
	c.failedGaps + c.failedRequests + (if (downloadsOn) c.folders else 0) + c.imports

/**
 * B-406, the owner's second re-walk (2026-11-13 evening): **the To fix number, as Worbus for desktop counts
 * it** (`libraryToFixCount` in its `lbbot/lb-page-link.ts`): lb-bot's imports plus, while downloading is on,
 * the folders waiting to be filed. Its counts, not the rows listed.
 *
 * The phone said 67 where the desktop said 41 (16 imports + 25 folders) because its number was
 * [toFixCount] (which adds lb-bot's failed gaps and failed requests, and lb-bot counts a failed gap fill in
 * **both**: it is a failed group and a failed ledger row under the group's release, listed once and counted
 * twice, `_attention_view`), plus the albums waiting on a pick, plus this phone's own rows lb-bot no longer
 * lists. Not files: both apps read the same `counts.imports` and `counts.folders`.
 */
fun libraryToFixCount(c: AttentionCounts, downloadsOn: Boolean = true): Int =
	c.imports + (if (downloadsOn) c.folders else 0)

/**
 * Library Matches' menu badge: the To fix section's own number ([toFixTotal]) over the same page the
 * section draws ([attentionPage], from the same state and ledger), so the badge, the chip and the headline
 * are one number, the desktop's ([libraryToFixCount]). With no answer from lb-bot it is 0 (no badge), as
 * the desktop's Library Matches badge is.
 */
fun attentionBadge(state: AttentionState, ledger: List<LbFillEntry>, downloadsOn: Boolean = true): Int =
	(attentionPage(state.status, state.last, state.lastAt.takeIf { it > 0L }, ledger, null, downloadsOn) as? AttentionPage.Rows)
		?.let(::toFixTotal)
		?: 0

/**
 * B-406 (second re-walk): Source history's menu badge, Worbus for desktop's Activity badge
 * (`attentionCount`): settled fills that wait on the user, failed or waiting on a pick.
 * They were added into the To fix number, which the desktop never did.
 *
 * Follow-up 2026-11-14 (the owner: "no separation for requests between clients"): every request lb-bot lists
 * as failed or waiting on a pick, **from any client**, plus this phone's ledger rows lb-bot does not list: one
 * set ([HistoryRow.needsYou]), one number. The desktop's Activity still counts its own ledger only.
 *
 * Review M1: **it is the size of Source history's Need you chip** ([HistoryGroups.needYou]), counted over
 * the same rows the page draws ([mergeHistory] of lb-bot's last answer, [activity], read at its
 * [ActivityAnswer.readAt], and the ledger), so a row is counted only when the state it is drawn in is
 * failed or waiting on a pick: where lb-bot's later state differs (a retry from another client that
 * landed), lb-bot's wins for the badge as on the row. With no answer from lb-bot it is the ledger's
 * count ([needsAttentionCount]), as the page is then the ledger.
 *
 * Review L3: **not turned off with downloading**: an admin's own failures from an earlier "on" period keep
 * the badge, and Source history's row stays in the menu for them ([avatarMenuItems]), as the desktop keeps
 * Activity (`sidebar-groups.ts`: "which would otherwise be unreachable").
 */
fun sourceHistoryBadge(ledger: List<LbFillEntry>, activity: ActivityAnswer? = null): Int =
	historyGroups(mergeHistory(activity?.requests.orEmpty(), ledger, activity?.readAt)).needYou.size

/**
 * Whether the avatar's dot is drawn: something to fix, the dot not turned off, and a hub configured.
 * With navi-connect off the persisted ledger count lit the dot while the menu had no Needs attention
 * row to explain it (audit W-06).
 */
fun attentionDotShown(badge: Int, preference: Boolean, configured: Boolean, admin: Boolean = true): Boolean =
	configured && admin && preference && badge > 0

/** The menu's Needs attention badge, by the same rule as the dot: none without a configured hub. */
fun menuAttentionBadge(badge: Int, configured: Boolean): Int = if (configured) badge else 0

/**
 * A folder in one line: "Artist — Album · 11 files", or "Unidentified folder · 11 files". Built from
 * what lb-bot identified and the count alone; a folder's name never reaches this side.
 */
fun folderLine(f: AttentionFolder, unidentified: String, filesWord: (Int) -> String): String =
	"${folderTitle(f, unidentified)} · ${filesWord(f.fileCount)}"

/** What a folder is, alone: lb-bot's "Artist — Album" when it identified one, else [unidentified]. */
fun folderTitle(f: AttentionFolder, unidentified: String): String =
	f.matchLabel.takeIf { f.identified && it.isNotBlank() } ?: unidentified

/** The line under a folder's title: how many files and lb-bot's word for their format ("11 files · FLAC"). */
fun folderDetail(f: AttentionFolder, filesWord: (Int) -> String): String =
	listOfNotNull(filesWord(f.fileCount), f.formats.trim().uppercase().ifBlank { null }).joinToString(" · ")

/** Whether a server row and a device ledger row are the same fill: by release MBID, else by group id. */
internal fun sameFill(releaseMbid: String, groupId: String, entry: LbFillEntry): Boolean = when {
	releaseMbid.isNotBlank() && entry.releaseMbid.isNotBlank() -> releaseMbid == entry.releaseMbid
	groupId.isNotBlank() -> groupId == entry.groupId || (entry.isGap && groupId == entry.key)
	else -> false
}

/** A ledger row the attention page lists: settled, and a failure or a pick waiting. */
internal fun LbFillEntry.wantsAttention(): Boolean =
	settled && (outcome == LbBotManager.OUTCOME_FAILED || outcome == LbBotManager.OUTCOME_NEEDS_PICK)

/** One album row on the page. [entry] is this device's ledger row for it, when there is one. */
data class AttentionRow(
	val id: String,
	val album: AttentionAlbum,
	val entry: LbFillEntry?
) {
	/** Only a row the ledger holds can be retried here; lb-bot's own rows open the album. */
	val actionable: Boolean get() = entry != null

	/** A row only this phone's ledger holds: lb-bot does not list it (any more). */
	val isDeviceOnly: Boolean get() = id.startsWith("device:")

	/**
	 * Dismiss is for a row only this phone holds. On a row lb-bot still lists it only hid this phone's
	 * copy: the row stayed as lb-bot's, with the count and the dot unchanged (audit L4b-09).
	 */
	val dismissable: Boolean get() = isDeviceOnly
}

/** A one-line note under the page's title. */
sealed interface AttentionNotice {
	/** The hub or lb-bot is older: only this device's requests can be listed. */
	data object Older : AttentionNotice

	/** lb-bot did not answer; what is shown was read at [since] (epoch ms), or never when null. */
	data class Unreachable(val since: Long?) : AttentionNotice

	/** The caps cut rows: say how many more lb-bot holds. */
	data class Truncated(val albums: Int, val folders: Int) : AttentionNotice
}

/**
 * Re-walk NM2: whether the page says "Nothing here needs you." Only of a list lb-bot answered just now
 * with nothing in it. When it could not be read (too old for this user, unavailable, refused: an
 * [AttentionNotice.Unreachable]) or only this phone's rows are known (an older hub or lb-bot), the one
 * notice is said alone: nothing claims what was not checked.
 */
fun attentionEmptyShown(page: AttentionPage.Rows): Boolean =
	page.albums.isEmpty() && page.folders.isEmpty() && page.deviceOnly.isEmpty() && page.imports == 0 &&
		(page.artistsToCheck ?: 0) == 0 && page.toFix == 0 &&
		page.notices.none { it is AttentionNotice.Unreachable || it == AttentionNotice.Older }

sealed interface AttentionPage {
	/** Nothing read yet and nothing to show from before: not "nothing to fix". */
	data object Loading : AttentionPage

	data class Rows(
		val albums: List<AttentionRow>,
		val folders: List<AttentionFolder>,
		/** Library Matches' "to check" list, or null when it is not known. Listed, never counted. */
		val artistsToCheck: Int?,
		/**
		 * The To fix number, the desktop's ([libraryToFixCount]: lb-bot's imports and folders to file), or null
		 * when no answer is known (then the chip and the title say no number at all).
		 */
		val toFix: Int?,
		val notices: List<AttentionNotice>,
		/**
		 * With an answer from lb-bot: the failed or waiting rows this phone's ledger holds that lb-bot
		 * no longer lists (the ledger keeps a week, lb-bot a day). Listed apart, under "Only on this
		 * phone", never under lb-bot's headline as if lb-bot had counted them (audit L4b-02). Without an
		 * answer the device's rows are [albums] and there is no headline.
		 */
		val deviceOnly: List<AttentionRow> = emptyList(),
		/** Worbus P4: lb-bot's `counts.imports` from the answer shown, 0 without one. */
		val imports: Int = 0,
		/** B-406: lb-bot's `counts.folders` from the answer shown, 0 without one or while downloading is off. */
		val foldersCounted: Int = 0
	) : AttentionPage
}

/**
 * B-406: the "Downloaded, to file" group's number, in the headline's units: lb-bot's imports and folders
 * (its counts, not the rows listed). It counted rows, so one row standing for 16 imports was 1. The
 * artists to check were counted too while their row showed; since the re-walk's ruling they are Library
 * Matches' own To check chip and not listed in the To fix section at all.
 */
fun toFixSectionCount(page: AttentionPage.Rows): Int =
	// Review m9: never fewer folders than are listed (an lb-bot whose counts lack `folders` reads 0).
	page.imports + maxOf(page.foldersCounted, page.folders.size)

/**
 * The To fix section's number, for its chip, its headline, the page's first choice and Library Matches'
 * menu badge alike: **Worbus for desktop's** ([libraryToFixCount]; B-406, the owner's second re-walk), or
 * null with no answer from lb-bot (the desktop then offers no To fix chip; the phone keeps its chip, with
 * no number, over this phone's own rows).
 *
 * Review M4 had made it everything the section lists (lb-bot's [toFixCount], the picks, this phone's rows):
 * 67 on the owner's phone against the desktop's 41. Those rows are still listed, under Albums and Only on
 * this phone, each group with its own row count; this phone's failures and picks are Source history's
 * badge ([sourceHistoryBadge]).
 */
fun toFixTotal(page: AttentionPage.Rows): Int? = page.toFix

/**
 * The page, from the last read, the last good answer ([last], read at [lastAt]) and the ledger.
 *
 * - A good answer lists lb-bot's rows; a ledger row the server also lists appears once, lb-bot's
 *   state with the device's [AttentionRow.entry]. The device's own failed or waiting rows lb-bot no
 *   longer lists are [AttentionPage.Rows.deviceOnly] (lb-bot forgets a failed request after a day;
 *   the ledger keeps a week).
 * - A failed read keeps the last good answer and says since when; with none, the device's rows.
 * - An older hub or lb-bot lists the device's rows and says so. No read at all is [AttentionPage.Loading].
 */
fun attentionPage(
	read: AttentionRead?,
	last: LbAttention?,
	lastAt: Long?,
	ledger: List<LbFillEntry>,
	artistsToCheck: Int?,
	/**
	 * Worbus P1: lb-bot can download. Off, no folder is listed: a folder row's only action is filing it
	 * in lb-bot (`#/place/<id>`), the last step of a download. lb-bot's count is still its own.
	 */
	downloadsOn: Boolean = true
): AttentionPage {
	val answer = when (read) {
		is AttentionRead.Ok -> read.attention
		AttentionRead.Unavailable -> last
		AttentionRead.Older -> null
		null -> last
	}
	if (read == null && answer == null) return AttentionPage.Loading

	val device = ledger.filter { it.wantsAttention() }
	val notices = buildList {
		if (read == AttentionRead.Older) add(AttentionNotice.Older)
		if (read == AttentionRead.Unavailable) add(AttentionNotice.Unreachable(lastAt.takeIf { last != null }))
		val cut = answer?.truncated
		if (cut != null && (cut.albums > 0 || cut.folders > 0)) add(AttentionNotice.Truncated(cut.albums, cut.folders))
	}
	if (answer == null) {
		return AttentionPage.Rows(
			albums = device.sortedForAttention().map { it.asAttentionRow() },
			folders = emptyList(),
			artistsToCheck = artistsToCheck,
			toFix = null,
			notices = notices
		)
	}

	val claimed = mutableSetOf<String>()
	val serverRows = answer.albums.mapNotNull { album ->
		val entry = ledger.firstOrNull { it.key !in claimed && sameFill(album.releaseMbid, album.groupId, it) }
		if (entry != null) claimed += entry.key
		// The ledger is newer than any read for the rows it names (a fill frame or a poll moves it
		// at once): a fill this device has started again is running, and no longer waits on anyone.
		if (entry != null && !entry.settled) return@mapNotNull null
		AttentionRow(id = "${album.kind}:${album.key.ifBlank { album.groupId.ifBlank { album.releaseMbid } }}", album = album, entry = entry)
	}
	val deviceOnly = device.filter { it.key !in claimed }.sortedForAttention().map { it.asAttentionRow() }
	return AttentionPage.Rows(
		albums = serverRows,
		folders = if (downloadsOn) answer.folders else emptyList(),
		artistsToCheck = artistsToCheck,
		toFix = answer.counts?.let { libraryToFixCount(it, downloadsOn) },
		notices = notices,
		deviceOnly = deviceOnly,
		imports = answer.counts?.imports ?: 0,
		foldersCounted = if (downloadsOn) answer.counts?.folders ?: 0 else 0
	)
}

/** Failed first, then waiting on a pick; newest first inside each. */
private fun List<LbFillEntry>.sortedForAttention(): List<LbFillEntry> =
	sortedWith(compareBy<LbFillEntry> { if (it.outcome == LbBotManager.OUTCOME_FAILED) 0 else 1 }
		.thenByDescending { it.finishedAt })

/** A ledger row as the page's album row, named and keyed as lb-bot would. */
internal fun LbFillEntry.asAttentionRow(): AttentionRow = AttentionRow(
	id = "device:$key",
	album = AttentionAlbum(
		kind = if (isGap) "gap" else "request",
		key = key,
		groupId = groupId,
		releaseMbid = releaseMbid,
		rgid = rgid,
		artist = artist,
		album = album,
		coverUrl = coverUrl,
		state = if (outcome == LbBotManager.OUTCOME_NEEDS_PICK) "picking" else "failed",
		total = total,
		updatedAt = finishedAt / 1000.0
	),
	entry = this
)

/**
 * Whether to read `/lb/attention` now: never while a read is out, always when forced, otherwise at
 * most once in [ATTENTION_MIN_INTERVAL_MS]. [readAt] 0 is "never read".
 */
fun shouldRefreshAttention(
	now: Long,
	readAt: Long,
	force: Boolean,
	inFlight: Boolean,
	/** NB3: lb-bot lists `/lb/attention` as admin; a non-admin never asks (it was a 403 per visit). */
	admin: Boolean = true
): Boolean = when {
	!admin -> false
	inFlight -> false
	force -> true
	readAt <= 0L -> true
	else -> now - readAt >= ATTENTION_MIN_INTERVAL_MS
}

const val ATTENTION_MIN_INTERVAL_MS = 30_000L

/**
 * Whether a `fill` frame forces a read: one that names a failed state (the count may just have
 * grown) or a pick waiting. Its `state` for an album fill, its `status` for a gap summary.
 */
fun fillFrameForcesAttention(frame: JsonObject): Boolean {
	fun str(key: String) = (frame[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
	return str("state") in ATTENTION_FORCING_STATES || str("status") in ATTENTION_FORCING_STATES
}

private val ATTENTION_FORCING_STATES = setOf("failed", "needs_match", "picking")

/**
 * What [LbBotManager.attention] holds: the last good answer and when it was read ([lastAt]), when
 * anything was last read ([readAt], for the 30 s rule), and how the last read went.
 */
data class AttentionState(
	val last: LbAttention? = null,
	val lastAt: Long = 0L,
	val readAt: Long = 0L,
	val status: AttentionRead? = null
)

/**
 * The next [AttentionState] after a read. A good answer replaces the last; "older" forgets it (the
 * hub or lb-bot was replaced by one without the route, and an answer from before is no longer the
 * truth); a failure keeps it.
 */
fun nextAttentionState(prev: AttentionState, read: AttentionRead, now: Long): AttentionState = when (read) {
	is AttentionRead.Ok -> AttentionState(last = read.attention, lastAt = now, readAt = now, status = read)
	AttentionRead.Older -> AttentionState(last = null, lastAt = 0L, readAt = now, status = read)
	AttentionRead.Unavailable -> prev.copy(status = read, readAt = now)
}
