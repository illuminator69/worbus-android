package app.worbus.shared

import kotlin.random.Random

/**
 * The play order of a shuffled queue, and the ONE rule for what plays next under shuffle
 * (W1, navi-connect DESIGN-w1-play-order-2026-11-10 §2). An order is a permutation of the queue's
 * rows: `order[p]` is the row played at play position `p`. The service's ExoPlayer applies these
 * functions through `PlayOrderShuffleOrder`, the app's player state through the same calls, and
 * the hub its own copy of the same table, so all three agree without asking each other.
 *
 * | Edit | Rule |
 * |---|---|
 * | shuffle on, re-shuffle | [currentFirst]: the playing row first, the rest random |
 * | a new queue under shuffle | [currentFirst] with its start row |
 * | Play next (1..n rows) | [insert] at `current + 1`: right after the playing row, in the order given |
 * | Add to queue, a top-up | [insert] at the end: the end of play order, in the order given |
 * | insert elsewhere | [insert]: right after its queue predecessor's play position |
 * | remove | [remove]: dropped, the rest keep their order |
 * | move | [move]: queue order only, every row keeps its play position |
 */
object PlayOrder {
	fun identity(n: Int): List<Int> = List(n) { it }

	/** True when [order] lists every row of a queue of [size] exactly once. */
	fun isPermutation(order: List<Int>?, size: Int): Boolean {
		if (order == null || order.size != size) return false
		val seen = BooleanArray(size)
		for (row in order) {
			if (row !in 0 until size || seen[row]) return false
			seen[row] = true
		}
		return true
	}

	/**
	 * [current] first, every other row of a queue of [size] after it in random order. A [current]
	 * outside the queue (none playing) makes the whole order random.
	 */
	fun currentFirst(size: Int, current: Int, random: Random = Random): List<Int> {
		if (current !in 0 until size) return identity(size).shuffled(random)
		return listOf(current) + (0 until size).filter { it != current }.shuffled(random)
	}

	/**
	 * [count] rows inserted into the queue at row [at]. Appended rows ([at] == the old size) go to
	 * the end of play order; rows inserted inside the queue go right after the play position of
	 * their queue predecessor (row `at - 1`, ruling 6), and rows inserted above the first row right
	 * BEFORE their queue successor, the old row 0 (the controller's ruling, W1 review). Either way in
	 * the order given. Play next is [playNext], never this. Into an empty order they are random: a new queue, whose start row the caller
	 * then puts first ([currentFirst]).
	 */
	fun insert(order: List<Int>, at: Int, count: Int, random: Random = Random): List<Int> {
		if (count <= 0) return order
		val size = order.size
		val where = at.coerceIn(0, size)
		val inserted = (where until where + count).toList()
		if (size == 0) return inserted.shuffled(random)
		val shifted = order.map { if (it >= where) it + count else it }
		val position = when {
			where >= size -> shifted.size
			where == 0 -> shifted.indexOf(count) // the old row 0, now shifted to `count`
			else -> shifted.indexOf(where - 1) + 1
		}
		return shifted.subList(0, position) + inserted + shifted.subList(position, shifted.size)
	}

	/**
	 * Play next: [count] rows inserted at queue row [at] play right after the playing row [current],
	 * in the order given (ruling 6). An explicit edit, never inferred from [at]: when the playing row
	 * is the last queue row, Play next lands where an append does (`at == size`), and [insert] would
	 * put it at the end of play order (W1 review finding 1). With no playing row it is [insert].
	 */
	fun playNext(order: List<Int>, at: Int, count: Int, current: Int): List<Int> {
		if (count <= 0) return order
		val size = order.size
		if (size == 0 || current !in 0 until size) return insert(order, at, count)
		val where = at.coerceIn(0, size)
		val shifted = order.map { if (it >= where) it + count else it }
		val playing = if (current >= where) current + count else current
		val position = shifted.indexOf(playing) + 1
		return shifted.subList(0, position) + (where until where + count) + shifted.subList(position, shifted.size)
	}

	/** Rows [from, toExclusive) removed: dropped from the order, the rest keep their order. */
	fun remove(order: List<Int>, from: Int, toExclusive: Int): List<Int> {
		val count = toExclusive - from
		if (count <= 0) return order
		return order.filter { it !in from until toExclusive }.map { if (it >= toExclusive) it - count else it }
	}

	/**
	 * Rows [from, toExclusive) moved so the first of them is at row [newFrom] (media3's
	 * `moveMediaItems`, i.e. `Util.moveItems`): the queue order changes, every row keeps its play
	 * position.
	 */
	fun move(order: List<Int>, from: Int, toExclusive: Int, newFrom: Int): List<Int> {
		val size = order.size
		if (from == newFrom || from >= toExclusive || size == 0) return order
		val rows = identity(size).toMutableList()
		val moved = rows.subList(from, toExclusive).toList()
		repeat(moved.size) { rows.removeAt(from) }
		rows.addAll(newFrom.coerceIn(0, rows.size), moved)
		val newRowOf = IntArray(size)
		rows.forEachIndexed { newRow, oldRow -> newRowOf[oldRow] = newRow }
		return order.map { newRowOf[it] }
	}

	/** The row after [current] in play order, or null at the end (repeat off). */
	fun successor(order: List<Int>, current: Int): Int? {
		val p = order.indexOf(current)
		return if (p < 0) null else order.getOrNull(p + 1)
	}

	/**
	 * The order the app's player state shows, read on the app's controller (B-346).
	 *
	 * The controller's timeline is not always the service player's: right after a queue edit made
	 * through it, media3's MediaController masks the edit with a timeline in QUEUE order
	 * (`MediaUtils.generateUnshuffledIndices`, 1.11.0) until the session's own arrives. So an
	 * order the app worked out and sent ([pending]) is shown until the timeline shows it; the
	 * timeline is believed otherwise (the notification's shuffle button, another controller).
	 * The result is always an order of exactly [queueSize] rows, or null.
	 *
	 * [timeline] is the controller timeline's shuffle order, [shown] what the state shows now,
	 * [landed] that the service accepted [pending] and a session timeline has arrived since.
	 */
	fun resolve(shuffle: Boolean, timeline: List<Int>?, pending: List<Int>?, shown: List<Int>?, queueSize: Int, landed: Boolean = false): Reading {
		if (!shuffle) return Reading(null, null)
		if (pending != null) {
			if (timeline == pending) return Reading(pending, null)
			// The service has accepted it and a timeline has come since: that timeline is the
			// player's (W1 review finding 4; an outside edit may have moved the order meanwhile).
			if (landed && isPermutation(timeline, queueSize)) return Reading(timeline, null)
			if (isPermutation(pending, queueSize)) return Reading(pending, pending)
			// Sent for a queue the state does not hold yet (an edit still going in): keep waiting,
			// and show what is an order of this one.
			val holding = shown?.takeIf { isPermutation(it, queueSize) } ?: timeline?.takeIf { isPermutation(it, queueSize) }
			return Reading(holding, pending)
		}
		return Reading(
			timeline?.takeIf { isPermutation(it, queueSize) } ?: shown?.takeIf { isPermutation(it, queueSize) },
			null
		)
	}

	/** What [resolve] shows, and the order still waited for. */
	data class Reading(val order: List<Int>?, val pending: List<Int>?)
}
