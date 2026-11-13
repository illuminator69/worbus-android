package app.worbus.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/*
 * Follow-up 2026-11-14, the owner's ruling on the second re-walk's question 1 ("no separation for requests between
 * clients"): Need you (Source history's chip and its menu badge) is one set and one number, from any client, by
 * Worbus for desktop's rule (`need-you.ts` at `51a62a38c`; [needYouItems] is its port). These are the desktop
 * harness's cases (`need-you.harness.ts`), case for case where Android has the same parts, so both clients count
 * the same set. The shapes are lb-bot's own (`_activity_view`, `_attention_view`): `/lb/activity` is the fill
 * LEDGER and never says `picking`; a gap's pick is `/lb/attention`'s album.
 *
 * Reds: another client's failed request (red at `fdc5cb5e`), and the desktop review's H1.3, H1.4 (twice) and M1 (red
 * at `90e0e4ea`, `H1RedTest`), none committed.
 */
class FollowupsNeedYouTest {
	private val t0 = 1_700_000_000.0 // lb-bot's clock, epoch seconds
	private fun ms(seconds: Double) = (seconds * 1000).toLong()

	private fun ledgerRow(
		key: String = "rg-1",
		releaseMbid: String = "rel-1",
		rgid: String = "rg-1",
		groupId: String = "",
		isGap: Boolean = false,
		outcome: String = LbBotManager.OUTCOME_FAILED,
		settled: Boolean = true,
		state: String = "failed",
		reason: String = "",
		sortAt: Long = ms(t0 + 60),
		artist: String = "Artist",
		album: String = "Album"
	) = LbFillEntry(
		key = key, isGap = isGap, rgid = rgid, groupId = groupId, releaseMbid = releaseMbid, artist = artist, album = album,
		quality = "", state = state, outcome = outcome, reason = reason, percent = 0, done = 0, total = 0,
		mp3WouldHelp = false, failureKind = "", retryable = true, attempts = 0, settled = settled,
		startedAt = if (settled) 0L else sortAt, finishedAt = if (settled) sortAt else 0L
	)

	private fun activity(
		releaseMbid: String = "rel-1",
		rgid: String = "rg-1",
		groupId: String = "",
		state: String = "failed",
		gapFill: Boolean = false,
		updatedAt: Double = t0,
		reason: String = "",
		artist: String = "Artist",
		album: String = "Album"
	) = ActivityRequest(
		releaseMbid = releaseMbid, rgid = rgid, groupId = groupId, artist = artist, album = album, state = state,
		reason = reason, gapFill = gapFill, updatedAt = updatedAt
	)

	private fun attention(
		kind: String = "gap",
		groupId: String = "g-1",
		releaseMbid: String = "rel-canon",
		rgid: String = "rg-1",
		state: String = "picking",
		updatedAt: Double = t0
	) = AttentionAlbum(
		kind = kind, key = if (kind == "gap") groupId else releaseMbid, groupId = groupId, releaseMbid = releaseMbid,
		rgid = rgid, albumId = "nd-1", artist = "Artist", album = "Album", state = state, updatedAt = updatedAt
	)

	private fun items(
		activity: List<ActivityRequest>? = null,
		attention: List<AttentionAlbum>? = null,
		dismissed: List<DismissedRequest> = emptyList(),
		ledger: List<LbFillEntry> = emptyList()
	) = needYouItems(activity, attention, dismissed, ledger)

	private fun count(
		activity: List<ActivityRequest>? = null,
		attention: List<AttentionAlbum>? = null,
		dismissed: List<DismissedRequest> = emptyList(),
		ledger: List<LbFillEntry> = emptyList()
	) = items(activity, attention, dismissed, ledger).size

	private fun gapRow(outcome: String = LbBotManager.OUTCOME_FAILED, sortAt: Long = ms(t0 + 60), state: String = "failed") =
		ledgerRow(key = "g-1", releaseMbid = "", rgid = "", groupId = "g-1", isGap = true, outcome = outcome, sortAt = sortAt, state = state)

	// ---- H1: the vocabulary lb-bot speaks ------------------------------------------------------ //

