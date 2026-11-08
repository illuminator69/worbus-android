package app.worbus.ui.screens.nowPlaying.staged

import app.worbus.domain.manager.StationProvenance
import app.worbus.domain.models.SavedQueueSource
import app.worbus.shared.MediaPlayerViewModel

/*
 * navi-connect P2: the staged queue's rows grouped by who put them there, the peek row and the
 * "playing from" words, as pure functions (`QueueGroupingTest`).
 *
 * - "Autoplay": the song is in the bound station's served list (what this device saw it add). That
 *   membership decides, never whether the station still runs: a song Off kept because it was nearly
 *   playing is still the station's, and its header says the station is off.
 * - "Queued by you": a song on a bound station's queue that the user queued on this device
 *   ([MediaPlayerViewModel.userQueued]: add to queue, play next). Only next to an autoplay group: an
 *   album's songs were never "queued by you". Any other row of such a queue (a hub edit's swap-in
 *   before it is in `served`, another device's top-up, a transfer's) is "Up next" (audit L4a-04).
 * - "Up next": a queue no station is bound to (an album, a playlist, a hand-made queue).
 *
 * Groups are runs in the order listed (play order under shuffle, else queue order), not a partition:
 * a song the user adds after the autoplay run gets a second "Queued by you" header there. Rows are keyed by the mirror's stable uid (a queue can hold
 * one song twice) and headers by their first row's uid, so a regroup does not replay animations.
 */

enum class QueueGroup { Played, Playing, Queued, Autoplay, Next }

sealed interface QueueListItem {
	/** A section label before a run of [group]; [key] is the run's first row's uid. */
	data class Header(val group: QueueGroup, val key: Long) : QueueListItem

	/** The queue's row [index], keyed by [uid]. */
	data class Row(val index: Int, val uid: Long, val group: QueueGroup) : QueueListItem
}

/**
 * The list the layer draws, in play order ([playOrder], the player's shuffle order, when it describes
 * this queue; else queue order): songs already played (no header), "Now playing", then runs of
 * "Queued by you" and "Autoplay" for a bound station's queue, or one "Up next" otherwise. Each row
 * keeps its QUEUE index and that index's uid, so a tap, a swipe or a remove acts on the song shown
 * (B-341, worbus-android#2: under shuffle "Up next" listed the queue, not what plays next). [songIds]
 * and [uids] are the mirror's, index for index. Null [playOrder] (no shuffle, another device playing:
 * [queuePlayOrder]) is today's list exactly.
 */
fun queueListItems(
	songIds: List<String>,
	uids: List<Long>,
	currentIndex: Int,
	bound: StationProvenance?,
	userQueued: Set<String>,
	playOrder: List<Int>? = null
): List<QueueListItem> {
	val count = minOf(songIds.size, uids.size)
	if (count == 0) return emptyList()
	val order = queueListOrder(count, playOrder)
	val playing = currentIndex.takeIf { it in 0 until count }
	val playingAt = playing?.let { order.indexOf(it) }
	// "Queued by you" only beside an autoplay group: with nothing upcoming the station's, the rest
	// is one "Up next" (a station continued here after a transfer, or one Off emptied). Upcoming in
	// play order: under shuffle a song later in the queue may already have played.
	val served = bound?.served?.toHashSet()?.takeIf { set ->
		order.subList((playingAt?.plus(1) ?: 0), count).any { songIds[it] in set }
	}
	val items = ArrayList<QueueListItem>(count + 4)
	var previous: QueueGroup? = null
	for ((at, i) in order.withIndex()) {
		val group = when {
			playingAt != null && at < playingAt -> QueueGroup.Played
			i == playing -> QueueGroup.Playing
			served == null -> QueueGroup.Next
			songIds[i] in served -> QueueGroup.Autoplay
			songIds[i] in userQueued -> QueueGroup.Queued
			else -> QueueGroup.Next
		}
		if (group != QueueGroup.Played && group != previous) items += QueueListItem.Header(group, uids[i])
		items += QueueListItem.Row(i, uids[i], group)
		previous = group
	}
	return items
}

/**
 * The queue indices in the order the layer lists them: [playOrder] when it is an order of exactly
 * these [count] songs (a frame behind, or a mirror mid-rebuild, it is not trusted), else queue order.
 */
fun queueListOrder(count: Int, playOrder: List<Int>?): List<Int> =
	if (playOrder != null && isOrderOf(count, playOrder)) playOrder else (0 until count).toList()

/** [order] holds each of 0 until [count] exactly once. */
private fun isOrderOf(count: Int, order: List<Int>): Boolean {
	if (order.size != count) return false
	val seen = BooleanArray(count)
	for (i in order) {
		if (i !in 0 until count || seen[i]) return false
		seen[i] = true
	}
	return true
}

