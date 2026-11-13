package app.worbus.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/*
 * Follow-up 2026-11-14, the owner's ruling on the second re-walk's question 1 ("no separation for requests between
 * clients"): Source history's Need you chip and its menu badge count every request lb-bot lists as failed or waiting
 * on a pick, from any client, plus this phone's ledger rows lb-bot does not list. One set, one number; lb-bot's later
 * state still wins. Red at `fdc5cb5e` (`Followup14RedTest`, not committed): another client's failed request was
 * listed and not counted.
 *
 * The shapes are lb-bot's own (`Lb-bot-missing/listenbrainz_bot.py`, `_activity_view` and `_attention_view`; the
 * desktop review's H1): `/lb/activity` is the FILL ledger and only ever says searching, queued, downloading,
 * placing, placed, verified, failed, cancelled or needs_match, never `picking`; a gap waiting on a pick (or one that
 * failed at search, which writes no ledger row) is `/lb/attention`'s album (`kind: "gap"`); a gap's ledger row is
 * the last fill of it that reached placement, keyed by release, and named from its labels with no groupId once the
 * review group is gone.
 */
class FollowupsNeedYouTest {
	private fun entry(key: String, rel: String, outcome: String = LbBotManager.OUTCOME_FAILED, settled: Boolean = true, finishedAt: Long = 1_000L) =
		LbFillEntry(
			key = key, isGap = false, rgid = "rg-$key", groupId = "", releaseMbid = rel,
			artist = "A", album = "B", quality = "", state = if (settled) "failed" else "downloading",
			outcome = outcome, reason = "", percent = 0, done = 0, total = 5, mp3WouldHelp = false,
			failureKind = "", retryable = true, attempts = 1, settled = settled, startedAt = 0L, finishedAt = finishedAt
		)

	/** This device's gap row, as `LbWatch.toEntry` makes it: key = group, no release. */
	private fun gapEntry(group: String, outcome: String, finishedAt: Long = 1_000L, artist: String = "Gap Artist", album: String = "Gap Album") =
		entry(group, "", outcome = outcome, finishedAt = finishedAt).copy(
			isGap = true, groupId = group, rgid = "", artist = artist, album = album,
			state = if (outcome == LbBotManager.OUTCOME_NEEDS_PICK) "picking" else "failed"
		)

	private fun req(rel: String, state: String, gap: Boolean = false, group: String = if (gap) "grp-$rel" else "", artist: String = "Other", album: String = rel) =
		ActivityRequest(
			releaseMbid = rel, rgid = "rg-$rel", groupId = group, artist = artist, album = album,
			state = state, gapFill = gap, updatedAt = 2.0
		)

	private fun att(kind: String, group: String, state: String, rel: String = "rel-$group") = AttentionAlbum(
		kind = kind, key = if (kind == "gap") group else rel, groupId = group, releaseMbid = rel, rgid = "rg-$group",
		artist = "Other", album = group, state = state, updatedAt = 3.0
	)

	private fun attention(vararg albums: AttentionAlbum, readAt: Long = 5_000L) =
		AttentionState(last = LbAttention(version = 1, albums = albums.toList()), lastAt = readAt, readAt = readAt)

	private fun groups(ledger: List<LbFillEntry>, activity: List<ActivityRequest>, attention: AttentionState? = null, dismissed: Set<String> = emptySet()) =
		historyGroups(sourceHistoryRows(ledger, ActivityAnswer(activity, emptyList(), readAt = 5_000L), attention, dismissed))

