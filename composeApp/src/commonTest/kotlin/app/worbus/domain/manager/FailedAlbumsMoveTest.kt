package app.worbus.domain.manager

import app.worbus.ui.screens.attention.toFixHeadlineNothingShown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * B-450, the owner's ruling (2026-11-14, QUESTIONS-before-stage2 item 12): failed albums **move** from Library
 * Matches' To fix to Source history, as on the desktop (whose To fix lists lb-bot's imports and folders only).
 * To fix listed lb-bot's failed albums, the albums waiting on a pick and this phone's own failed rows under its
 * number (imports + folders) without counting them; Source history's Need you already lists every one of them.
 */
class FailedAlbumsMoveTest {

	private fun entry(key: String) = LbFillEntry(
		key = key, isGap = false, rgid = "rg-$key", groupId = "", releaseMbid = "rel-$key",
		artist = "Artist $key", album = "Album $key", quality = "", state = "failed",
		outcome = LbBotManager.OUTCOME_FAILED, reason = "", percent = 0, done = 0, total = 5, mp3WouldHelp = false,
		failureKind = "", retryable = true, attempts = 1, settled = true, startedAt = 1_000L, finishedAt = 2_000L
	)

	private val answer = LbAttention(
		version = 1,
		counts = AttentionCounts(failedGaps = 1, picks = 1),
		albums = listOf(
			AttentionAlbum(kind = "gap", key = "g1", groupId = "g1", artist = "C", album = "D", state = "failed", updatedAt = 50.0),
			AttentionAlbum(kind = "gap", key = "g2", groupId = "g2", artist = "E", album = "F", state = "picking", updatedAt = 60.0)
		),
		folders = emptyList()
	)

	private val ledger = listOf(entry("mine"))

	@Test
	fun failedAlbumsPicksAndThisPhonesFailuresAreNotListedUnderToFix() {
		val page = attentionPage(AttentionRead.Ok(answer), answer, 1L, null) as AttentionPage.Rows
		assertEquals(0, toFixTotal(page))
		assertTrue(toFixHeadlineNothingShown(page), "To fix lists only imports and folders: here none, so \"Nothing to fix\"")
		assertTrue(attentionEmptyShown(page), "and the section is empty, as the desktop's")
	}

	@Test
	fun withoutAnAnswerToFixListsNothingOfThisPhones() {
		val page = attentionPage(AttentionRead.Older, null, null, null) as AttentionPage.Rows
		assertTrue(toFixHeadlineNothingShown(page.copy(toFix = 0)), "this phone's failures are Source history's")
	}

	@Test
	fun sourceHistorysNeedYouListsEveryOneOfThem() {
		val items = needYouItems(emptyList(), answer.albums, emptyList(), ledger)
		assertEquals(3, items.size, "lb-bot's failed album, its pick and this phone's failure")
		assertEquals(3, sourceHistoryBadge(ledger, ActivityAnswer(emptyList(), emptyList(), 1L), AttentionState(last = answer, lastAt = 1L, readAt = 1L, status = AttentionRead.Ok(answer))))
	}
}
