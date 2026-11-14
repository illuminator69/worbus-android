package app.worbus.domain.manager

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * The review of the Android follow-ups (REVIEW-followups-android-2026-11-14), M1, M2 and L4: the wiring no host test
 * can run (LbBotManager needs Room and the hub), held at the source. Red at `a2f6577e`. The rules these call are
 * tested in FollowupsReviewRulesTest.
 */
class FollowupsReviewGuardTest {
	private fun text(path: String) = sourceFile("src/commonMain/kotlin/app/worbus/$path").readText()
	private val manager by lazy { text("domain/manager/LbBotManager.kt") }

	private fun member(all: String, signature: String): String {
		val start = all.indexOf(signature)
		assertTrue(start >= 0, signature)
		return all.substring(start, all.indexOf("\n\t}\n", start))
	}

	// ---- M1 ------------------------------------------------------------------------------------- //

	@Test
	fun m1_losingAdminDropsTheAdminAnswers() {
		assertTrue("forgetAdminReads(" in member(manager, "fun applyRoles("), "applyRoles forgets on a demotion")
		val forget = member(manager, "private fun forgetAdminReads(")
		assertTrue("_activityAnswer.value = null" in forget)
		assertTrue("_attention.value = AttentionState()" in forget)
	}

	@Test
	fun m1_aLogoutDropsTheAnswersAndTheDismissals() {
		val logout = member(manager, "suspend fun forgetWatches(")
		assertTrue("forgetAdminReads(" in logout, "the answers")
		assertTrue("forgetDismissals(" in logout, "the dismissals")
	}

	@Test
	fun m1_theBadgeNeverCountsWhatThisUserMayNotRead() {
		val badge = manager.substring(manager.indexOf("val sourceHistoryBadge: StateFlow<Int>"))
		assertTrue("_roles" in badge.substring(0, badge.indexOf("stateIn")), "the badge reads the roles")
	}

	@Test
	fun m1_rolesDoNotGrantAdminBeforeTheHubSays() {
		assertTrue("private val _roles = MutableStateFlow(hubRolesOf(null, hubAuth.signedIn))" in manager)
	}

	@Test
	fun m1_aReadThatLandsAfterAForgetIsDropped() {
		assertTrue("adminReadsEpoch" in member(manager, "suspend fun refreshAttention("))
		assertTrue("adminReadsEpoch" in member(manager, "suspend fun activity("))
	}

	// ---- M2 ------------------------------------------------------------------------------------- //

	@Test
	fun m2_theDesktopsActivityMovesReReadBothAnswers() {
		val frame = member(manager, "fun onFillFrame(")
		assertTrue("fillFrameMovesNeedYou(frame)" in frame)
		assertTrue("requestActivityRefresh(force = true)" in frame)
	}

	@Test
	fun m2_aForcedReadAskedWhileOneIsOutIsReadAfterIt() {
		assertTrue("activityAgain" in member(manager, "suspend fun refreshActivity("))
		assertTrue("attentionAgain" in member(manager, "suspend fun refreshAttention("))
	}

	@Test
	fun m2_needYouIsReadOnResumeAndEveryMinuteInTheForeground() {
		val app = text("App.kt")
		val at = app.indexOf("lbBot.watchNeedYouWhileShown()")
		assertTrue(at >= 0, "App composes the foreground reads")
		assertTrue("LifecycleResumeEffect" in app.substring(maxOf(0, at - 400), at), "while resumed")
		assertTrue("NEED_YOU_POLL_MS" in member(manager, "fun watchNeedYouWhileShown("))
	}

	// ---- L4 ------------------------------------------------------------------------------------- //

	@Test
	fun l4_dismissalsArePrunedAfterAnAttentionReadToo() {
		assertTrue("pruneDismissals()" in member(manager, "suspend fun refreshAttention("))
		assertTrue("pruneDismissals()" in member(manager, "suspend fun activity("))
	}

	@Test
	fun l4_aDismissalIsWrittenAtomically() {
		assertTrue("_dismissed.update" in manager)
		assertFalse("_dismissed.value = bounded" in manager)
	}

	@Test
	fun l4_thePreferenceSaysWhatItHolds() {
		val prefs = text("domain/manager/PreferenceManager.kt")
		val at = prefs.indexOf("var lbDismissedRequests")
		val comment = prefs.substring(prefs.lastIndexOf("\n\n", at), at)
		assertFalse("-> epoch ms" in comment, "the old map format")
		assertTrue("DismissedRequest" in comment)
	}
}
