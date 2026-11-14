package app.worbus.domain.manager

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * The review of the Android follow-ups: the rules M1, M2, L1 and L4 added (their wiring is FollowupsReviewGuardTest).
 */
class FollowupsReviewRulesTest {
	private fun frame(state: String = "", status: String = "") = buildJsonObject {
		put("t", JsonPrimitive("fill"))
		if (state.isNotEmpty()) put("state", JsonPrimitive(state))
		if (status.isNotEmpty()) put("status", JsonPrimitive(status))
	}

	// ---- M1 ------------------------------------------------------------------------------------- //

	@Test
	fun m1_onlyAHubThatSaysNotAnAdminTakesTheAnswersAway() {
		assertTrue(adminReadsAllowed(HubRoles.EVERYTHING))
		assertTrue(adminReadsAllowed(HubRoles(admin = true, primary = false)))
		assertFalse(adminReadsAllowed(HubRoles(admin = false, primary = false)), "a non-admin, said by the hub")
		assertFalse(adminReadsAllowed(HubRoles(admin = false, primary = true)))
		// A reconnect before its welcome keeps what this same account read (an account change forgets it anyway).
		assertTrue(adminReadsAllowed(HubRoles.NOTHING_YET))
	}

	@Test
	fun m1_aSignedInDeviceStartsWithNoAdminUntilTheHubSays() {
		assertFalse(hubRolesOf(null, signedIn = true).admin)
		assertTrue(hubRolesOf(null, signedIn = false).admin, "an older hub or the legacy token: everything, as before")
	}

	// ---- M2 ------------------------------------------------------------------------------------- //

	@Test
	fun m2_theDesktopsActivityMovesReReadNeedYou() {
		for (s in listOf("cancelled", "complete", "failed", "picking", "placed", "searching", "verified", "needs_match")) {
			assertTrue(fillFrameMovesNeedYou(frame(state = s)), "state $s")
			assertTrue(fillFrameMovesNeedYou(frame(status = s)), "status $s")
		}
		for (s in listOf("downloading", "queued", "placing", "")) {
			assertFalse(fillFrameMovesNeedYou(frame(state = s)), "progress $s")
		}
		assertEquals(60_000L, NEED_YOU_POLL_MS)
	}

	// ---- L1 ------------------------------------------------------------------------------------- //

	@Test
	fun l1_aRowOnlyLbBotKnowsHasNoButtonsHere() {
		val req = ActivityRequest(releaseMbid = "rel-o", rgid = "rg-o", artist = "A", album = "B", state = "failed", updatedAt = 10.0)
		val row = mergeHistory(listOf(req), emptyList()).single()
		assertFalse(row.mine)
		assertTrue(row.needsYou)
		val item = needYouItems(listOf(req), null, emptyList(), emptyList()).single()
		assertFalse(item.asHistoryRow().mine)
		assertEquals(row.shown, item.drawn(), "drawn the same in both views")
	}

	// ---- L4 ------------------------------------------------------------------------------------- //

	@Test
	fun l4_theStoredListAndTheFirstFormatBothDecode() {
		val d = DismissedRequest("rel-1", "", "rg-1", "A", "B", false, 9L)
		val stored = lbWireJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(DismissedRequest.serializer()), listOf(d))
		assertEquals(listOf(d), decodeDismissedRequests(stored))
		assertEquals(
			listOf(DismissedRequest(releaseMbid = "rel-1", at = 5L), DismissedRequest(groupId = "g-1", isGap = true, at = 6L)),
			decodeDismissedRequests("""{"r:rel-1":5,"g:g-1":6,"x:junk":7,"r:":8}""")
		)
		assertEquals(emptyList(), decodeDismissedRequests("not json"))
		assertEquals(emptyList(), decodeDismissedRequests("[]"))
	}

	@Test
	fun l4_theBoundsApplyAtLoadToo() {
		val now = DISMISSED_RETAIN_MS * 3
		val old = DismissedRequest(releaseMbid = "old", at = now - DISMISSED_RETAIN_MS)
		val fresh = DismissedRequest(releaseMbid = "fresh", at = now - 1)
		assertEquals(listOf(fresh), boundDismissals(listOf(old, fresh), now))
		val many = (1..(DISMISSED_MAX + 5)).map { DismissedRequest(releaseMbid = "r$it", at = now) }
		assertEquals(many.takeLast(DISMISSED_MAX), boundDismissals(many, now))
	}
}