	@Test
	fun everyClientsFailuresAndPicksAreCounted() {
		val activity = listOf(
			req("o-failed", "failed"),            // another client's request lb-bot gave up on
			req("o-match", "needs_match"),        // another client's: its files are in and wait to be matched
			req("o-gap", "failed", gap = true),   // a gap fill started from the web page that failed at placement
			req("o-run", "downloading"),
			req("o-done", "verified"),
			req("o-cancel", "cancelled")
		)
		// Another client's gap waiting on a pick, and one that failed at search: only /lb/attention says so.
		val att = attention(att("gap", "g-pick", "picking"), att("gap", "g-search", "failed"))
		val ledger = listOf(entry("mine", "rel-mine"))   // this phone's, lb-bot no longer lists it
		val g = groups(ledger, activity, att)
		assertEquals(
			listOf("o-failed", "o-match", "o-gap", "g-pick", "g-search", "mine").sorted(),
			g.needYou.map { it.key }.sorted()
		)
		assertEquals(6, sourceHistoryBadge(ledger, ActivityAnswer(activity, emptyList(), 5_000L), att), "one set, one number")
		// Another client's row is shown and can be opened, never retried from here: no ledger entry.
		val other = g.needYou.single { it.key == "o-failed" }
		assertNull(other.entry)
		assertEquals(LbBotManager.OUTCOME_FAILED, other.shown.outcome)
		assertEquals(LbBotManager.OUTCOME_NEEDS_PICK, g.needYou.single { it.key == "g-pick" }.shown.outcome)
		assertTrue(g.needYou.all { it in g.earlier }, "every Need you row is a row Requests lists")
		// Running, landed and cancelled requests are nobody's to fix.
		assertTrue(g.running.any { it.key == "o-run" })
		assertFalse((g.running + g.earlier).single { it.key == "o-done" }.needsYou)
		assertFalse((g.running + g.earlier).single { it.key == "o-cancel" }.needsYou)
	}

	@Test
	fun thisDevicesNeedsMatchFillStillCounts() {
		// H1.1: an album fill that ended at needs_match is the ledger's `failed`; lb-bot lists the same release as
		// needs_match. They agree, so it stays counted (it needs sorting out in lb-bot).
		val ledger = listOf(entry("m", "rel-m").copy(state = "needs_match"))
		assertEquals(1, groups(ledger, listOf(req("rel-m", "needs_match"))).needYou.size)
	}

	@Test
	fun anOlderFillOfAGapDoesNotHideThisDevicesGap() {
		// H1.3: the gap waits on a pick here; lb-bot's fill ledger still holds a `verified` row of an earlier fill
		// of the same group (kept 6 h). That row is not the gap's state.
		val ledger = listOf(gapEntry("g1", LbBotManager.OUTCOME_NEEDS_PICK))
		val activity = listOf(req("rel-a", "verified", gap = true, group = "g1"))
		val g = groups(ledger, activity)
		assertEquals(listOf("g1"), g.needYou.map { it.key })
		assertSame(g.needYou.single().entry, g.needYou.single().shown, "drawn as this device's row, with its buttons")
	}

	@Test
	fun theGapsOwnLaterStateFromAttentionWins() {
		// This device's gap failed; /lb/attention, read after that, says the gap now waits on a pick: still counted,
		// as lb-bot's later word. And read BEFORE it settled, the device's row stands.
		val ledger = listOf(gapEntry("g1", LbBotManager.OUTCOME_FAILED, finishedAt = 1_000L))
		val later = groups(ledger, emptyList(), attention(att("gap", "g1", "picking"), readAt = 5_000L))
		assertEquals(LbBotManager.OUTCOME_NEEDS_PICK, later.needYou.single().shown.outcome)
		val earlier = groups(ledger, emptyList(), attention(att("gap", "g1", "picking"), readAt = 500L))
		assertEquals(LbBotManager.OUTCOME_FAILED, earlier.needYou.single().shown.outcome)
		assertEquals(1, earlier.needYou.size)
	}

	@Test
	fun oneGapCountsOnce() {
		// H1.4: two releases of one group (the canonical changed, an earlier fill) are two fill-ledger rows.
		val ledger = listOf(gapEntry("g1", LbBotManager.OUTCOME_FAILED))
		val activity = listOf(req("rel-a", "failed", gap = true, group = "g1"), req("rel-b", "failed", gap = true, group = "g1"))
		assertEquals(1, groups(ledger, activity).needYou.size)
		// The same two from another client: one request, counted once.
		assertEquals(1, groups(emptyList(), activity).needYou.size)
		// And attention naming the same gap does not add a third.
		assertEquals(1, groups(ledger, activity, attention(att("gap", "g1", "failed", rel = "rel-a"))).needYou.size)
	}

