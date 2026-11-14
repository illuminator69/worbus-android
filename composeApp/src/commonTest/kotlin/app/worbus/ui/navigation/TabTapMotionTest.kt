package app.worbus.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * B-434 (motion lane, 2026-11-14): a bar tap fades through whatever page it leaves. From a page opened over Home,
 * Home's tap only POPS (`[Home, page]` to `[Home]`), and the pop took the page's own motion (the album slide)
 * while Search's tap, a push onto its tab page, faded through.
 */
class TabTapMotionTest {
	private val home: NavKey = Screen.Home
	private val library: NavKey = Screen.Library()
	private val page: NavKey = Screen.SavedQueues

	private fun tap(motion: TabTapMotion, stack: MutableList<NavKey>, tab: ShellTab) {
		val after = stackAfterTap(stack, tab)!!
		motion.tapped(after)
		stack.becomeStack(after)
		motion.stackChanged(stack)
	}

	@Test
	fun homeTappedFromAPagePushedOverHomeIsATabSwitch() {
		val motion = TabTapMotion()
		val stack = mutableListOf(home, page)
		tap(motion, stack, ShellTab.Home)
		assertTrue(motion.isTabTap(stack), "the pop to [Home] is the tap's")
	}

	@Test
	fun theLibraryTappedFromItsOwnPushedPageIsATabSwitchToo() {
		val motion = TabTapMotion()
		val stack = mutableListOf(home, library, page)
		tap(motion, stack, ShellTab.Library)
		assertTrue(motion.isTabTap(stack))
	}

	@Test
	fun backToTheSameStackLaterIsTheOrdinaryPop() {
		val motion = TabTapMotion()
		val stack = mutableListOf(home, page)
		tap(motion, stack, ShellTab.Library)
		stack.add(page)
		motion.stackChanged(stack)
		assertFalse(motion.isTabTap(stack), "a push is not the tap's")
		stack.removeAt(stack.lastIndex)
		motion.stackChanged(stack)
		assertFalse(motion.isTabTap(stack), "Back to [Home, Library] is a pop, not a tab switch")
	}

	@Test
	fun nothingTappedIsNoTabSwitch() {
		assertFalse(TabTapMotion().isTabTap(listOf(home)))
	}
}
