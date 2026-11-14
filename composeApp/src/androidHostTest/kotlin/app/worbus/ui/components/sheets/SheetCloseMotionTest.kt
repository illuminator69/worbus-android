package app.worbus.ui.components.sheets

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import app.worbus.sourceFile
import app.worbus.ui.util.atSpeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * B-442, the owner's walk of `a7ea2fe1` (2026-11-14): "the new sheets and player closing animations got worse - it should
 * just be the reverse of how they open ... revert it without breaking the chevron and back functionality. also you should
 * be able to close the sheet by tapping the space above that the sheet doesnt cover up".
 *
 * Back, the player's chevron and a tap on the scrim close a sheet through `SheetState.hide()`, whose spec Material's
 * BottomSheet writes from the motion scheme's `fastEffectsSpec` (an effects spring, ~100 ms on the VM AVD); a drag
 * released past the threshold settles on `defaultSpatialSpec`, the spring the sheet opens on. The sheet's scheme gives the
 * hide that same spring, so every close is the opening's motion the other way.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class SheetCloseMotionTest {
	@Test
	fun aSheetClosesOnTheSpringItOpensOn() {
		for ((name, scheme) in listOf("expressive" to MotionScheme.expressive(), "standard" to MotionScheme.standard()))
			for (speed in listOf(0.5f, 1f, 2f)) {
				val base = scheme.atSpeed(speed)
				val sheet = sheetMotionScheme(base)
				assertEquals(base.defaultSpatialSpec<Float>(), sheet.fastEffectsSpec<Float>(), "$name at $speed: hide = open")
				assertEquals(base.defaultSpatialSpec<Float>(), sheet.defaultSpatialSpec<Float>(), "$name at $speed: open and drag settle stay Material's")
			}
	}

	/*
	 * The tap above a sheet: Material's scrim fills the window under the sheet and closes it on a tap, but M3 1.5's
	 * BottomSheet applies its `modifier` BEFORE `draggableAnchors` moves the sheet down, so a pointer input there covers
	 * [0, sheet height] from the TOP of the window, above the scrim in hit testing. The wrapper's B-121 touch watch was
	 * there: a tall sheet (the album sheet, 1881 of 2992 px on the AVD) left no tappable scrim at all, a short one only a
	 * band near it (uiautomator: "Close sheet" at [0,1881][1344,2992], under the sheet).
	 */
	@Test
	fun nothingOnTheSheetsOuterModifierTakesTheScrimsTaps() {
		val src = sourceFile("src/commonMain/kotlin/app/worbus/ui/components/sheets/ModalBottomSheet.kt").readText()
		val call = src.substringAfter("androidx.compose.material3.ModalBottomSheet(")
		val outer = call.substringAfter("modifier = ").substringBefore("sheetState = sheetState")
		assertFalse("pointerInput" in outer, "the sheet's outer modifier covers the space above it: $outer")
		// B-121 still holds: the finger is watched on the sheet itself, its handle and its content.
		assertTrue(Regex("watchTouch").findAll(src).count() >= 3, "the handle and the content are watched")
	}

	@Test
	fun noSheetTimingIsLeftThatNothingReads() {
		for (path in listOf("ui/util/PageMotion.kt", "ui/util/Material3Transitions.kt", "ui/components/sheets/ModalBottomSheet.kt")) {
			val src = sourceFile("src/commonMain/kotlin/app/worbus/$path").readText()
			assertFalse(Regex("sheet(Show|Hide)(Ms|MotionSpec)").containsMatchIn(src), path)
		}
	}
}
