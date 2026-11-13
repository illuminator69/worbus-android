package app.worbus.ui.navigation

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * B-378 (the owner, 2026-11-09, on the phone): Back played two animations. The button and a quickly
 * committed gesture ran `popTransitionSpec` (the shared-axis slide and fade, timed by PageMotion);
 * a slower gesture ran `predictivePopTransitionSpec`, a full-width slide of its own
 * (PREDICTIVE_POP_MS), and with predictive animations off that spec was
 * `ContentTransform(EnterTransition.None, ExitTransition.None)`: the page under the finger was
 * simply gone. The owner's ruling: ONE back animation, the slide and fade, for the button, a quick
 * gesture and a slow drag (which drives it by its progress); the previous page never disappears
 * at once. Read from App.kt: the transitions are Compose's.
 */
class OneBackAnimationTest {
	private val app = sourceFile("src/commonMain/kotlin/app/worbus/App.kt").readText()

	/** NavDisplay's three specs, from `transitionSpec = {` to the end of its argument list. */
	private val navSpecs = app.substringAfter("\t\t\t\t\t\t\t\t\ttransitionSpec = {").substringBefore("\n\t\t\t\t\t\t\t\t)")

	private val predictive = navSpecs.substringAfter("predictivePopTransitionSpec = {")
	private val pop = navSpecs.substringAfter("popTransitionSpec = {").substringBefore("predictivePopTransitionSpec")

	@Test
	fun theSpecsAreFound() {
		assertTrue("popTransitionSpec = {" in navSpecs && "predictivePopTransitionSpec = {" in navSpecs, navSpecs.take(400))
	}

	/** The transform a spec builds: the arguments of its `Material3Transitions.page(` call. */
	private fun call(spec: String) = spec.substringAfter("Material3Transitions.page(", "").substringBefore("\n\t\t\t\t\t\t\t\t\t\t)").trim()

	private val transitions = sourceFile("src/commonMain/kotlin/app/worbus/ui/util/Material3Transitions.kt").readText()

	@Test
	fun aDraggedBackRunsTheSameTransformAsTheButton() {
		// B-403 kept it: the two pop specs are ONE call, the motion picked by the two pages
		// (pageChangeOf), so Settings' slide is the button's, the quick gesture's and the drag's alike.
		assertTrue(call(pop).isNotBlank() && "pop = true" in call(pop), pop)
		assertEquals(call(pop), call(predictive), "the predictive pop is the pop")
		assertTrue("PREDICTIVE_POP_MS" !in predictive && "slideInHorizontally" !in predictive, "no second back animation:\n$predictive")
	}

	@Test
	fun noBackLeavesThePreviousPageAtOnce() {
		assertTrue("EnterTransition.None" !in predictive && "ExitTransition.None" !in predictive, predictive)
		assertTrue("EnterTransition.None" !in pop && "ExitTransition.None" !in pop, pop)
		// The transform itself: no `None` side, and the slide moves both pages the whole width with no fade.
		val page = transitions.substringAfter("fun page(").substringBefore("\n\t/**")
		assertTrue(".None" !in page, page)
		val slide = transitions.substringAfter("private fun slide(").substringBefore("\n\t/**")
		assertTrue("fade" !in slide && ".None" !in slide, slide)
		assertTrue("{ sign * it }" in slide && "{ -sign * it }" in slide, "both pages travel the whole width:\n$slide")
	}
}
