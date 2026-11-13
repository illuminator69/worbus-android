package app.worbus.domain.manager

import app.worbus.ui.components.sheets.AvatarMenuEntry
import app.worbus.ui.components.sheets.AvatarMenuIds
import app.worbus.ui.components.sheets.avatarMenuItems
import app.worbus.ui.screens.attention.ToFixParts
import app.worbus.ui.screens.attention.toFixHeadlineNothingShown
import app.worbus.ui.screens.attention.toFixNumber
import app.worbus.ui.screens.attention.toFixParts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/*
 * The review of the Android second re-walk fix round (REVIEW-second-rewalk-android-2026-11-13): Source history's
 * badge is its Need you chip (M1), drawn and counted by lb-bot's later state; it stays with downloading off (L3);
 * no "Nothing to fix" or "To fix (0)" over listed folders (L1); the headline says what its number is made of
 * (L2); lb-bot's counts are read as the desktop reads them (L7). Each case was red at `67685037` in the base's
 * API (Rewalk3RedTest, not committed).
 */
class SecondRewalkReviewTest {
	private fun entry(
		key: String,
		rel: String,
		outcome: String = LbBotManager.OUTCOME_FAILED,
		settled: Boolean = true,
		finishedAt: Long = 1_000L
	) = LbFillEntry(
		key = key, isGap = false, rgid = "rg-$key", groupId = "", releaseMbid = rel,
		artist = "A", album = "B", quality = "", state = if (settled) "failed" else "downloading",
		outcome = outcome, reason = "", percent = 0, done = 0, total = 5, mp3WouldHelp = false,
		failureKind = "", retryable = true, attempts = 1, settled = settled, startedAt = 0L, finishedAt = finishedAt
	)

	private fun req(rel: String, state: String) =
		ActivityRequest(releaseMbid = rel, rgid = "rg-$rel", artist = "A", album = "B", state = state, updatedAt = 2.0)

	private fun groups(server: List<ActivityRequest>, ledger: List<LbFillEntry>, readAt: Long?) =
		historyGroups(mergeHistory(server, ledger, readAt))

	// ---- M1 ------------------------------------------------------------------------------------- //

	@Test
	fun m1_theBadgeIsTheNeedYouChipOverTheRowsAsDrawn() {
		val ledger = listOf(
			entry("x", "rel-x"),                                                   // lb-bot later: verified (landed elsewhere)
			entry("y", "rel-y"),                                                   // lb-bot agrees: failed
			entry("p", "rel-p", outcome = LbBotManager.OUTCOME_NEEDS_PICK),        // lb-bot agrees: picking
			entry("z", "rel-z"),                                                   // lb-bot no longer lists it
			entry("r", "rel-r", settled = false, outcome = LbBotManager.OUTCOME_RUNNING) // running
		)
		val server = listOf(req("rel-x", "verified"), req("rel-y", "failed"), req("rel-p", "picking"), req("rel-o", "failed"))
		val answer = ActivityAnswer(server, emptyList(), readAt = 5_000L)
		val g = groups(server, ledger, answer.readAt)
		assertEquals(listOf("p", "y", "z").sorted(), g.needYou.map { it.key }.sorted())
		assertEquals(g.needYou.size, sourceHistoryBadge(ledger, answer), "one set, one number")
		// x is drawn as lb-bot's verified row (Open album only), so it is neither shown nor counted as failed.
		val x = (g.running + g.earlier).single { it.key == "x" }
		assertEquals(LbBotManager.OUTCOME_DONE, x.shown.outcome)
		assertFalse(x.needsYou)
		// A row lb-bot agrees with keeps this phone's entry and its buttons.
		val y = g.needYou.single { it.key == "y" }
		assertSame(y.entry, y.shown)
		// lb-bot's own failed row (another client's) is listed and not this phone's to count.
		assertTrue((g.running + g.earlier).any { it.entry == null && it.state == "failed" })
		// Every Need you row is a row Requests lists too.
		assertTrue(g.needYou.all { it in g.earlier })
	}

	@Test
	fun m1_aRowThatSettledAfterLbBotsAnswerKeepsItsOwnState() {
		// lb-bot was read (still downloading) before this phone's ledger saw the fill fail.
		val ledger = listOf(entry("x", "rel-x", finishedAt = 9_000L))
		val server = listOf(req("rel-x", "downloading"))
		val g = groups(server, ledger, readAt = 5_000L)
		val row = g.earlier.single()
		assertSame(row.entry, row.shown)
		assertEquals(1, g.needYou.size)
		assertEquals(1, sourceHistoryBadge(ledger, ActivityAnswer(server, emptyList(), 5_000L)))
		// Read after it settled, lb-bot's later state wins: the row is running again, nobody's to fix.
		val later = groups(server, ledger, readAt = 10_000L)
		assertEquals(0, later.needYou.size)
		assertEquals(1, later.running.size)
		assertEquals(0, sourceHistoryBadge(ledger, ActivityAnswer(server, emptyList(), 10_000L)))
	}

