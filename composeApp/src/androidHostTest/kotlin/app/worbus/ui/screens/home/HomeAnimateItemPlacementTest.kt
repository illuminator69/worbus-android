package app.worbus.ui.screens.home

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M3 (audit L1-02): a lazy item's placement animation is read from the item's ROOT. Since P7 each
 * Home shelf's `animateItem()` sat inside `Fold`'s `AnimatedVisibility`, where it does nothing, so a
 * shelf appearing late made the shelves below it jump. Every `item(key = …) { Fold(` on Home passes
 * `animateItem` in Fold's own arguments, and nothing inside a Fold calls it again.
 */
class HomeAnimateItemPlacementTest {

	private val text = sourceFile("src/commonMain/kotlin/app/worbus/ui/screens/home/HomeScreen.kt").readText()

	/** `animateItem(` or, since the follow-up of 2026-11-14, the speed-aware `animatedItem(` (`ItemMotion.kt`). */
	private fun animates(s: String): Boolean = Regex("""\banimated?Item\(""").containsMatchIn(s)

	/** The text between the bracket at [open] and its match. */
	private fun span(open: Int): String {
		val (o, c) = text[open] to if (text[open] == '(') ')' else '}'
		var depth = 1
		var i = open + 1
		while (depth > 0) {
			if (text[i] == o) depth++ else if (text[i] == c) depth--
			i++
		}
		return text.substring(open + 1, i - 1)
	}

	@Test
	fun everyShelfFoldAnimatesItsPlacementFromTheRoot() {
		val folds = Regex("""item\(key = "([^"]+)"\) \{\s*Fold\(""").findAll(text).toList()
		assertTrue(folds.size >= 6, "found ${folds.size} shelf folds")
		for (m in folds) {
			val argsOpen = m.range.last
			val args = span(argsOpen)
			val bodyOpen = text.indexOf('{', argsOpen + args.length + 1)
			val body = span(bodyOpen)
			val shelf = m.groupValues[1]
			assertTrue(animates(args), "$shelf: Fold does not take animateItem in its arguments")
			assertTrue(!animates(body), "$shelf: animateItem inside Fold is inert")
		}
	}

	/**
	 * Worbus P1: the index card's item is a root `Box` (its one pixel keeps the item laid out while the
	 * card is folded), so the placement animation is the Box's: it takes `animateItem` in its own
	 * arguments, and nothing inside it calls it again. Every Home item whose root is a `Box` is held to
	 * the same rule.
	 */
	@Test
	fun everyRootBoxAnimatesItsPlacementFromItsArguments() {
		val boxes = Regex("""item\(key = "([^"]+)"\) \{\s*Box\(""").findAll(text).toList()
		val keys = boxes.map { it.groupValues[1] }
		assertTrue("index" in keys, "the index card's item is not a root Box any more: $keys")
		for (m in boxes) {
			val argsOpen = m.range.last
			val args = span(argsOpen)
			val bodyOpen = text.indexOf('{', argsOpen + args.length + 1)
			val body = span(bodyOpen)
			val item = m.groupValues[1]
			assertTrue(animates(args), "$item: its root Box does not take animateItem")
			assertTrue(!animates(body), "$item: animateItem below the root is inert")
		}
	}

	/*
	 * B-410 (the owner's walk, 2026-11-13; reproduced on the VM AVD): Home with no Worbus Server has no mood
	 * chips, so the folded mood body is the list's FIRST item, and folded shut it was 0 px tall. A hard fling
	 * up then ran past it: the whole list slid off the bottom of the screen and came back (38 frames of an
	 * empty page). Every item that can be first is a root Box at least one pixel tall, as the index card's.
	 */
	@Test
	fun theFirstItemWithoutMoodChipsIsNeverZeroPixelsTall() {
		val m = Regex("""item\(key = "mood-body"\) \{\s*Box\(""").find(text)
		assertTrue(m != null, "the mood body's item is not a root Box")
		val args = span(m.range.last)
		assertTrue("heightIn(min = onePixel)" in args, "the mood body's item can be 0 px tall: $args")
	}
}
