package app.worbus.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * The review of the Android follow-ups (REVIEW-followups-android-2026-11-14): M3, L1, L2, L3 as rules, in the API
 * of `a2f6577e`, so each was red there (`--tests "*FollowupsReviewTest*"`). M1, M2 and L4 are wiring
 * (FollowupsReviewGuardTest) plus the new rules in FollowupsReviewRulesTest. The desktop's rule is `need-you.ts` at
 * `51a62a38c`; the cases follow its harness where it has one.
 */
class FollowupsReviewTest {
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
		sortAt: Long = ms(t0 + 60),
		artist: String = "Artist",
		album: String = "Album"
	) = LbFillEntry(
		key = key, isGap = isGap, rgid = rgid, groupId = groupId, releaseMbid = releaseMbid, artist = artist, album = album,
		quality = "", state = state, outcome = outcome, reason = "", percent = 0, done = 0, total = 0,
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
		artist: String = "Artist",
		album: String = "Album"
	) = ActivityRequest(
		releaseMbid = releaseMbid, rgid = rgid, groupId = groupId, artist = artist, album = album, state = state,
		gapFill = gapFill, updatedAt = updatedAt
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

	private fun gapRow(outcome: String = LbBotManager.OUTCOME_FAILED, sortAt: Long = ms(t0 + 60), state: String = "failed") =
		ledgerRow(key = "g-1", releaseMbid = "", rgid = "", groupId = "g-1", isGap = true, outcome = outcome, sortAt = sortAt, state = state)

	private fun answer(vararg rows: ActivityRequest) = ActivityAnswer(rows.toList(), emptyList(), readAt = ms(t0 + 3_600))

	private fun attentionState(vararg albums: AttentionAlbum) =
		AttentionState(last = LbAttention(version = 1, counts = AttentionCounts(), albums = albums.toList()), lastAt = 1L, readAt = 1L)

	/** One request, one way: every Need you item is the one Requests row of that request, drawn the same. */
	private fun assertOneWay(g: HistoryGroups) {
		val requests = g.running + g.earlier
		assertEquals(g.needYou.size, requests.count { it.needsYou }, "Requests draws as many requests as needing you")
		for (n in g.needYou) {
			val same = requests.filter { r -> r.needsYou && r.shown.key == n.shown.key }
			assertEquals(1, same.size, "${n.key}: one Requests row")
			assertEquals(n.shown.outcome, same.single().shown.outcome, "${n.key}: drawn the same")
			assertEquals(n.shown.state, same.single().shown.state, "${n.key}: in the same state")
		}
	}

	// ---- M3: 503 lbbot_too_old is "no answer" for both reads --------------------------------- //

	@Test
	fun m3_tooOldIsNoAnswerForTheAttentionReadToo() {
		val tooOld = """{"code":"lbbot_too_old","error":"lb-bot is too old"}"""
		assertEquals(AttentionRead.Older, attentionRead(503, tooOld))
		val held = attentionState(attention(state = "failed"))
		assertEquals(1, sourceHistoryBadge(emptyList(), null, held))
		// The hub refusing this user: Need you is the ledger's own set, as the desktop's `screenRead` makes it.
		val refused = nextAttentionState(held, attentionRead(503, tooOld), 2L)
		assertNull(refused.last)
		assertEquals(0, sourceHistoryBadge(emptyList(), null, refused))
		// A 5xx or a timeout is not an answer: the last good one is kept, for both reads.
		for (status in listOf(500, 502, 503, 504)) {
			assertEquals(AttentionRead.Unavailable, attentionRead(status, """{"code":"busy"}"""), "$status")
			assertEquals(1, sourceHistoryBadge(emptyList(), null, nextAttentionState(held, attentionRead(status, ""), 2L)), "$status keeps")
			assertEquals(ActivityRead.Unavailable, activityRead(status, """{"code":"busy"}"""), "$status")
		}
	}

	// ---- L1: drawn as the desktop's `drawnRow` ------------------------------------------------- //

	@Test
	fun l1_aGapLbBotNowHasAtAPickIsDrawnAsThisPhonesRowWithLbBotsState() {
		val item = needYouItems(null, listOf(attention(state = "picking", updatedAt = t0 + 600)), emptyList(), listOf(gapRow())).single()
		val row = item.drawn()
		assertEquals("g-1", row.key, "this phone's row: its buttons act on it")
		assertEquals(LbBotManager.OUTCOME_NEEDS_PICK, row.outcome)
		assertEquals("picking", row.state)
		assertTrue(row.settled)
		assertEquals(ms(t0 + 600), row.finishedAt, "lb-bot's time")
		assertEquals(item.ledger!!.isGap, row.isGap)
		assertEquals(item.ledger!!.groupId, row.groupId)
	}

	@Test
	fun l1_aRunningFillLbBotAlreadyFailedIsTheSameFailureInRequestsAndNeedYou() {
		val ledger = listOf(ledgerRow(outcome = LbBotManager.OUTCOME_RUNNING, settled = false, state = "downloading", sortAt = ms(t0)))
		val g = sourceHistoryGroups(ledger, answer(activity(state = "failed", updatedAt = t0 + 30)))
		assertEquals(1, g.needYou.size)
		val row = (g.running + g.earlier).single()
		assertFalse(row.running, "not Running in Requests while Need you counts it failed")
		assertTrue(row.needsYou)
		assertEquals("rg-1", row.shown.key)
		assertOneWay(g)
	}

	// ---- L2: Requests judges "later" and joins as Need you does -------------------------------- //

	@Test
	fun l2_requestsJudgesLaterByLbBotsRowTimeNotByWhenItWasRead() {
		// Cancelled here at t0+60; lb-bot's row of an earlier attempt says failed at t0; read an hour later.
		val ledger = listOf(ledgerRow(outcome = LbBotManager.OUTCOME_CANCELLED, state = "cancelled", sortAt = ms(t0 + 60)))
		val g = sourceHistoryGroups(ledger, answer(activity(state = "failed", updatedAt = t0)))
		assertEquals(0, g.needYou.size)
		val row = (g.running + g.earlier).single()
		assertFalse(row.needsYou)
		assertEquals(LbBotManager.OUTCOME_CANCELLED, row.shown.outcome)
		assertOneWay(g)
	}

	@Test
	fun l2_twoEditionsOfOneReleaseGroupAreOneRequestInBothViews() {
		val g = sourceHistoryGroups(
			emptyList(),
			answer(activity(releaseMbid = "rel-a"), activity(releaseMbid = "rel-b", updatedAt = t0 - 10))
		)
		assertEquals(1, g.needYou.size)
		assertEquals(1, (g.running + g.earlier).size)
		assertOneWay(g)
	}

	@Test
	fun l2_anOrphanGapIsJoinedByTheSameNamesInBothViews() {
		// lb-bot names a gap fill whose group is gone by its labels; "AC/DC" is not "ACDC" in Need you.
		val ledger = listOf(gapRow().copy(artist = "AC/DC", album = "Powerage"))
		val orphan = activity(releaseMbid = "rel-z", rgid = "", gapFill = true, artist = "ACDC", album = "Powerage", updatedAt = t0 + 600)
		val g = sourceHistoryGroups(ledger, answer(orphan))
		assertEquals(2, g.needYou.size)
		assertEquals(2, (g.running + g.earlier).size)
		assertOneWay(g)
		// The same names, trimmed and in another case, are one request in both.
		val same = sourceHistoryGroups(ledger, answer(orphan.copy(artist = " ac/dc ", album = "POWERAGE")))
		assertEquals(1, same.needYou.size)
		assertEquals(1, (same.running + same.earlier).size)
		assertOneWay(same)
	}

	@Test
	fun l2_aGapThisPhoneFailedThatLbBotHasAtAPickIsOneWayInBoth() {
		val g = sourceHistoryGroups(listOf(gapRow()), answer(), attentionState(attention(state = "picking", updatedAt = t0 + 600)))
		assertEquals(1, g.needYou.size)
		assertOneWay(g)
	}

	// ---- L3: inputs allowlisted as the desktop's ----------------------------------------------- //

	@Test
	fun l3_anActivityRowWithNoStateOrNoIdsIsDropped() {
		// A row with no state does not decide over this phone's failure.
		assertEquals(1, needYouItems(listOf(activity(state = "", updatedAt = t0 + 600)), null, emptyList(), listOf(ledgerRow())).size)
		// A row with neither a release nor a group joins nothing and is not counted.
		assertEquals(0, needYouItems(listOf(activity(releaseMbid = "", groupId = "", rgid = "rg-x")), null, emptyList(), emptyList()).size)
	}

	@Test
	fun l3_anAttentionAlbumOfAnotherKindNoStateOrNoIdsIsDropped() {
		assertEquals(0, needYouItems(null, listOf(attention(kind = "folder", state = "failed")), emptyList(), emptyList()).size)
		assertEquals(0, needYouItems(null, listOf(attention(groupId = "", releaseMbid = "", state = "failed")), emptyList(), emptyList()).size)
		assertEquals(1, needYouItems(null, listOf(attention(state = "")), emptyList(), listOf(gapRow())).size, "no state: this phone's row decides")
	}

	@Test
	fun l3_anAttentionAnswerWithNoAlbumsIsNoAnswerForTheDismissals() {
		val d = DismissedRequest("rel-1", "", "rg-1", "Artist", "Album", false, ms(t0 + 120))
		val noAlbums = (attentionRead(200, """{"version":1,"counts":{"imports":0}}""") as AttentionRead.Ok).attention
		assertEquals(listOf(d), dismissalsStillListed(listOf(d), emptyList(), noAlbums.albums), "kept: lb-bot sent no albums")
		val none = (attentionRead(200, """{"version":1,"counts":{"imports":0},"albums":[]}""") as AttentionRead.Ok).attention
		assertEquals(emptyList(), dismissalsStillListed(listOf(d), emptyList(), none.albums), "forgotten: lb-bot listed none")
	}

	@Test
	fun l3_anAttentionAlbumIsRetryableWhenItIsAGap() {
		assertTrue(needYouItems(null, listOf(attention(state = "failed")), emptyList(), emptyList()).single().drawn().retryable)
		val request = attention(kind = "request", groupId = "", releaseMbid = "rel-1", state = "failed")
		assertFalse(needYouItems(null, listOf(request), emptyList(), emptyList()).single().drawn().retryable)
	}
}