	@Test
	fun p1_thisDevicesNeedsMatchFillStillCountsWhenLbBotListsIt() {
		val rows = listOf(ledgerRow(state = "needs_match"))
		assertEquals(1, count(ledger = rows), "ledger only")
		assertEquals(1, count(activity = listOf(activity(state = "needs_match")), ledger = rows))
	}

	@Test
	fun p2_anotherClientsNeedsMatchAndFailedRequestsCount() {
		assertEquals(1, count(activity = listOf(activity(releaseMbid = "rel-x", rgid = "rg-x", state = "needs_match"))))
		assertEquals(1, count(activity = listOf(activity(releaseMbid = "rel-y", rgid = "rg-y"))))
	}

	@Test
	fun p3_thisDevicesGapAtAPickIsNotHiddenByAnEarlierVerifiedFill() {
		val rows = listOf(gapRow(LbBotManager.OUTCOME_NEEDS_PICK, sortAt = ms(t0 + 600), state = ""))
		val verifiedEarlier = activity(gapFill = true, groupId = "g-1", releaseMbid = "rel-canon", state = "verified", updatedAt = t0)
		assertEquals(1, count(ledger = rows), "ledger only")
		assertEquals(1, count(activity = listOf(verifiedEarlier), ledger = rows), "lb-bot older: ours")
		assertEquals(0, count(activity = listOf(verifiedEarlier.copy(updatedAt = t0 + 1200)), ledger = rows), "lb-bot newer: theirs")
	}

	@Test
	fun p3b_thisDevicesFailedGapWithAnOlderVerifiedRowCounts() {
		val rows = listOf(gapRow(sortAt = ms(t0 + 600)))
		assertEquals(1, count(activity = listOf(activity(gapFill = true, groupId = "g-1", state = "verified")), ledger = rows))
	}

	@Test
	fun anotherClientsGapAtAPickComesFromAttention() {
		val picks = listOf(attention())
		assertEquals(1, count(attention = picks))
		val item = items(attention = picks).single()
		assertNull(item.ledger)
		assertEquals("picking", item.server?.state)
		assertEquals(LbBotManager.OUTCOME_NEEDS_PICK, item.drawn().outcome)
	}

	@Test
	fun theGapsOwnStatusIsNotOverruledByAFinishedFill() {
		assertEquals(1, count(
			activity = listOf(activity(gapFill = true, groupId = "g-1", state = "verified", updatedAt = t0 + 50)),
			attention = listOf(attention(updatedAt = t0))
		))
		// ...but a fill of it in flight now is: nobody needs to pick while it runs.
		assertEquals(0, count(
			activity = listOf(activity(groupId = "g-1", state = "downloading", updatedAt = t0 + 50)),
			attention = listOf(attention(updatedAt = t0))
		))
	}

	@Test
	fun aFailedRequestBothReadsListIsOneItem() {
		assertEquals(1, count(
			activity = listOf(activity()),
			attention = listOf(attention(kind = "request", groupId = "", releaseMbid = "rel-1", state = "failed"))
		))
	}

	@Test
	fun lbBotsLiveAndFinishedRequestsAreNotCounted() {
		val rows = listOf("verified", "downloading", "searching", "queued", "placing", "placed", "cancelled")
			.mapIndexed { i, state -> activity(releaseMbid = "rel-$i", rgid = "rg-$i", state = state) }
		assertEquals(0, count(activity = rows))
	}

	@Test
	fun lbBotNewerThanOurRowWins() {
		val rows = listOf(ledgerRow(sortAt = ms(t0)))
		for (state in listOf("verified", "downloading", "searching", "placed", "cancelled")) {
			assertEquals(0, count(activity = listOf(activity(state = state, updatedAt = t0 + 30)), ledger = rows), state)
		}
	}

	@Test
	fun ourRowThatLbBotNoLongerListsStillCounts() {
		assertEquals(2, count(activity = listOf(activity()), ledger = listOf(ledgerRow(key = "rg-gone", releaseMbid = "rel-gone", rgid = "rg-gone"))))
	}

