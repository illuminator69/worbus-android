package app.worbus.exoplayer

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import app.worbus.shared.PlayOrder
import kotlin.random.Random

/*
 * W1 (navi-connect DESIGN-w1-play-order-2026-11-10 §5): what the service's ExoPlayer orders a
 * shuffled queue by. One seam, called by PlaybackService on the player it builds and by
 * PlayOrderPlayerTest on a real ExoPlayer, so the test runs the rule the service runs.
 *
 * media3 1.11.0's own DefaultShuffleOrder put every inserted row at a RANDOM play position (Play
 * next did not play next, B-362; a top-up could land before the playing song and never play,
 * B-345), kept the order of INDICES through a move (so the moved rows changed play positions), and
 * changed nothing when shuffle was turned on. [PlayOrderShuffleOrder] applies [PlayOrder]'s rule.
 */

/**
 * A [ShuffleOrder] over an explicit play order: `order[p]` is the row played at play position `p`.
 * Every edit goes through [PlayOrder], the rule the app's state and the hub apply too.
 */
@UnstableApi
class PlayOrderShuffleOrder(
	order: List<Int>,
	private val random: Random = Random
) : ShuffleOrder {
	private val order: IntArray = order.toIntArray()
	private val positionOf = IntArray(this.order.size).also { pos ->
		this.order.forEachIndexed { p, row -> pos[row] = p }
	}

	init {
		require(PlayOrder.isPermutation(order, order.size)) { "not a play order: $order" }
	}

	fun rows(): List<Int> = order.toList()

	override fun getLength(): Int = order.size

	override fun getNextIndex(index: Int): Int {
		val p = positionOf[index] + 1
		return if (p < order.size) order[p] else C.INDEX_UNSET
	}

	override fun getPreviousIndex(index: Int): Int {
		val p = positionOf[index] - 1
		return if (p >= 0) order[p] else C.INDEX_UNSET
	}

	override fun getLastIndex(): Int = if (order.isEmpty()) C.INDEX_UNSET else order[order.size - 1]

	override fun getFirstIndex(): Int = if (order.isEmpty()) C.INDEX_UNSET else order[0]

	override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder =
		PlayOrderShuffleOrder(PlayOrder.insert(rows(), insertionIndex, insertionCount, random), random)

	override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder =
		PlayOrderShuffleOrder(PlayOrder.remove(rows(), indexFrom, indexToExclusive), random)

	override fun cloneAndMove(indexFrom: Int, indexToExclusive: Int, newIndexFrom: Int): ShuffleOrder =
		PlayOrderShuffleOrder(PlayOrder.move(rows(), indexFrom, indexToExclusive, newIndexFrom), random)

	/** A new queue (`setMediaItems`): its start row first, the rest random. */
	override fun cloneAndSet(insertionCount: Int, startIndex: Int): ShuffleOrder =
		PlayOrderShuffleOrder(PlayOrder.currentFirst(insertionCount, startIndex, random), random)

	override fun cloneAndClear(): ShuffleOrder = PlayOrderShuffleOrder(emptyList(), random)
}

/**
 * Gives [player] the play-order rule: a [PlayOrderShuffleOrder], and on every turn of shuffle to
 * on (the app's button, the notification's, a hub directive alike) the playing row first and the
 * rest random. An explicit order sent after that ([setPlayOrder], the session's
 * COMMAND_SET_PLAY_ORDER) replaces it: commands run in the order they were sent.
 */
@UnstableApi
internal fun installPlayOrder(player: ExoPlayer) {
	player.setShuffleOrder(PlayOrderShuffleOrder(PlayOrder.identity(player.mediaItemCount)))
	player.addListener(object : Player.Listener {
		override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
			if (!shuffleModeEnabled) return
			player.setShuffleOrder(
				PlayOrderShuffleOrder(PlayOrder.currentFirst(player.mediaItemCount, player.currentMediaItemIndex))
			)
		}
	})
}

/**
 * Sets an explicit play order. False (and nothing changed) when [order] is not an order of
 * exactly the player's rows: an order sent for another queue never lands on this one.
 */
@UnstableApi
internal fun setPlayOrder(player: ExoPlayer, order: IntArray): Boolean {
	val rows = order.toList()
	if (!PlayOrder.isPermutation(rows, player.mediaItemCount)) return false
	player.setShuffleOrder(PlayOrderShuffleOrder(rows))
	return true
}

/**
 * A play order the app sends for the queue it was built for ([ids], the media ids in queue order),
 * answered through [done] (W1 walk). Today: applied at once if the lengths agree, as
 * COMMAND_SET_PLAY_ORDER does.
 */
@UnstableApi
internal fun requestPlayOrder(player: ExoPlayer, order: List<Int>, ids: List<String>, done: (Boolean) -> Unit) {
	done(setPlayOrder(player, order.toIntArray()))
}
