package app.worbus.ui.util

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The motion lane (option (c), 2026-11-14): every chip or segmented switch that changes what a page shows goes
 * through ONE content swap (`ContentSwap.kt`: [ContentSwap] for sections that are different lists, [SwapRows] for
 * chips that filter one list). Six pages had six ways, one of them two sections drawn over each other (B-431,
 * B-432). A page with chips or segmented buttons that switches with an `AnimatedContent` or a `Crossfade` of its own fails here until
 * someone reviews it.
 */
class ContentSwapGuardTest {
	private val srcRoot = sourceFile("src/commonMain/kotlin/app/worbus")

	/** The pages whose chips swap content, and which shape of the swap each uses. */
	private val swaps = mapOf(
		"ui/screens/library/LibraryScreen.kt" to "ContentSwap(",
		"ui/screens/matches/LibraryMatchesScreen.kt" to "ContentSwap(",
		"ui/screens/sourcehistory/SourceHistoryScreen.kt" to "ContentSwap(",
		"ui/screens/savedqueues/SavedQueuesScreen.kt" to "SwapRows {",
		"ui/screens/search/SearchScreen.kt" to "SwapRows {",
		"ui/screens/fresh/FreshScreen.kt" to "SwapRows {",
		"ui/screens/browse/BrowseFeedScreen.kt" to "SwapRows {"
	)

	/**
	 * A chip page with an `AnimatedContent` of its own, reviewed: the queue's Up next / Related is a segmented
	 * switch between two tabs, which move as pages do (B-433, `Material3Transitions.inPageAxis`), not a chip's swap.
	 */
	private val reviewed = mapOf(
		"ui/screens/queue/QueueScreen.kt" to 2,
		// The custom radio's count line: words that change in place (`CountLine`), not a switch of content.
		"ui/screens/radio/custom/TuneStep.kt" to 1
	)

	@Test
	fun everyChipSwapGoesThroughTheHelper() {
		for ((path, call) in swaps) {
			val text = sourceFile("src/commonMain/kotlin/app/worbus/$path").readText()
			assertTrue(call in text, "$path does not swap through $call")
		}
	}

	@Test
	fun noChipPageSwitchesWithItsOwnAnimatedContent() {
		val own = srcRoot.walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.map { it.relativeTo(srcRoot).invariantSeparatorsPath to it.readText() }
			.filter { (_, text) -> "PageChips(" in text || "SegmentedButton(" in text }
			.associate { (path, text) -> path to Regex("""\b(AnimatedContent|Crossfade)\(""").findAll(text).count() }
			.filterValues { it > 0 }
		assertEquals(reviewed, own)
	}

	@Test
	fun theQueueTabsMoveAsPages() {
		val queue = sourceFile("src/commonMain/kotlin/app/worbus/ui/screens/queue/QueueScreen.kt").readText()
		assertEquals(2, Regex("""Material3Transitions\.inPageAxis\(""").findAll(queue).count())
	}
}