	@Test
	fun noAnswerIsTheLedgersOwnSet() {
		val rows = listOf(
			ledgerRow(),
			ledgerRow(key = "rg-2", releaseMbid = "rel-2", rgid = "rg-2", outcome = LbBotManager.OUTCOME_NEEDS_PICK),
			ledgerRow(key = "rg-3", releaseMbid = "rel-3", rgid = "rg-3", outcome = LbBotManager.OUTCOME_CANCELLED),
			ledgerRow(key = "rg-4", releaseMbid = "rel-4", rgid = "rg-4", outcome = LbBotManager.OUTCOME_RUNNING, settled = false)
		)
		assertEquals(2, count(ledger = rows))
		assertEquals(2, sourceHistoryBadge(rows, null))
	}

	// ---- M1: Dismiss and Add to wishlist clear the item here ----------------------------------- //

	private val dismissedFill = DismissedRequest("rel-1", "", "rg-1", "Artist", "Album", false, ms(t0 + 120))
	private val dismissedGap = DismissedRequest("", "g-1", "", "Artist", "Album", true, ms(t0 + 120))

	@Test
	fun p4_aDismissedFailureStaysDismissedAgainstLbBotsRow() {
		assertEquals(0, count(activity = listOf(activity()), dismissed = listOf(dismissedFill)))
		assertEquals(1, count(activity = listOf(activity(updatedAt = t0 + 600)), dismissed = listOf(dismissedFill)), "a newer state is news")
	}

	@Test
	fun aDismissedGapStaysDismissedAgainstItsPickAndFillRows() {
		assertEquals(0, count(
			activity = listOf(activity(gapFill = true, groupId = "g-1", releaseMbid = "rel-canon")),
			attention = listOf(attention()),
			dismissed = listOf(dismissedGap)
		))
	}

	@Test
	fun aDismissalIsForgottenOnceLbBotListsNothingForItNeverOnAMissingAnswer() {
		assertEquals(listOf(dismissedFill), dismissalsStillListed(listOf(dismissedFill), listOf(activity()), emptyList()))
		assertEquals(emptyList(), dismissalsStillListed(listOf(dismissedFill), emptyList(), emptyList()))
		assertEquals(listOf(dismissedFill), dismissalsStillListed(listOf(dismissedFill), null, emptyList()), "activity not read")
		assertEquals(listOf(dismissedFill), dismissalsStillListed(listOf(dismissedFill), emptyList(), null), "attention not read")
	}

	@Test
	fun whatADismissRemembers() {
		assertEquals(DismissedRequest("rel-1", "", "rg-1", "Artist", "Album", false, 9L), dismissalOf(ledgerRow(), 9L))
		assertEquals(DismissedRequest("", "g-1", "", "Artist", "Album", true, 9L), dismissalOf(gapRow(), 9L))
	}

	// ---- L1: one request, one item, one key ---------------------------------------------------- //

	@Test
	fun p5_twoLbBotRowsOfOneGroupAndOurGapRowAreOneItem() {
		assertEquals(1, items(
			activity = listOf(
				activity(groupId = "g-1", releaseMbid = "rel-a", updatedAt = t0 - 10),
				activity(groupId = "g-1", releaseMbid = "rel-b", updatedAt = t0 - 20)
			),
			ledger = listOf(gapRow())
		).size)
	}

	@Test
	fun p6_aGapFillWhoseGroupIsGoneIsNotCountedTwice() {
		val rows = listOf(gapRow())
		val gone = activity(gapFill = true, groupId = "", releaseMbid = "rel-gap", rgid = "")
		assertEquals(1, count(activity = listOf(gone), ledger = rows))
		val other = activity(album = "Other", artist = "Else", gapFill = true, groupId = "", releaseMbid = "rel-z", rgid = "")
		assertEquals(2, count(activity = listOf(gone, other), ledger = rows), "an empty id never joins two unrelated rows")
	}

	@Test
	fun everyItemHasAUniqueStableKey() {
		val a1 = activity(releaseMbid = "rel-x", rgid = "rg-x")
		val a2 = activity(groupId = "g-9", releaseMbid = "rel-y", rgid = "")
		val att = attention(groupId = "g-2", releaseMbid = "rel-c2", rgid = "rg-2")
		val l1 = ledgerRow()
		val l2 = ledgerRow(key = "g-3", releaseMbid = "", rgid = "", groupId = "g-3", isGap = true)
		val keys = items(activity = listOf(a1, a2), attention = listOf(att), ledger = listOf(l1, l2)).map { it.key }
		assertEquals(keys.size, keys.toSet().size)
		assertEquals(5, keys.size)
		val again = items(activity = listOf(a2, a1), attention = listOf(att), ledger = listOf(l2, l1)).map { it.key }
		assertEquals(keys.toSet(), again.toSet())
	}

