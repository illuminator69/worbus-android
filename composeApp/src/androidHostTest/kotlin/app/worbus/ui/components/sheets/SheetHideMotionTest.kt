package app.worbus.ui.components.sheets

import androidx.compose.animation.core.TweenSpec
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import app.worbus.sourceFile
import app.worbus.domain.models.settings.AnimationStyle
import app.worbus.ui.util.EmphasizedAccelerateEasing
import app.worbus.ui.util.pageMotionOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/*
 * B-442 / B-154 (measured on the VM AVD, 2026-11-14, release with a log of the sheet state: RESULTS-stage2-android):
 * Back on the album sheet and the player's collapse chevron closed on Material's own fast spring, `SpringSpec`, never
 * the wrapper's 300 ms hide. Material's BottomSheet writes show, hide and drag-settle into the sheet state from the
 * motion scheme in a SideEffect on every recomposition, so the specs the wrapper wrote once were overwritten. The hide
 * now reaches the sheet through its motion scheme.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class SheetHideMotionTest {
	@Test
	fun theSheetsMotionSchemeHidesOnThePagesHide() {
		for (style in AnimationStyle.entries) for (speed in listOf(0.5f, 1f, 2f)) {
			val motion = pageMotionOf(style, speed)
			val base = MotionScheme.expressive()
			val scheme = SheetMotionScheme(base, motion)
			val hide = assertIs<TweenSpec<Float>>(scheme.fastEffectsSpec<Float>())
			assertEquals(motion.sheetHideMs, hide.durationMillis, "$style at $speed")
			assertSame(EmphasizedAccelerateEasing, hide.easing)
			// Opening and drag settling stay Material's spring (a tween there would ignore a fling's speed).
			assertEquals(base.defaultSpatialSpec<Float>(), scheme.defaultSpatialSpec<Float>())
		}
	}

	@Test
	fun theWrapperGivesTheHideThroughTheSchemeNotTheState() {
		val src = sourceFile("src/commonMain/kotlin/app/worbus/ui/components/sheets/ModalBottomSheet.kt").readText()
		assertTrue("hideMotionSpec =" !in src, "a spec written into the sheet state is overwritten by Material's SideEffect")
		assertTrue("motionScheme = sheetMotion" in src)
		assertTrue("motionScheme = outerMotion" in src, "the content keeps the page's motion scheme")
	}
}
