package app.worbus.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/*
 * B-443 (seen on the VM, 2026-11-14): while the hub refuses lb-bot with `503 lbbot_too_old` (R26), Library Matches
 * said "Can't reach lb-bot. This is the copy this device loaded last." and "Not pulled: lb-bot is not reachable",
 * not the too-old sentence: a refused read was drawn as an unreachable lb-bot.
 */
class LbTooOldLinesTest {
	@Test
	fun theListsSayTooOldNotUnreachable() {
		assertEquals(LB_TOO_OLD_STALE_LINE, matchesNote(LbPanelData.STALE, 3, tooOld = true))
		assertEquals(LB_TOO_OLD_LINE, matchesNote(LbPanelData.UNREACHABLE, 0, tooOld = true))
		assertEquals(LB_TOO_OLD_STALE_LINE, overridesNote(LbPanelData.STALE, 3, tooOld = true))
		assertEquals(LB_TOO_OLD_LINE, overridesNote(LbPanelData.UNREACHABLE, 0, tooOld = true))
		assertEquals(LB_TOO_OLD_STALE_LINE, albumRowsNote(LbPanelData.STALE, 1, tooOld = true))
		assertEquals(LB_TOO_OLD_STALE_LINE, mergeRowsNote(LbPanelData.STALE, 1, tooOld = true))
		// Otherwise as before.
		assertEquals(LB_STALE_LINE, matchesNote(LbPanelData.STALE, 3, tooOld = false))
		assertEquals(LB_UNREACHABLE_LINE, overridesNote(LbPanelData.UNREACHABLE, 0, tooOld = false))
		assertNull(matchesNote(LbPanelData.CURRENT, 3, tooOld = true), "an answer read is an answer")
	}

	@Test
	fun theMirrorSaysTooOld() {
		assertEquals(
			"Not pulled: this Worbus Server's lb-bot is too old for signed-in users. The mirror is unchanged.",
			indexRunLine(LbIndexRun.Skipped(1L, LbIndexSkip.TOO_OLD))
		)
	}

	@Test
	fun theMirrorsFailedProbeIsTooOldWhenTheHubSaysSo() {
		// Review N3: the wiring, not only the line. A probe refused as too old for this user is TOO_OLD.
		assertEquals(LbIndexSkip.TOO_OLD, indexProbeSkip(configured = true, access = LbAccess.TOO_OLD))
		assertEquals(LbIndexSkip.LB_BOT_DOWN, indexProbeSkip(configured = true, access = LbAccess.OPEN))
		assertEquals(LbIndexSkip.NOT_SET_UP, indexProbeSkip(configured = false, access = LbAccess.OPEN))
	}
}
