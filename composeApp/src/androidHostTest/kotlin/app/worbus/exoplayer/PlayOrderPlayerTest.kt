package app.worbus.exoplayer

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ShuffleOrder
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * W1 (navi-connect DESIGN-w1-play-order-2026-11-10 §2, §5, §6): the play order of a shuffled queue
 * on media3's real ExoPlayer (1.11.0), under Robolectric. Nothing is prepared: what is read is the
 * player's own timeline after each call (its masking timeline is built with the player's
 * ShuffleOrder), which is what the service's MediaSession hands the app's controller.
 *
 * Two kinds of test:
 * - `media3Pin…`: what media3 itself does, on a player WITHOUT [installPlayOrder]. The design rests
 *   on these two and they had only been reasoned.
 * - the rest: the rule of §2 on a player WITH [installPlayOrder], the one PlaybackService builds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@UnstableApi
class PlayOrderPlayerTest {
	private val players = mutableListOf<ExoPlayer>()

	@After
	fun release() {
		players.forEach { it.release() }
	}

	private fun newPlayer(installed: Boolean): ExoPlayer {
		val context = RuntimeEnvironment.getApplication()
		// ExoPlayer refuses a player without renderers; one audio renderer, never prepared.
		val player = ExoPlayer.Builder(
			context,
			RenderersFactory { _, _, _, _, _ -> arrayOf<Renderer>(MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT)) }
		).build()
		if (installed) installPlayOrder(player)
		players += player
		return player
	}

	private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("http://127.0.0.1/$id").build()

	private fun rows(n: Int, prefix: String = "s") = (0 until n).map { item("$prefix$it") }

	private fun ExoPlayer.playOrderRows(): List<Int> {
		val t = currentTimeline
		val out = ArrayList<Int>()
		var i = t.getFirstWindowIndex(true)
		while (i != C.INDEX_UNSET && out.size < t.windowCount) {
			out += i
			i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
		}
		return out
	}

	private fun ExoPlayer.playOrderIds() = playOrderRows().map { getMediaItemAt(it).mediaId }

	/** Six rows, s4 playing at play position 2 of [3, 0, 4, 1, 5, 2], shuffle on. */
	private fun shuffledSix(installed: Boolean): ExoPlayer = newPlayer(installed).apply {
		setMediaItems(rows(6), 4, 0L)
		shuffleModeEnabled = true
		if (installed) setPlayOrder(this, intArrayOf(3, 0, 4, 1, 5, 2))
		else setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(intArrayOf(3, 0, 4, 1, 5, 2), 0L))
		assertEquals(listOf("s3", "s0", "s4", "s1", "s5", "s2"), playOrderIds())
		assertEquals("s4", currentMediaItem?.mediaId)
	}

	@Test
	fun media3PinAMoveKeepsTheOrderOfIndicesNotOfRows() {
		val player = shuffledSix(installed = false)
		player.moveMediaItem(5, 0)
		// ShuffleOrder.cloneAndMove's default returns the order unchanged: the same INDICES, so the
		// rows that moved take other play positions.
		assertEquals(listOf(3, 0, 4, 1, 5, 2), player.playOrderRows())
	}

	@Test
	fun media3PinRemovingThePlayingRowGoesToItsShuffleSuccessor() {
		val player = shuffledSix(installed = false)
		player.removeMediaItem(4)
		assertEquals("s1", player.currentMediaItem?.mediaId)
	}

	/** B-345: an autoplay top-up (an append) under shuffle lands after the playing song. */
	@Test
	fun anAppendUnderShuffleGoesToTheEndOfPlayOrderInTheOrderGiven() {
		val player = shuffledSix(installed = true)
		player.addMediaItems(rows(5, "a"))
		assertEquals(
			listOf("s3", "s0", "s4", "s1", "s5", "s2", "a0", "a1", "a2", "a3", "a4"),
			player.playOrderIds()
		)
	}

	/** B-362: Play next under shuffle (an insert at current + 1) plays next. */
	@Test
	fun playNextUnderShufflePlaysNext() {
		val player = shuffledSix(installed = true)
		player.addMediaItem(5, item("n"))
		val next = player.currentTimeline.getNextWindowIndex(player.currentMediaItemIndex, Player.REPEAT_MODE_OFF, true)
		assertEquals("n", player.getMediaItemAt(next).mediaId)
		assertEquals(listOf("s3", "s0", "s4", "n", "s1", "s5", "s2"), player.playOrderIds())
	}

	/** Several rows played next keep the order they were given. */
	@Test
	fun severalRowsPlayedNextKeepTheirOrder() {
		val player = shuffledSix(installed = true)
		player.addMediaItems(5, listOf(item("n0"), item("n1"), item("n2")))
		assertEquals(listOf("s3", "s0", "s4", "n0", "n1", "n2", "s1", "s5", "s2"), player.playOrderIds())
	}

	@Test
	fun aMoveKeepsEveryRowsPlayPosition() {
		val player = shuffledSix(installed = true)
		player.moveMediaItem(5, 0)
		assertEquals(listOf("s3", "s0", "s4", "s1", "s5", "s2"), player.playOrderIds())
		player.moveMediaItems(0, 2, 4)
		assertEquals(listOf("s3", "s0", "s4", "s1", "s5", "s2"), player.playOrderIds())
	}

	@Test
	fun removingThePlayingRowPlaysItsPlayOrderSuccessorAndKeepsTheRest() {
		val player = shuffledSix(installed = true)
		player.removeMediaItem(4)
		assertEquals("s1", player.currentMediaItem?.mediaId)
		assertEquals(listOf("s3", "s0", "s1", "s5", "s2"), player.playOrderIds())
	}

	@Test
	fun shuffleOnPutsThePlayingSongFirst() {
		val player = newPlayer(installed = true)
		player.setMediaItems(rows(50), 31, 0L)
		setPlayOrder(player, IntArray(50) { it })
		player.shuffleModeEnabled = true
		val order = player.playOrderRows()
		assertEquals(31, order.first())
		assertEquals((0 until 50).toList(), order.sorted())
	}

	@Test
	fun aNewQueueUnderShuffleStartsWithItsStartRow() {
		val player = newPlayer(installed = true)
		player.shuffleModeEnabled = true
		player.setMediaItems(rows(50), 17, 0L)
		val order = player.playOrderRows()
		assertEquals(17, order.first())
		assertEquals((0 until 50).toList(), order.sorted())
	}

	/**
	 * W1 review finding 1: Play next when the playing song is the LAST queue row. The engine sees an
	 * insert at the end (an append, by its index alone), so the app makes Play next explicit: the
	 * add, then the order it works out with PlayOrder.playNext (COMMAND_SET_PLAY_ORDER), as
	 * MediaPlayer.android's playNextSingleLocal does.
	 */
	@Test
	fun playNextOnTheLastQueueRowPlaysNext() {
		val player = newPlayer(installed = true).apply {
			setMediaItems(rows(6), 5, 0L)
			shuffleModeEnabled = true
			setPlayOrder(this, intArrayOf(3, 0, 4, 1, 5, 2))
		}
		val before = player.playOrderRows()
		player.addMediaItem(6, item("n"))
		setPlayOrder(player, app.worbus.shared.PlayOrder.playNext(before, 6, 1, current = 5).toIntArray())
		val next = player.currentTimeline.getNextWindowIndex(player.currentMediaItemIndex, Player.REPEAT_MODE_OFF, true)
		assertEquals("n", player.getMediaItemAt(next).mediaId)
	}

	/** Controller ruling (W1 review): an insert above the first row goes right before its queue successor. */
	@Test
	fun anInsertAboveTheFirstRowGoesRightBeforeItsQueueSuccessor() {
		val player = shuffledSix(installed = true)
		player.addMediaItem(0, item("x"))
		assertEquals(listOf("s3", "x", "s0", "s4", "s1", "s5", "s2"), player.playOrderIds())
	}

	// ---- W1 walk (2026-11-10): the order arrives before the queue edit it was built for ----
	// media3 1.11's session runs a controller's queue edits through a per-controller command queue
	// and its custom commands straight away, so COMMAND_SET_PLAY_ORDER can reach the service's
	// player BEFORE the setMediaItems / addMediaItems / setShuffleModeEnabled it follows. On the AVD
	// it was refused ("refused a play order of N rows", 5 times) and the player kept its own order.

	private fun ExoPlayer.ids() = (0 until mediaItemCount).map { getMediaItemAt(it).mediaId }

	@Test
	fun aPlayNextOrderSentBeforeItsAddIsAppliedWhenTheAddLands() {
		val player = newPlayer(installed = true).apply {
			setMediaItems(rows(6), 5, 0L)
			shuffleModeEnabled = true
			setPlayOrder(this, intArrayOf(3, 0, 4, 1, 5, 2))
		}
		var answer: Boolean? = null
		val ids = player.ids() + "n"
		requestPlayOrder(player, listOf(3, 0, 4, 1, 5, 6, 2), ids, null) { answer = it }
		player.addMediaItem(6, item("n"))
		assertEquals(listOf("s3", "s0", "s4", "s1", "s5", "n", "s2"), player.playOrderIds())
		assertEquals(true, answer)
	}

	@Test
	fun aNewQueuesOrderSentBeforeTheQueueIsAppliedWhenTheQueueLands() {
		val player = shuffledSix(installed = true)
		var answer: Boolean? = null
		requestPlayOrder(player, listOf(2, 0, 3, 1), listOf("g0", "g1", "g2", "g3"), null) { answer = it }
		player.setMediaItems(rows(4, "g"), 2, 0L)
		assertEquals(listOf("g2", "g0", "g3", "g1"), player.playOrderIds())
		assertEquals(true, answer)
	}

	/** Ruling 2 on the AVD: the hub's proposed order lost to the service's own shuffle-on order. */
	@Test
	fun anOrderSentWithShuffleOnSurvivesTheLateShuffleCommand() {
		val player = newPlayer(installed = true).apply { setMediaItems(rows(6), 4, 0L) }
		var answer: Boolean? = null
		requestPlayOrder(player, listOf(3, 0, 4, 1, 5, 2), player.ids(), null) { answer = it }
		player.shuffleModeEnabled = true // the controller's own shuffle-on, landing after the order
		assertEquals(listOf("s3", "s0", "s4", "s1", "s5", "s2"), player.playOrderIds())
		assertEquals(true, answer)
	}

	@Test
	fun anOrderIsNeverAppliedToAnotherQueueOfTheSameLength() {
		val player = shuffledSix(installed = true)
		var answer: Boolean? = null
		requestPlayOrder(player, listOf(5, 4, 3, 2, 1, 0), (0 until 6).map { "x$it" }, null) { answer = it }
		assertEquals(listOf("s3", "s0", "s4", "s1", "s5", "s2"), player.playOrderIds())
		assertTrue(answer != true, "not applied: $answer")
	}

	@Test
	fun shuffleOffDropsAnOrderStillWaitingForItsQueue() {
		val player = shuffledSix(installed = true)
		var answer: Boolean? = null
		requestPlayOrder(player, listOf(6, 0, 1, 2, 3, 4, 5), player.ids() + "n", null) { answer = it }
		player.shuffleModeEnabled = false
		assertEquals(false, answer)
	}

	/**
	 * W1 re-walk: a transfer's load (and the orphan adopt) of a queue the player already held — the
	 * same songs in the same order — had its order applied to the OLD queue (same ids) before the
	 * queued setMediaItems landed, whose new-queue order (start row first, the rest random) then
	 * replaced it. A load's order is for that load: its items carry the load's token.
	 */
	@Test
	fun aLoadsOrderSentBeforeAReloadOfTheSameSongsIsAppliedAfterTheReload() {
		val player = shuffledSix(installed = true)
		var answer: Boolean? = null
		val order = listOf(5, 0, 1, 2, 3, 4)
		requestPlayOrder(player, order, player.ids(), 7L) { answer = it }
		player.setMediaItems(withLoadToken(rows(6), 7L), 5, 0L)
		assertEquals(order.map { "s$it" }, player.playOrderIds())
		assertEquals(true, answer)
	}

	@Test
	fun aLoadsOrderLandingAfterItsLoadIsAppliedAtOnce() {
		val player = shuffledSix(installed = true)
		player.setMediaItems(withLoadToken(rows(6), 8L), 5, 0L)
		var answer: Boolean? = null
		requestPlayOrder(player, listOf(5, 0, 1, 2, 3, 4), player.ids(), 8L) { answer = it }
		assertEquals(listOf("s5", "s0", "s1", "s2", "s3", "s4"), player.playOrderIds())
		assertEquals(true, answer)
	}

	/** Review 3, the transfer back A to B to A: the released device kept the session's queue. */
	@Test
	fun aTransferBackLoadsTheSameQueueWithTheHubsOrder() {
		val player = shuffledSix(installed = true).apply { pause() }
		val hubs = listOf(2, 5, 1, 0, 3, 4)
		var answer: Boolean? = null
		requestPlayOrder(player, hubs, player.ids(), 21L) { answer = it }
		player.setMediaItems(withLoadToken(rows(6), 21L), 2, 30_000L)
		assertEquals(hubs.map { "s$it" }, player.playOrderIds())
		assertEquals(true, answer)
	}

	/** Review 3, the orphan reload: the same queue again, only the cursor differs. */
	@Test
	fun theOrphanReloadOfTheSameQueueKeepsTheSessionsOrder() {
		val player = shuffledSix(installed = true)
		val session = listOf(1, 3, 5, 0, 2, 4)
		var answer: Boolean? = null
		requestPlayOrder(player, session, player.ids(), 22L) { answer = it }
		player.setMediaItems(withLoadToken(rows(6), 22L), 1, 0L)
		assertEquals(session.map { "s$it" }, player.playOrderIds())
		assertEquals(true, answer)
	}

	/**
	 * Review 3 finding 2: an edit's order arriving after an outside controller (the notification,
	 * Bluetooth, Android Auto) turned shuffle off must not turn it back on; it is refused.
	 */
	@Test
	fun anEditsOrderDoesNotTurnShuffleBackOn() {
		val player = shuffledSix(installed = true)
		player.shuffleModeEnabled = false // the notification's button
		var answer: Boolean? = null
		requestPlayOrder(player, listOf(3, 0, 4, 1, 5, 6, 2), player.ids() + "n", null) { answer = it }
		player.addMediaItem(6, item("n"))
		assertEquals(false, player.shuffleModeEnabled)
		assertEquals(false, answer)
	}
}