	@Test
	fun aGapWhoseGroupIsGoneIsFoundByItsNames() {
		// H1.4: lb-bot names a gap fill from its labels once its review group is gone: no groupId to join by.
		val ledger = listOf(gapEntry("g1", LbBotManager.OUTCOME_FAILED, artist = "Gap Artist", album = "Gap Album"))
		val activity = listOf(req("rel-x", "failed", gap = true, group = "", artist = "Gap Artist", album = "Gap Album"))
		val g = groups(ledger, activity)
		assertEquals(1, g.needYou.size)
		assertEquals(1, (g.running + g.earlier).size)
	}

	@Test
	fun dismissedHereStaysOutOfNeedYou() {
		// M1 (desktop review): Dismiss is local; lb-bot keeps its failed row 24 h. Once this device's row is gone,
		// lb-bot's must not come back as another client's.
		val dismissed = dismissalIds(entry("x", "rel-x"))
		assertEquals(setOf("r:rel-x"), dismissed)
		val g = groups(emptyList(), listOf(req("rel-x", "failed"), req("rel-y", "failed")), dismissed = dismissed)
		assertEquals(listOf("rel-y"), g.needYou.map { it.key })
		// A dismissed gap, by its group, from activity and from attention alike.
		val gapIds = dismissalIds(gapEntry("g1", LbBotManager.OUTCOME_NEEDS_PICK))
		assertEquals(setOf("g:g1"), gapIds)
		val gg = groups(emptyList(), listOf(req("rel-a", "failed", gap = true, group = "g1")), attention(att("gap", "g1", "picking")), gapIds)
		assertEquals(0, gg.needYou.size)
		// A new request for the same release made here is this device's row again, whatever was dismissed.
		assertEquals(1, groups(listOf(entry("x2", "rel-x")), listOf(req("rel-x", "failed")), dismissed = dismissed).needYou.size)
	}

	@Test
	fun dismissalsAreKeptWhileLbBotListsThem() {
		val d = mapOf("r:rel-x" to 1_000L, "r:gone" to 1_000L, "g:g1" to 1_000L, "r:old" to 0L)
		val kept = prunedDismissals(d, listOf(req("rel-x", "failed"), req("old", "failed")), listOf(att("gap", "g1", "picking")), now = DISMISSAL_KEEP_MS + 500)
		assertEquals(setOf("r:rel-x", "g:g1"), kept.keys, "gone from lb-bot, or past the 48 h cap: forgotten")
		// With no attention answer a gap's dismissal is kept (nothing says lb-bot dropped it).
		assertEquals(setOf("g:g1"), prunedDismissals(mapOf("g:g1" to 1_000L), emptyList(), null, 2_000L).keys)
	}

	@Test
	fun lbBotsLaterStateStillWins() {
		// This phone's request failed; lb-bot's later answer has it verified (retried from the desktop): not counted.
		val ledger = listOf(entry("x", "rel-x"))
		assertEquals(0, sourceHistoryBadge(ledger, ActivityAnswer(listOf(req("rel-x", "verified")), emptyList(), readAt = 5_000L)))
		// Running here (a Retry lb-bot has not been read since) while lb-bot lists an earlier failure: not counted.
		val running = listOf(entry("y", "rel-y", outcome = LbBotManager.OUTCOME_RUNNING, settled = false))
		assertEquals(0, sourceHistoryBadge(running, ActivityAnswer(listOf(req("rel-y", "failed")), emptyList(), 5_000L)))
	}

	@Test
	fun eachRequestIsCountedOnce() {
		val ledger = listOf(entry("x", "rel-x"))
		assertEquals(1, sourceHistoryBadge(ledger, ActivityAnswer(listOf(req("rel-x", "failed")), emptyList(), readAt = 5_000L)))
	}

	@Test
	fun withNoAnswerTheBadgeIsStillTheLedgers() {
		val ledger = listOf(entry("x", "rel-x"), entry("d", "rel-d", outcome = LbBotManager.OUTCOME_DONE))
		assertEquals(1, sourceHistoryBadge(ledger, null))
	}
}