	// ---- L3: drawn from the state that decided ------------------------------------------------- //

	@Test
	fun p7_aLocalRetryStartedAfterLbBotsFailureIsInFlightNotNeedYou() {
		val rows = listOf(ledgerRow(outcome = LbBotManager.OUTCOME_RUNNING, settled = false, sortAt = ms(t0 + 5), state = "searching"))
		assertEquals(0, count(activity = listOf(activity(updatedAt = t0)), ledger = rows))
	}

	@Test
	fun p8_ourCancelledRowUnderLbBotsNewerFailureIsDrawnAsLbBotsFailure() {
		val rows = listOf(ledgerRow(outcome = LbBotManager.OUTCOME_CANCELLED, sortAt = ms(t0), state = "cancelled"))
		val item = items(activity = listOf(activity(reason = "No peer had it", updatedAt = t0 + 30)), ledger = rows).single()
		val row = item.drawn()
		assertEquals(LbBotManager.OUTCOME_FAILED, row.outcome)
		assertEquals("failed", row.state)
		assertEquals("No peer had it", row.reason)
		// Android draws lb-bot's differing newer state as lb-bot's row (Open album only), keeping the item's key.
		assertEquals("here:rg-1", item.key)
	}

	@Test
	fun aRowThisDeviceDecidedIsDrawnAsThisDevices() {
		val rows = listOf(ledgerRow(reason = "ours", sortAt = ms(t0 + 60)))
		val item = items(activity = listOf(activity(reason = "theirs", updatedAt = t0)), ledger = rows).single()
		assertSame(rows.single(), item.drawn())
	}

	@Test
	fun lbBotNewerAndAgreeingKeepsThisDevicesRowAndItsButtons() {
		val rows = listOf(ledgerRow(sortAt = ms(t0)))
		val item = items(activity = listOf(activity(updatedAt = t0 + 30)), ledger = rows).single()
		assertSame(rows.single(), item.drawn())
	}

	// ---- L2: a failed read is not an answer ---------------------------------------------------- //

	@Test
	fun l2_aFailedReadIsNotAnAnswerOnlyAMissingRouteOrTooOldIs() {
		assertIs<ActivityRead.Ok>(activityRead(200, """{"version":1,"requests":[]}"""))
		assertEquals(ActivityRead.Older, activityRead(404, ""))
		assertEquals(ActivityRead.Older, activityRead(503, """{"code":"lbbot_too_old"}"""))
		for (status in listOf(500, 502, 503, 504)) assertEquals(ActivityRead.Unavailable, activityRead(status, """{"code":"busy"}"""), "$status")
	}

	// ---- the page and the badge read one set --------------------------------------------------- //

	@Test
	fun theChipAndTheBadgeAreOneSet() {
		val ledger = listOf(ledgerRow(), gapRow(LbBotManager.OUTCOME_NEEDS_PICK, state = ""))
		val answer = ActivityAnswer(listOf(activity(releaseMbid = "rel-o", rgid = "rg-o"), activity(state = "verified", updatedAt = t0 + 600)), emptyList(), readAt = 5_000L)
		val att = AttentionState(last = LbAttention(version = 1, albums = listOf(attention(groupId = "g-p", releaseMbid = "", rgid = ""))), lastAt = 5_000L, readAt = 5_000L)
		val g = sourceHistoryGroups(ledger, answer, att, emptyList())
		// rel-1 retried elsewhere and verified (newer): not counted; the gap here at a pick, another client's failure,
		// another client's gap at a pick (attention): counted.
		assertEquals(3, g.needYou.size)
		assertEquals(g.needYou.size, sourceHistoryBadge(ledger, answer, att, emptyList()))
		assertEquals(g.needYou.map { it.key }.toSet().size, g.needYou.size, "keys unique")
		assertTrue(g.needYou.all { it.shown.settled })
	}
}