	@Test
	fun m1_withNoAnswerTheBadgeIsTheLedgersCount() {
		val ledger = listOf(entry("x", "rel-x"), entry("y", "rel-y", outcome = LbBotManager.OUTCOME_NEEDS_PICK), entry("d", "rel-d", outcome = LbBotManager.OUTCOME_DONE))
		assertEquals(needsAttentionCount(ledger), sourceHistoryBadge(ledger))
		assertEquals(2, sourceHistoryBadge(ledger, null))
	}

	@Test
	fun m1_needYouIsAppendedToTheSavedEnum() {
		assertEquals(SourceHistorySection.entries.size - 1, SourceHistorySection.NeedYou.ordinal)
	}

	// ---- L3 ------------------------------------------------------------------------------------- //

	@Test
	fun l3_downloadsOffKeepsThisPhonesFailuresBadgedAndReachable() {
		val ledger = listOf(entry("x", "rel-x"), entry("y", "rel-y"))
		assertEquals(2, sourceHistoryBadge(ledger))
		fun ids(count: Int) = avatarMenuItems(lbConfigured = true, canShare = false, attentionCount = 0, downloadsOn = false, sourceHistoryCount = count)
			.flatMap { if (it is AvatarMenuEntry.Group) it.entries else listOf(it) }.map { it.id }
		assertTrue(AvatarMenuIds.SOURCE_HISTORY in ids(2))
		assertFalse(AvatarMenuIds.SOURCE_HISTORY in ids(0))
		// Not an admin's: Library tools is the wishlist alone, badge or not.
		val user = avatarMenuItems(lbConfigured = true, canShare = false, attentionCount = 0, downloadsOn = false, admin = false, sourceHistoryCount = 2)
			.flatMap { if (it is AvatarMenuEntry.Group) it.entries else listOf(it) }.map { it.id }
		assertFalse(AvatarMenuIds.SOURCE_HISTORY in user)
	}

	// ---- L1, L2 --------------------------------------------------------------------------------- //

	private fun page(body: String) =
		attentionPage(attentionRead(200, body), null, null, emptyList(), null) as AttentionPage.Rows

	@Test
	fun l1_nothingToFixIsNeverSaidOverListedFolders() {
		val p = page("""{"version":1,"counts":{"imports":0},"folders":[{"id":"f1","fileCount":3}]}""")
		assertEquals(0, p.toFix)
		assertFalse(toFixHeadlineNothingShown(p))
		assertNull(toFixNumber(p, 0), "no \"To fix (0)\" over a listed folder")
		val empty = page("""{"version":1,"counts":{"imports":0,"folders":0}}""")
		assertTrue(toFixHeadlineNothingShown(empty))
		assertEquals(0, toFixNumber(empty, 0), "an answer with nothing listed is a 0")
		val albumOnly = page("""{"version":1,"counts":{"failedGaps":1},"albums":[{"kind":"gap","key":"g","state":"failed"}]}""")
		assertNull(toFixNumber(albumOnly, 0), "nor over a listed album")
	}

	@Test
	fun l2_theHeadlineSaysWhatItsNumberIsMadeOf() {
		val p = page("""{"version":1,"counts":{"imports":16,"folders":25,"failedGaps":9,"failedRequests":11,"picks":4}}""")
		assertEquals(ToFixParts(total = 41, imports = 16, folders = 25), toFixParts(p))
		assertEquals(ToFixParts(16, 16, 0), toFixParts(page("""{"version":1,"counts":{"imports":16}}""")))
		// Downloading off: the folders are not counted, so not said.
		val off = attentionPage(attentionRead(200, """{"version":1,"counts":{"imports":16,"folders":25}}"""), null, null, emptyList(), null, downloadsOn = false) as AttentionPage.Rows
		assertEquals(ToFixParts(16, 16, 0), toFixParts(off))
		// No answer, no number, no parts.
		val none = attentionPage(AttentionRead.Older, null, null, emptyList(), null) as AttentionPage.Rows
		assertNull(toFixParts(none))
	}

	// ---- L7 ------------------------------------------------------------------------------------- //

	@Test
	fun l7_countsAreWholeNumbersAboveZeroElseZero() {
		val read = attentionRead(200, """{"version":1,"counts":{"imports":-3,"folders":2.0,"picks":1.5,"failedGaps":"4","failedRequests":null,"foldersUnidentified":7}}""")
		val counts = assertIs<AttentionRead.Ok>(read).attention.counts!!
		assertEquals(AttentionCounts(failedGaps = 0, failedRequests = 0, folders = 2, foldersUnidentified = 7, picks = 0, imports = 0), counts)
		assertEquals(2, libraryToFixCount(counts))
	}
}
