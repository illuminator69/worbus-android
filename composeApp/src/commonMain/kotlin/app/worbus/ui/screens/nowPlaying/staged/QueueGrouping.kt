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
 * Unshuffled, groups are runs in queue order, not a partition: a song the user adds after the
 * autoplay run gets a second "Queued by you" header there. Shuffled (a play order is given, B-350),
 * the play order alternates the groups at random (a top-up lands anywhere in it), so a header per
 * run drew a section for every switch: everything after "Now playing" is ONE "Up next" instead,
 * each row keeps its own group, and the autoplay ones are marked on the row ([rowShowsAutoplayMark]).
 * Rows are keyed by the mirror's stable uid (a queue can hold one song twice) and headers by their
 * first row's uid, so a regroup does not replay animations.
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
 * this queue; else queue order): songs already played (no header), "Now playing", then, for a bound
 * station's queue, runs of "Queued by you" and "Autoplay", or one "Up next" otherwise. A non-null
 * [playOrder] is "shuffled" (also while it is a frame ahead of the mirror and [lastShown] stands in
 * for it): then every row after "Now playing" sits under ONE "Up next" header, whoever added it
 * (B-350), and a row keeps its own group so the screen can mark the autoplay ones. Each row
 * keeps its QUEUE index and that index's uid, so a tap, a swipe or a remove acts on the song shown
 * (B-341, worbus-android#2: under shuffle "Up next" listed the queue, not what plays next). [songIds]
 * and [uids] are the mirror's, index for index. Null [playOrder] (no shuffle, another device playing:
 * [queuePlayOrder]) is today's list exactly.
 *
 * [lastShown]: the uids of the listing on screen, in its order ([listedUids]). The player publishes a
 * new order the moment its queue changes, and the mirror catches up a composition or more later, so
 * under shuffle every size change (a swipe-remove, an autoplay top-up) meets an order that is not yet
 * this mirror's. Listing in queue order then drew the whole list in the wrong order for a frame and
 * animated every row there and back (lane N review I1); the listing on screen is kept instead, mapped
 * onto the mirror by uid ([queueListOrder]), until the order and the mirror agree.
 */
fun queueListItems(
	songIds: List<String>,
	uids: List<Long>,
	currentIndex: Int,
	bound: StationProvenance?,
	userQueued: Set<String>,
	playOrder: List<Int>? = null,
	lastShown: List<Long>? = null
): List<QueueListItem> {
	val count = minOf(songIds.size, uids.size)
	if (count == 0) return emptyList()
	val order = queueListOrder(count, playOrder, uids, lastShown)
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
		val headerGroup = when {
			group == QueueGroup.Played -> null
			// Shuffled: one header for everything upcoming, right after "Now playing" (or first, with
			// nothing playing); never between two upcoming rows, whoever added them.
			playOrder != null && group != QueueGroup.Playing ->
				QueueGroup.Next.takeIf { previous == null || previous == QueueGroup.Playing || previous == QueueGroup.Played }
			group != previous -> group
			else -> null
		}
		if (headerGroup != null) items += QueueListItem.Header(headerGroup, uids[i])
		items += QueueListItem.Row(i, uids[i], group)
		previous = group
	}
	return items
}

/**
 * The queue indices in the order the layer lists them: the [playOrder] when it is an order of exactly
 * these [count] songs ([trustedPlayOrder]), else queue order. An order that is not yet this mirror's
 * (a frame ahead of it, or a mirror mid-rebuild) keeps the listing on screen instead, when there is
 * one ([lastShown], the uids it listed, in order): the rows it listed that are still here, in that
 * order, then any row it did not list, in queue order. It lasts until the mirror and the order agree.
 */
fun queueListOrder(count: Int, playOrder: List<Int>?, uids: List<Long> = emptyList(), lastShown: List<Long>? = null): List<Int> {
	trustedPlayOrder(count, playOrder)?.let { return it }
	if (playOrder == null || lastShown == null || uids.size < count) return (0 until count).toList()
	val at = HashMap<Long, Int>(count * 2)
	for (i in 0 until count) at[uids[i]] = i
	val listed = BooleanArray(count)
	val out = ArrayList<Int>(count)
	for (uid in lastShown) {
		val i = at[uid] ?: continue
		if (listed[i]) continue
		listed[i] = true
		out += i
	}
	for (i in 0 until count) if (!listed[i]) out += i
	return out
}

/**
 * Whether [row] wears the quiet "added by autoplay" mark: while the list is [shuffled] (the same
 * value that makes [queueListItems] take its shuffled branch, a non-null play order), a row of the
 * Autoplay group. Unshuffled, the group's own header already says it.
 */
fun rowShowsAutoplayMark(row: QueueListItem.Row, shuffled: Boolean): Boolean = shuffled && row.group == QueueGroup.Autoplay

/** The uids of [items]' rows, in the order listed: what [queueListItems] keeps as `lastShown`. */
fun listedUids(items: List<QueueListItem>): List<Long> = items.mapNotNull { (it as? QueueListItem.Row)?.uid }

/**
 * The one rule for whether a play order describes a queue of [count] songs (the list, the peek row,
 * the prefetch and the drag all read it, lane N review Minor 1): [order] when it holds each of
 * 0 until [count] exactly once, else null.
 */
fun trustedPlayOrder(count: Int, order: List<Int>?): List<Int>? = order?.takeIf { isOrderOf(count, it) }

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
 * Whether the [count] rows may be dragged: with no play order, or one that is this queue's in queue
 * order. Not while the queue is listed in a shuffle order (a drag moves a row among its neighbours on
 * screen, and under shuffle those are not its neighbours in the queue), nor while an order is not yet
 * this queue's ([trustedPlayOrder]): shuffle is on then, and the listing on screen may be a shuffled one.
 */
fun queueReorderAllowed(count: Int, playOrder: List<Int>?): Boolean {
	if (playOrder == null) return true
	val order = trustedPlayOrder(count, playOrder) ?: return false
	return order.indices.all { order[it] == it }
}

/**
 * The queue indices the Remaining count and duration add up: the playing row and every row after it,
 * as [items] lists them (play order under shuffle, the listing on screen while an order is a frame
 * ahead). With no playing row in the list, every row.
 */
fun remainingQueueIndices(items: List<QueueListItem>): List<Int> =
	items.mapNotNull { (it as? QueueListItem.Row)?.takeIf { row -> row.group != QueueGroup.Played }?.index }

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
	trustedPlayOrder(size, order)?.let { trusted ->
		return trusted.drop(trusted.indexOf(currentIndex) + 1).take(count)
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
