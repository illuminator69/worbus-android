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
 * filed ([libraryToFixCount], desktop `libraryToFixCount`). B-450 (2026-11-14): the failed albums, the picks
 * and this phone's own rows are no longer listed in the section either; they are Source history's Need you and
 * its badge ([needsAttentionCount], desktop Activity's `attentionCount`). Artists to
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
	/**
	 * Review L3 (follow-ups): null when lb-bot sent no `albums` (an lb-bot from before the rows): "nothing to say",
	 * never "nothing listed", as the desktop's `attentionAlbums` reads it. The To fix page reads it as none.
	 */
	val albums: List<AttentionAlbum>? = null,
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
	// Review M3 (follow-ups), the desktop's `screenRead`: the hub calling lb-bot too old for this user is "no
	// answer" for this read as for `/lb/activity` (Need you is then the ledger's), not a failure that keeps the last.
	503 -> if (bodyCode(body) == "lbbot_too_old") AttentionRead.Older else AttentionRead.Unavailable
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
 * section draws ([attentionPage], from the same state), so the badge, the chip and the headline
 * are one number, the desktop's ([libraryToFixCount]). With no answer from lb-bot it is 0 (no badge), as
 * the desktop's Library Matches badge is.
 */
fun attentionBadge(state: AttentionState, downloadsOn: Boolean = true): Int =
	(attentionPage(state.status, state.last, state.lastAt.takeIf { it > 0L }, null, downloadsOn) as? AttentionPage.Rows)
		?.let(::toFixTotal)
		?: 0

/**
 * B-406 (second re-walk): Source history's menu badge, Worbus for desktop's Activity badge
 * (`attentionCount`): settled fills that wait on the user, failed or waiting on a pick.
 * They were added into the To fix number, which the desktop never did.
 *
 * Follow-up 2026-11-14 (the owner: "no separation for requests between clients"): every request lb-bot lists
 * as failed or waiting on a pick, **from any client**, plus this phone's ledger rows lb-bot does not list: one
 * set, one number, by the desktop's rule ([needYouItems], a port of `need-you.ts`).
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
fun sourceHistoryBadge(
	ledger: List<LbFillEntry>,
	activity: ActivityAnswer? = null,
	attention: AttentionState? = null,
	dismissed: List<DismissedRequest> = emptyList()
): Int = needYouItems(activity?.requests, attention?.last?.albums, dismissed, ledger).size

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

/** A one-line note under the page's title. */
sealed interface AttentionNotice {
	/** The hub or lb-bot is older: it lists nothing to fix. */
	data object Older : AttentionNotice

	/** lb-bot did not answer; what is shown was read at [since] (epoch ms), or never when null. */
	data class Unreachable(val since: Long?) : AttentionNotice

	/** The cap cut folders: say how many more lb-bot holds (B-450: its album rows are Source history's). */
	data class Truncated(val folders: Int) : AttentionNotice
}

/**
 * Re-walk NM2: whether the page says "Nothing here needs you." Only of a list lb-bot answered just now
 * with nothing in it. When it could not be read (too old for this user, unavailable, refused: an
 * [AttentionNotice.Unreachable]) or only this phone's rows are known (an older hub or lb-bot), the one
 * notice is said alone: nothing claims what was not checked.
 */
fun attentionEmptyShown(page: AttentionPage.Rows): Boolean =
	page.folders.isEmpty() && page.imports == 0 &&
		(page.artistsToCheck ?: 0) == 0 && page.toFix == 0 &&
		page.notices.none { it is AttentionNotice.Unreachable || it == AttentionNotice.Older }

sealed interface AttentionPage {
	/** Nothing read yet and nothing to show from before: not "nothing to fix". */
	data object Loading : AttentionPage

	/**
	 * B-450 (the owner's ruling, 2026-11-14): what lb-bot has to be filed, as Worbus for desktop's To fix: its
	 * imports and the folders waiting. Failed albums, the albums waiting on a pick and this phone's own failed
	 * rows were listed here too, uncounted; they are Source history's Need you, where every client's are.
	 */
	data class Rows(
		val folders: List<AttentionFolder>,
		/** Library Matches' "to check" list, or null when it is not known. Listed, never counted. */
		val artistsToCheck: Int?,
		/**
		 * The To fix number, the desktop's ([libraryToFixCount]: lb-bot's imports and folders to file), or null
		 * when no answer is known (then the chip and the title say no number at all).
		 */
		val toFix: Int?,
		val notices: List<AttentionNotice>,
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
 * 67 on the owner's phone against the desktop's 41. Since B-450 those rows are not listed here at all: they are
 * Source history's Need you and its badge ([sourceHistoryBadge]).
 */
fun toFixTotal(page: AttentionPage.Rows): Int? = page.toFix

/**
 * The page, from the last read and the last good answer ([last], read at [lastAt]).
 *
 * - A good answer lists lb-bot's imports and folders to file.
 * - A failed read keeps the last good answer and says since when; with none, nothing and no number.
 * - An older hub or lb-bot lists nothing and says so. No read at all is [AttentionPage.Loading].
 *
 * B-450: lb-bot's failed albums and picks (`albums`) and this device's failed or waiting rows are not this
 * page's; Source history's Need you lists them ([needYouItems]).
 */
fun attentionPage(
	read: AttentionRead?,
	last: LbAttention?,
	lastAt: Long?,
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

	val notices = buildList {
		if (read == AttentionRead.Older) add(AttentionNotice.Older)
		if (read == AttentionRead.Unavailable) add(AttentionNotice.Unreachable(lastAt.takeIf { last != null }))
		val cut = answer?.truncated
		if (downloadsOn && cut != null && cut.folders > 0) add(AttentionNotice.Truncated(cut.folders))
	}
	if (answer == null) {
		return AttentionPage.Rows(folders = emptyList(), artistsToCheck = artistsToCheck, toFix = null, notices = notices)
	}
	return AttentionPage.Rows(
		folders = if (downloadsOn) answer.folders else emptyList(),
		artistsToCheck = artistsToCheck,
		toFix = answer.counts?.let { libraryToFixCount(it, downloadsOn) },
		notices = notices,
		imports = answer.counts?.imports ?: 0,
		foldersCounted = if (downloadsOn) answer.counts?.folders ?: 0 else 0
	)
}

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
 * Review M2 (follow-ups): whether a `fill` frame re-reads Need you's two answers (`/lb/activity` and `/lb/attention`),
 * forced: the desktop's `ACTIVITY_MOVES` (a fill that starts or settles, the frames the hub drops those caches for,
 * PROTOCOL §15.6), plus `needs_match`, which Android already read on. Never a progress frame.
 */
fun fillFrameMovesNeedYou(frame: JsonObject): Boolean {
	fun str(key: String) = (frame[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
	return str("state") in NEED_YOU_MOVES || str("status") in NEED_YOU_MOVES
}

private val NEED_YOU_MOVES = setOf("cancelled", "complete", "failed", "needs_match", "picking", "placed", "searching", "verified")

/** Review M2: Need you's reads while the app is in the foreground, as the desktop's `refetchInterval`. */
const val NEED_YOU_POLL_MS = 60_000L

/**
 * Review M1 (follow-ups): whether this user may hold an admin's lb-bot answers (`/lb/activity`, `/lb/attention`,
 * other clients' album names). Only a hub that has SAID who the user is takes them away: [HubRoles.NOTHING_YET]
 * (a reconnect, before its welcome) keeps what this same account read, since an account change forgets them anyway.
 */
fun adminReadsAllowed(roles: HubRoles): Boolean = roles.admin || !roles.known

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
