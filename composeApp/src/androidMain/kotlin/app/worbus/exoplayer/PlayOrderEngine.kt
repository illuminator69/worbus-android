package app.worbus.exoplayer

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
 * rest random, unless an order the app sent for that turn is waiting to be applied
 * ([requestPlayOrder]).
 */
@UnstableApi
internal fun installPlayOrder(player: ExoPlayer) {
	synchronized(engines) { engines[player] = PlayOrderEngine(player) }
}

/**
 * Sets an explicit play order at once. False (and nothing changed) when [order] is not an order of
 * exactly the player's rows.
 */
@UnstableApi
internal fun setPlayOrder(player: ExoPlayer, order: IntArray): Boolean {
	val rows = order.toList()
	if (!PlayOrder.isPermutation(rows, player.mediaItemCount)) return false
	player.setShuffleOrder(PlayOrderShuffleOrder(rows))
	return true
}

/**
 * A play order the app sent (COMMAND_SET_PLAY_ORDER) for the queue it was built for: [ids], the
 * media ids in queue order, and for a whole-queue load its [loadToken] (the load's items carry it,
 * [withLoadToken]). The order is applied the moment the player holds exactly that queue
 * (now, or when the queue edit it follows lands), with shuffle turned on, and [done] answers true
 * then; false when it is not an order of [ids], when a newer one replaces it, when shuffle is
 * turned off first, or after [ORDER_WAIT_MS] without its queue.
 *
 * Why it waits (W1 walk, 2026-11-10): media3 1.11's session runs a controller's queue edits
 * (setMediaItems, addMediaItems, setShuffleModeEnabled) through a per-controller command queue and
 * its custom commands at once (`MediaSessionStub`), so the order can arrive before the edit it
 * follows. Checked against the queue's identity, never its length: an order of another queue with
 * as many rows is never applied.
 */
@UnstableApi
internal fun requestPlayOrder(player: ExoPlayer, order: List<Int>, ids: List<String>, loadToken: Long?, done: (Boolean) -> Unit) {
	val engine = synchronized(engines) { engines[player] }
	if (engine == null) done(false) else engine.request(order, ids, loadToken, done)
}

/** The extra under which a whole-queue load stamps its media items with its token. */
internal const val LOAD_TOKEN_EXTRA = "worbusLoadToken"

/** [items] stamped as the load [token] (their request metadata's extras), for [requestPlayOrder]. */
internal fun withLoadToken(items: List<MediaItem>, token: Long): List<MediaItem> = items.map { item ->
	item.buildUpon()
		.setRequestMetadata(
			item.requestMetadata.buildUpon()
				.setExtras(Bundle(item.requestMetadata.extras ?: Bundle.EMPTY).apply { putLong(LOAD_TOKEN_EXTRA, token) })
				.build()
		)
		.build()
}

/** How long an order waits for the queue it was built for. */
internal const val ORDER_WAIT_MS = 5_000L

private val engines = java.util.WeakHashMap<ExoPlayer, PlayOrderEngine>()

@UnstableApi
private class PlayOrderEngine(private val player: ExoPlayer) {
	private class Waiting(val order: List<Int>, val ids: List<String>, val loadToken: Long?, val done: (Boolean) -> Unit)

	private var waiting: Waiting? = null

	/** An order applied by turning shuffle on: the turn-on applies it instead of its own. */
	private var orderForTurnOn: List<Int>? = null

	private val handler = android.os.Handler(player.applicationLooper)

	init {
		player.setShuffleOrder(PlayOrderShuffleOrder(PlayOrder.identity(player.mediaItemCount)))
		player.addListener(object : Player.Listener {
			override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
				if (!shuffleModeEnabled) {
					orderForTurnOn = null
					waiting?.let { waiting = null; it.done(false) }
					return
				}
				// This event may be delivered after a later call in the same turn (media3 defers a
				// listener event raised inside another listener), so an order applied by turning
				// shuffle on is applied here, never overwritten here.
				val explicit = orderForTurnOn?.takeIf { PlayOrder.isPermutation(it, player.mediaItemCount) }
				orderForTurnOn = null
				player.setShuffleOrder(
					PlayOrderShuffleOrder(explicit ?: PlayOrder.currentFirst(player.mediaItemCount, player.currentMediaItemIndex))
				)
			}

			override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
				val w = waiting ?: return
				if (holds(w)) {
					waiting = null
					apply(w)
				}
			}
		})
	}

	fun request(order: List<Int>, ids: List<String>, loadToken: Long?, done: (Boolean) -> Unit) {
		waiting?.let { waiting = null; it.done(false) }
		if (!PlayOrder.isPermutation(order, ids.size)) {
			done(false)
			return
		}
		val w = Waiting(order, ids, loadToken, done)
		if (holds(w)) {
			apply(w)
			return
		}
		waiting = w
		handler.postDelayed({ if (waiting === w) { waiting = null; w.done(false) } }, ORDER_WAIT_MS)
	}

	/**
	 * The player holds exactly the queue [w] was built for: the same media ids in the same order,
	 * and for a whole-queue load the items of THAT load (its token), so an order for a reload of
	 * the songs the player already holds waits for the reload (W1 re-walk: applied to the old queue
	 * first, it was replaced by the reload's new-queue order).
	 */
	private fun holds(w: Waiting): Boolean {
		val ids = w.ids
		if (player.mediaItemCount != ids.size || !ids.indices.all { player.getMediaItemAt(it).mediaId == ids[it] }) return false
		val token = w.loadToken ?: return true
		return ids.isNotEmpty() && player.getMediaItemAt(0).requestMetadata.extras?.getLong(LOAD_TOKEN_EXTRA, Long.MIN_VALUE) == token
	}

	private fun apply(w: Waiting) {
		if (player.shuffleModeEnabled) {
			player.setShuffleOrder(PlayOrderShuffleOrder(w.order))
		} else {
			orderForTurnOn = w.order
			player.shuffleModeEnabled = true
		}
		w.done(true)
	}
}
