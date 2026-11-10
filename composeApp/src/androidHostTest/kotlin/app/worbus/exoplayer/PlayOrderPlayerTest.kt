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
}
