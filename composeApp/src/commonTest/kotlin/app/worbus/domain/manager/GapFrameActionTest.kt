package app.worbus.domain.manager

import kotlin.test.Test
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
		assertEquals(GapFrameAction.REREAD, gapFrameAction(watched = false, held = true))
	}

	@Test
	fun aWatchedGroupRidesThePollAndAnUnknownOneIsLeftAlone() {
		assertEquals(GapFrameAction.POLL, gapFrameAction(watched = true, held = true))
		assertEquals(GapFrameAction.POLL, gapFrameAction(watched = true, held = false))
		assertEquals(GapFrameAction.NONE, gapFrameAction(watched = false, held = false))
	}
}
