package app.worbus.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Follow-up 2026-11-14, the owner's ruling on the second re-walk's question 1 ("no separation for requests between
 * clients"): Source history's Need you chip and its menu badge count every request lb-bot lists as failed or
 * waiting on a pick, from any client, plus this phone's ledger rows lb-bot does not list. One set, one number;
 * lb-bot's later state still wins. Red at `fdc5cb5e` (`Followup14RedTest`, not committed): another client's
 * failed request was listed and not counted.
 */
class FollowupsNeedYouTest {
	private fun entry(key: String, rel: String, outcome: String = LbBotManager.OUTCOME_FAILED, settled: Boolean = true) =
		LbFillEntry(
			key = key, isGap = false, rgid = "rg-$key", groupId = "", releaseMbid = rel,
			artist = "A", album = "B", quality = "", state = if (settled) "failed" else "downloading",
			outcome = outcome, reason = "", percent = 0, done = 0, total = 5, mp3WouldHelp = false,
			failureKind = "", retryable = true, attempts = 1, settled = settled, startedAt = 0L, finishedAt = 1_000L
		)

	private fun req(rel: String, state: String, gap: Boolean = false) = ActivityRequest(
		releaseMbid = rel, rgid = "rg-$rel", groupId = if (gap) "grp-$rel" else "", artist = "Other", album = rel,
		state = state, gapFill = gap, updatedAt = 2.0
	)

	@Test
	fun everyClientsFailuresAndPicksAreCounted() {
		val server = listOf(
			req("o-failed", "failed"),            // another client's request lb-bot gave up on
			req("o-match", "needs_match"),        // another client's: its files are in and wait to be matched
			req("o-gap", "failed", gap = true),   // a gap fill started from the web page
			req("o-pick", "picking"),             // not an /api/activity state today; read as a pick if it comes
			req("o-run", "downloading"),
			req("o-done", "verified"),
			req("o-cancel", "cancelled")
		)
		val ledger = listOf(entry("mine", "rel-mine"))   // this phone's, lb-bot no longer lists it
		val answer = ActivityAnswer(server, emptyList(), readAt = 5_000L)
		val g = historyGroups(mergeHistory(server, ledger, answer.readAt))
		assertEquals(
			listOf("o-failed", "o-match", "o-gap", "o-pick", "mine").sorted(),
			g.needYou.map { it.key }.sorted()
		)
		assertEquals(5, sourceHistoryBadge(ledger, answer), "the badge is the chip: one set, one number")
		// Another client's row is shown and can be opened, never retried from here: no ledger entry, no buttons.
		val other = g.needYou.single { it.key == "o-failed" }
		assertNull(other.entry)
		assertEquals(LbBotManager.OUTCOME_FAILED, other.shown.outcome)
		assertEquals(LbBotManager.OUTCOME_NEEDS_PICK, g.needYou.single { it.key == "o-pick" }.shown.outcome)
		// Running, landed and cancelled requests are nobody's to fix.
		assertTrue(g.running.any { it.key == "o-run" })
		assertFalse((g.running + g.earlier).single { it.key == "o-done" }.needsYou)
		assertFalse((g.running + g.earlier).single { it.key == "o-cancel" }.needsYou)
	}

	@Test
	fun lbBotsLaterStateStillWins() {
		// This phone's request failed; lb-bot's later answer has it verified (retried from the desktop): not counted.
		val ledger = listOf(entry("x", "rel-x"))
		val answer = ActivityAnswer(listOf(req("rel-x", "verified")), emptyList(), readAt = 5_000L)
		assertEquals(0, sourceHistoryBadge(ledger, answer))
		// The other way round: this phone's request is running here, lb-bot already lists it failed from an
		// earlier attempt (a Retry lb-bot has not been read since): the ledger's running state is shown.
		val running = listOf(entry("y", "rel-y", outcome = LbBotManager.OUTCOME_RUNNING, settled = false))
		assertEquals(0, sourceHistoryBadge(running, ActivityAnswer(listOf(req("rel-y", "failed")), emptyList(), 5_000L)))
	}

	@Test
	fun eachRequestIsCountedOnce() {
		// The phone's own failed request, lb-bot listing it failed too: one row, one count.
		val ledger = listOf(entry("x", "rel-x"))
		val answer = ActivityAnswer(listOf(req("rel-x", "failed")), emptyList(), readAt = 5_000L)
		assertEquals(1, sourceHistoryBadge(ledger, answer))
	}

	@Test
	fun withNoAnswerTheBadgeIsStillTheLedgers() {
		val ledger = listOf(entry("x", "rel-x"), entry("d", "rel-d", outcome = LbBotManager.OUTCOME_DONE))
		assertEquals(1, sourceHistoryBadge(ledger, null))
	}
}
