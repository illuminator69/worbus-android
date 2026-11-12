package app.worbus.domain.manager

import app.worbus.ui.screens.collection.GapInput
import app.worbus.ui.screens.collection.GapStage
import app.worbus.ui.screens.collection.GapView
import app.worbus.ui.screens.collection.gapStage
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals

/*
 * B-376: a gaps box open on this device never heard what another device did to the same group. The
 * hub's `fill` frame for a gap only kicked this device's poll, which reads the groups THIS device's
 * ledger watches; a box opened on an album with no fill started here has no watch, so the frame was
 * dropped and the box kept its list until it was reopened. The desktop re-reads the group on the
 * frame (`['lbbot', 'gap', key]` invalidated).
 */
class GapFrameActionTest {
	@Test
	fun aGroupABoxHasReadIsReadAgainOnItsFrame() {
		assertEquals(setOf(GapFrameAction.REREAD), gapFrameActions(polled = false, held = true))
	}

	@Test
	fun aWatchedGroupRidesThePollAndAnUnknownOneIsLeftAlone() {
		// A group the box shows is read again even when the poll also carries it (the re-walk).
		assertEquals(setOf(GapFrameAction.POLL, GapFrameAction.REREAD), gapFrameActions(polled = true, held = true))
		assertEquals(setOf(GapFrameAction.POLL), gapFrameActions(polled = true, held = false))
		assertEquals(setOf(GapFrameAction.NONE), gapFrameActions(polled = false, held = false))
		// What the poll reads: a live watch, or a failure lb-bot still retries; never a settled one.
		assertEquals(true, gapFramePolls(LbWatch(kind = "gap", key = "g", settled = false)))
		assertEquals(true, gapFramePolls(LbWatch(kind = "gap", key = "g", settled = true, retryAt = 5L)))
		assertEquals(false, gapFramePolls(LbWatch(kind = "gap", key = "g", settled = true)))
		assertEquals(false, gapFramePolls(null))
	}

	/**
	 * The Phase B re-walk (`RESULTS-phase-b-rewalk-2026-11-12.md`, "B-231 Android live update"): the
	 * box read "Downloaded, but needs sorting out in lb-bot" (a pending match with nothing left to
	 * fetch, its ledger row settled `failed · needs_match`); lb-bot then marked the fill failed and
	 * pushed the `fill` frame. A SETTLED watch was taken for "the poll carries it", but the poll skips
	 * settled watches, so the frame was dropped and the box kept the old words for 70 s+ (the desktop
	 * showed Failed in 5 s). A frame for a group a box has read re-reads it, settled watch or not.
	 */
	@Test
	fun aSettledWatchDoesNotSwallowTheFrameOfAGroupTheBoxShows() {
		val settled = LbWatch(kind = "gap", key = "g-1", settled = true, state = "needs_match", outcome = "failed")
		assertEquals(setOf(GapFrameAction.REREAD), gapFrameActions(polled = gapFramePolls(settled), held = true), "the poll skips a settled watch")

		// What the re-read brings: picking with the downloaded track → lb-bot's failed record.
		val fill = LbFillEntry(
			key = "g-1", isGap = true, rgid = "", groupId = "g-1", releaseMbid = "", artist = "MGMT", album = "Golden Club II",
			quality = "", state = "needs_match", outcome = "failed", reason = "lb-bot's own words", percent = 100, done = 1, total = 1,
			mp3WouldHelp = false, failureKind = "", retryable = true, attempts = 1, settled = true, startedAt = 0L, finishedAt = 0L
		)
		fun gap(status: String, last: String) = LbGap(
			id = "g-1", album = "Golden Club II", present = 2, total = 4, status = status,
			tracks = listOf(LbGapTrack(1, title = "a", state = "present"), LbGapTrack(2, title = "b", state = "present"), LbGapTrack(3, title = "c", state = "downloaded"), LbGapTrack(4, title = "d", state = last))
		)
		fun stage(g: LbGap) = gapStage(GapInput(g, 2, fill, GapView.TRACKS, false, false, 100_000L))
		assertIs<GapStage.Attention>(stage(gap("picking", "downloaded")), "before: needs sorting out in lb-bot")
		assertIs<GapStage.Failed>(stage(gap("failed", "failed")), "after the re-read: Failed")
	}
}
