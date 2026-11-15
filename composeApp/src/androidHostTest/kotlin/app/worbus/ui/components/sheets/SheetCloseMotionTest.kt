package app.worbus.ui.components.sheets

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import app.worbus.sourceFile
import app.worbus.ui.util.atSpeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
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
	/*
	 * The hide runs on the opening's spring: the same offset at every frame, played over the album sheet's travel on the
	 * VM AVD (1881 px). Review Major 2 (2026-11-15): with Compose's default 0.01 px threshold that spring only settled
	 * ~860 ms after a Back, ~560 ms after the sheet was off screen, and Material's window took every tap until then. The
	 * hide must end once the sheet is within a pixel of its hidden place, and no later.
	 */
	@Test
	fun aSheetClosesOnTheSpringItOpensOn() {
		val travel = 1881f
		for ((name, scheme) in listOf("expressive" to MotionScheme.expressive(), "standard" to MotionScheme.standard()))
			for (speed in listOf(0.5f, 1f, 2f)) {
				val base = scheme.atSpeed(speed)
				val sheet = sheetMotionScheme(base)
				assertEquals(base.defaultSpatialSpec<Float>(), sheet.defaultSpatialSpec<Float>(), "$name at $speed: open and drag settle stay Material's")
				val open = offsets(base.defaultSpatialSpec(), travel)
				val hide = offsets(sheet.fastEffectsSpec(), travel)
				val doneMs = hide.durationMs
				for (ms in 0 until doneMs step 4)
					assertEquals(open.at(ms), hide.at(ms), 1e-3f, "$name at $speed: the hide is the opening's curve at $ms ms")
				assertTrue(doneMs < open.durationMs, "$name at $speed: the hide ends at ${doneMs} ms, the spring settles at ${open.durationMs}")
				// Cut there, the opening's spring never again strays more than about a pixel from the hidden place (the end is
				// Compose's analytic estimate from the spring's envelope, so a hair over at the stiffest): nothing visible is
				// left out, only the settle no one can see.
				for (ms in doneMs..open.durationMs)
					assertTrue(kotlin.math.abs(open.at(ms) - travel) <= SHEET_SETTLED_PX + 0.5f, "$name at $speed: ${open.at(ms)} px at $ms ms, after the hide ended")
				val reached = (0..doneMs).first { open.at(it) >= travel - SHEET_SETTLED_PX }
				println("SheetCloseMotionTest $name at $speed: within a pixel of hidden at $reached ms, hide ends at $doneMs ms, the 0.01 px settle at ${open.durationMs} ms")
			}
		// A spring of anything else, or a tween, is left as it is.
		val tween = tween<Float>(300)
		assertSame(tween, settledAtAPixel(tween))
	}

	private class Offsets(val anim: TargetBasedAnimation<Float, *>) {
		val durationMs = (anim.durationNanos / 1_000_000L).toInt()
		fun at(ms: Int): Float = anim.getValueFromNanos(ms * 1_000_000L)
	}

	private fun offsets(spec: FiniteAnimationSpec<Float>, travel: Float) =
		Offsets(TargetBasedAnimation(spec, Float.VectorConverter, 0f, travel))

	/*
	 * Review Minor 1 (2026-11-15): the close reaches Material only through the scheme the wrapper puts round Material's
	 * sheet; the pure [sheetMotionScheme] above proves nothing if the wrapper installs another one (a mutation to
	 * `motionScheme = outerMotion` there passed every motion test). And nothing writes the sheet state's specs:
	 * Material's SideEffect overwrites them (the first B-442 finding).
	 */
	@Test
	fun theWrapperGivesTheHideThroughTheSchemeNotTheState() {
		val src = sourceFile("src/commonMain/kotlin/app/worbus/ui/components/sheets/ModalBottomSheet.kt").readText()
		assertTrue("val sheetMotion = remember(outerMotion) { sheetMotionScheme(outerMotion) }" in src, "the sheet's scheme is built from the page's")
		val aroundMaterial = src.substringBefore("androidx.compose.material3.ModalBottomSheet(").substringAfterLast("MaterialExpressiveTheme(")
		assertTrue("motionScheme = sheetMotion," in aroundMaterial, "Material's sheet runs under the sheet's scheme: $aroundMaterial")
		val pageMotion = src.substringAfter("val withPageMotion").substringBefore("}")
		assertTrue("motionScheme = outerMotion" in pageMotion, "the content gets the page's scheme back: $pageMotion")
		assertTrue("content = { withPageMotion {" in src, "the content runs under withPageMotion")
		val writers = sourceFile("src/commonMain/kotlin").walkTopDown().filter { it.extension == "kt" && "hideMotionSpec =" in it.readText() }.toList()
		assertTrue(writers.isEmpty(), "nothing writes a sheet state's hide spec: $writers")
	}

	/*
	 * Review Major 1 (2026-11-15): Sort, Sleep timer and Lyrics share imported Material's `ModalBottomSheet` by name; an
	 * explicit import beats a declaration in the same package, so they ran Material's sheet (bytecode:
	 * `androidx/compose/material3/ModalBottomSheetKt`) and closed on the theme's fast effects spring. Only the wrapper may
	 * name Material's sheet.
	 */
	@Test
	fun onlyTheWrapperUsesMaterialsSheet() {
		val wrapper = sourceFile("src/commonMain/kotlin/app/worbus/ui/components/sheets/ModalBottomSheet.kt").canonicalFile
		val material = Regex("""^import androidx\.compose\.material3\.(ModalBottomSheet|\*)\s*$|androidx\.compose\.material3\.ModalBottomSheet\(""", RegexOption.MULTILINE)
		val offenders = sourceFile("src").walkTopDown()
			.filter { it.isFile && it.extension == "kt" && it.canonicalFile != wrapper && "androidHostTest" !in it.path && material.containsMatchIn(it.readText()) }
			.map { it.path }.toList()
		assertTrue(offenders.isEmpty(), "these call Material's sheet, not the wrapper: $offenders")
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
		assertFalse("pointerInput" in outer || "watchTouch" in outer, "the sheet's outer modifier covers the space above it: $outer")
	}

	/*
	 * B-121 still holds where the finger can be: the handle's whole row (Material lays it out full width; a watch round
	 * the 32 dp pill alone missed a finger beside it, review nit 2026-11-15) and the content in both eased branches.
	 */
	@Test
	fun theHandleRowAndTheContentAreWatched() {
		val src = sourceFile("src/commonMain/kotlin/app/worbus/ui/components/sheets/ModalBottomSheet.kt").readText()
		val call = src.substringAfter("androidx.compose.material3.ModalBottomSheet(")
		val handle = call.substringAfter("dragHandle = ").substringBefore("handle() }")
		assertTrue(Regex("""Box\(Modifier\.fillMaxWidth\(\)\.watchTouch \{ touched = it \}""").containsMatchIn(handle), "the handle's row is watched, full width: $handle")
		assertTrue("val watched = Modifier.watchTouch { touched = it }" in call, "the content's watch")
		val ambient = call.substringAfter("if (ambient != null) {").substringBefore("} else if (animateContentHeight)")
		val plain = call.substringAfter("} else if (animateContentHeight)").substringBefore("} else {")
		for ((name, branch) in listOf("ambient" to ambient, "plain" to plain))
			assertTrue(Regex("""\.then\(watched\)\s*\.then\(eased\)""").containsMatchIn(branch), "the $name content is watched where it is eased")
	}

	@Test
	fun noSheetTimingIsLeftThatNothingReads() {
		for (path in listOf("ui/util/PageMotion.kt", "ui/util/Material3Transitions.kt", "ui/components/sheets/ModalBottomSheet.kt")) {
			val src = sourceFile("src/commonMain/kotlin/app/worbus/$path").readText()
			assertFalse(Regex("sheet(Show|Hide)(Ms|MotionSpec)").containsMatchIn(src), path)
		}
	}
}
