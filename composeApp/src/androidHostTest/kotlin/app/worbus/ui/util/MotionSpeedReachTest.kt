package app.worbus.ui.util

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** I-075 review L4-L6 (second re-walk; red at 67685037): the speed setting reaches the fixed tweens and the springs (L4), the slider is read as "1×" (L5). */
class MotionSpeedReachTest {
	private fun src(p: String) = sourceFile("src/commonMain/kotlin/app/worbus/$p").readText()

	@Test
	fun l4_fixedTimesAndSpringsFollowTheSpeed() {
		listOf(
			"ui/util/Arrival.kt", "ui/components/common/CoverAmbientBackground.kt", "ui/components/layouts/MiniPlayer.kt",
			"ui/util/CoverColorScheme.kt", "ui/screens/collection/CollectionDetailScreen.kt",
			"ui/components/common/CoverArt.kt", "ui/components/common/RemoteCoverArt.kt"
		).forEach { assertTrue("motionMs(" in src(it), it) }
		assertTrue(".atSpeed(" in src("ui/theme/Theme.kt"), "the motion scheme's springs")
		// The Library's chips swap through ContentSwap since the motion lane (2026-11-14), on the page motion's times.
		assertTrue("ContentSwap(" in src("ui/screens/library/LibraryScreen.kt") && "pageMotion()" in src("ui/util/ContentSwap.kt"))
	}

	@Test
	fun l5_theSliderSaysItsSpeedAndItsEnds() {
		val s = src("ui/screens/settings/AppearanceScreen.kt")
		assertTrue("stateDescription = motionSpeedLabel" in s)
		assertTrue("animation_speed_faster" in s)
	}

	@Test
	fun l6_noUnusedSharedXAxisVals() {
		assertFalse("SharedXAxis" in src("ui/util/Material3Transitions.kt"))
	}
}
