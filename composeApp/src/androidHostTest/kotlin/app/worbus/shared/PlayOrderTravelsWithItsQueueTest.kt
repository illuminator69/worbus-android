package app.worbus.shared

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertTrue

/*
 * B-346 (the audit's step, W1 design §5): the queue layer combined `steadyState` (a map hopping
 * through Dispatchers.Default) with `MediaPlayerViewModel.playOrder` (a StateFlow of its own, set
 * at once), so after a remove under shuffle one frame paired the new order with the old queue, or
 * the old order with the new one: the removed row slid back, or the list read as unshuffled and
 * showed its drag handles. The play order is a field of PlayerUiState, written in the same update
 * as the queue, so no reader can pair one queue with another queue's order. These read the source:
 * the frames are Compose's and the players are media3's.
 */
class PlayOrderTravelsWithItsQueueTest {
	private val readers = listOf(
		"src/commonMain/kotlin/app/worbus/ui/screens/queue/QueueScreen.kt",
		"src/commonMain/kotlin/app/worbus/ui/screens/nowPlaying/staged/StagedNowPlaying.kt",
		"src/commonMain/kotlin/app/worbus/ui/screens/nowPlaying/components/controls/ArtworkPager.kt",
		"src/commonMain/kotlin/app/worbus/ui/ColourArrivalEffects.kt",
		"src/commonMain/kotlin/app/worbus/domain/manager/RadioManager.kt"
	)

	@Test
	fun thePlayOrderIsAFieldOfThePlayerState() {
		val state = sourceFile("src/commonMain/kotlin/app/worbus/ui/core/PlayerUiState.kt").readText()
		assertTrue(Regex("""val playOrder: List<Int>\?""").containsMatchIn(state), "PlayerUiState carries playOrder")
	}

	@Test
	fun noReaderTakesThePlayOrderFromAFlowOfItsOwn() {
		val offenders = readers.flatMap { path ->
			sourceFile(path).readLines().withIndex()
				.filter { (_, line) -> Regex("""(player|mediaPlayer)\.playOrder\b""").containsMatchIn(line) }
				.map { (i, line) -> "$path:${i + 1}: ${line.trim()}" }
		}
		assertTrue(offenders.isEmpty(), "read the order off the same state as the queue:\n" + offenders.joinToString("\n"))
	}

	@Test
	fun theViewModelKeepsNoSecondPlayOrderFlow() {
		val common = sourceFile("src/commonMain/kotlin/app/worbus/shared/MediaPlayer.kt").readText()
		assertTrue("_playOrder" !in common, "MediaPlayerViewModel keeps no _playOrder beside _uiState")
		val android = sourceFile("src/androidMain/kotlin/app/worbus/shared/MediaPlayer.android.kt").readText()
		assertTrue("_playOrder" !in android, "MediaPlayer.android writes no _playOrder beside _uiState")
	}
}