/**
 * The play order the queue lists by: the LOCAL player's ([playOrder], null without shuffle), and
 * none while another device plays, whose queue the local order says nothing about. The peek row and
 * the queue's list read the same value.
 */
fun queuePlayOrder(isRemoteActive: Boolean, playOrder: List<Int>?): List<Int>? = if (isRemoteActive) null else playOrder

/**
 * Whether the queue's rows may be dragged: not while it is listed in a shuffle order. A drag moves a
 * row among its neighbours on screen, and under shuffle those are not its neighbours in the queue.
 */
fun queueReorderAllowed(playOrder: List<Int>?): Boolean = playOrder == null || playOrder.indices.all { playOrder[it] == it }

/**
 * The queue indices the Remaining count and duration add up: the playing song and every row after it
 * as listed ([queueListOrder]), nothing for an index outside the queue.
 */
fun remainingQueueIndices(size: Int, currentIndex: Int, playOrder: List<Int>?): List<Int> {
	if (currentIndex !in 0 until size) return emptyList()
	val order = queueListOrder(size, playOrder)
	return order.drop(order.indexOf(currentIndex))
}

/**
 * Where each row stands in its run (the rows between two headers, or the played rows before the
 * first): its uid to (position, run length), for the row's segmented shape.
 */
fun rowRunPositions(items: List<QueueListItem>): Map<Long, Pair<Int, Int>> {
	val out = HashMap<Long, Pair<Int, Int>>(items.size)
	val run = ArrayList<Long>()
	fun flush() {
		run.forEachIndexed { i, uid -> out[uid] = i to run.size }
		run.clear()
	}
	for (item in items) {
		when (item) {
			is QueueListItem.Header -> flush()
			is QueueListItem.Row -> run += item.uid
		}
	}
	flush()
	return out
}

/**
 * The queue index of the song after the playing one in play order ([order], the shuffle order, or
 * null for queue order), or null at the end. An order that does not describe this queue (a frame
 * behind it) is not trusted.
 */
fun peekRow(songIds: List<String>, currentIndex: Int, order: List<Int>?): Int? =
	upcomingIndices(songIds.size, currentIndex, order, 1).firstOrNull()

/**
 * The queue indices of the songs after the playing one, in play order, at most [count]: [order] (the
 * shuffle order) when it describes this queue of [size] songs, else queue order; nothing for an index
 * outside the queue. The prefetch follows it, so under shuffle it works out the songs that really
 * come next (audit L4a-08).
 */
fun upcomingIndices(size: Int, currentIndex: Int, order: List<Int>?, count: Int): List<Int> {
	if (currentIndex !in 0 until size || count <= 0) return emptyList()
	if (order != null && order.size == size && currentIndex in order) {
		return order.drop(order.indexOf(currentIndex) + 1).take(count)
	}
	return ((currentIndex + 1) until size).take(count)
}

/** The header's "Playing from": [kind] ("album", "playlist") when the name alone does not say it. */
data class PlayingFrom(val kind: String?, val name: String)

/**
 * What the queue is playing from: the name the user gave it ([name]) or the one it was born with
 * ([sourceName]), and the kind for an album or a playlist. A radio, a mix or a search is named for
 * what it is already ("Paper Lanterns radio", "Search: rain"). Null when nothing is known: the header
 * then says nothing rather than a guess.
 */
fun playingFromText(kind: String?, name: String?, sourceName: String?): PlayingFrom? {
	val shown = name?.takeIf { it.isNotBlank() } ?: sourceName?.takeIf { it.isNotBlank() } ?: return null
	val shownKind = when (kind) {
		SavedQueueSource.ALBUM, SavedQueueSource.PLAYLIST -> kind
		else -> null
	}
	return PlayingFrom(shownKind, shown)
}

/** The songs the user has queued on this device after [ids] were added by their own hand. */
fun userQueuedAfterAdd(current: Set<String>, ids: List<String>): Set<String> = current + ids

/**
 * What stays of [current] when the queue changes: everything while it is the same queue and not
 * empty; nothing once it is replaced (a mix, a transfer, an album played: [previousQueueId] gives way
 * to another id or to none) or cleared. A first id where there was none is the same queue being
 * started by the add itself.
 */
fun userQueuedAfterQueueChange(
	current: Set<String>,
	previousQueueId: String?,
	queueId: String?,
	queueEmpty: Boolean
): Set<String> = if (queueEmpty || (previousQueueId != null && previousQueueId != queueId)) emptySet() else current

