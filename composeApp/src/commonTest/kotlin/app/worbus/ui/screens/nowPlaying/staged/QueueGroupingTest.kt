package app.worbus.ui.screens.nowPlaying.staged

import app.worbus.domain.manager.StationProvenance
import app.worbus.domain.models.SavedQueueSource
import app.worbus.ui.screens.nowPlaying.staged.QueueListItem.Header
import app.worbus.ui.screens.nowPlaying.staged.QueueListItem.Row
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The staged queue's rows (P2, `QueueGrouping.kt`): grouped by who put them there, keyed by the
 * mirror's stable uids, the peek row's "up next" in play order, and the "playing from" words.
 */
class QueueGroupingTest {

	private fun ids(n: Int) = (0 until n).map { "s$it" }
	private fun uids(n: Int) = (0 until n).map { 100L + it }

	private val station = StationProvenance(
		queueId = "q_1",
		stationId = "st_1",
		name = "Paper Lanterns radio",
		character = "balanced",
		served = listOf("s3", "s4", "s5")
	)

	@Test
	fun aQueueNoStationIsBoundToIsNowPlayingThenUpNext() {
		val items = queueListItems(ids(5), uids(5), currentIndex = 1, bound = null, userQueued = emptySet())
		assertEquals(
			listOf(
				Row(0, 100L, QueueGroup.Played),
				Header(QueueGroup.Playing, 101L),
				Row(1, 101L, QueueGroup.Playing),
				Header(QueueGroup.Next, 102L),
				Row(2, 102L, QueueGroup.Next),
				Row(3, 103L, QueueGroup.Next),
				Row(4, 104L, QueueGroup.Next)
			),
			items
		)
	}

	@Test
	fun aStationQueueIsQueuedByYouThenAutoplayOneHeaderPerRun() {
		val items = queueListItems(ids(6), uids(6), currentIndex = 0, bound = station, userQueued = setOf("s1", "s2"))
		assertEquals(
			listOf(
				Header(QueueGroup.Playing, 100L),
				Row(0, 100L, QueueGroup.Playing),
				Header(QueueGroup.Queued, 101L),
				Row(1, 101L, QueueGroup.Queued),
				Row(2, 102L, QueueGroup.Queued),
				Header(QueueGroup.Autoplay, 103L),
				Row(3, 103L, QueueGroup.Autoplay),
				Row(4, 104L, QueueGroup.Autoplay),
				Row(5, 105L, QueueGroup.Autoplay)
			),
			items
		)
	}

	@Test
	fun aSongQueuedAfterTheAutoplayRunGetsASecondQueuedHeader() {
		val songs = listOf("s0", "s1", "s3", "s4", "s5", "s2")
		val items = queueListItems(songs, uids(6), currentIndex = 0, bound = station, userQueued = setOf("s1", "s2"))
		assertEquals(
			listOf(
				Header(QueueGroup.Playing, 100L),
				Row(0, 100L, QueueGroup.Playing),
				Header(QueueGroup.Queued, 101L),
				Row(1, 101L, QueueGroup.Queued),
				Header(QueueGroup.Autoplay, 102L),
				Row(2, 102L, QueueGroup.Autoplay),
				Row(3, 103L, QueueGroup.Autoplay),
				Row(4, 104L, QueueGroup.Autoplay),
				Header(QueueGroup.Queued, 105L),
				Row(5, 105L, QueueGroup.Queued)
			),
			items
		)
	}

	@Test
	fun membershipInServedDecidesTheGroupNotWhetherTheStationRuns() {
		// Off kept s3 because it was nearly playing; s1 and s2 are the user's.
		val stopped = station.copy(stopped = true, served = listOf("s3"))
		val items = queueListItems(ids(4), uids(4), currentIndex = 0, bound = stopped, userQueued = setOf("s1", "s2"))
		assertEquals(
			listOf(
				Header(QueueGroup.Playing, 100L),
				Row(0, 100L, QueueGroup.Playing),
				Header(QueueGroup.Queued, 101L),
				Row(1, 101L, QueueGroup.Queued),
				Row(2, 102L, QueueGroup.Queued),
				Header(QueueGroup.Autoplay, 103L),
				Row(3, 103L, QueueGroup.Autoplay)
			),
			items
		)
	}

	@Test
	fun oneSongIsNowPlayingOnlyAndAnEmptyQueueIsNothing() {
		assertEquals(
			listOf(Header(QueueGroup.Playing, 100L), Row(0, 100L, QueueGroup.Playing)),
			queueListItems(listOf("s0"), listOf(100L), currentIndex = 0, bound = station, userQueued = emptySet())
		)
		assertEquals(emptyList(), queueListItems(emptyList(), emptyList(), currentIndex = -1, bound = null, userQueued = emptySet()))
	}

	@Test
	fun theSameSongTwiceIsTwoRowsWithTheirOwnKeys() {
		val items = queueListItems(listOf("s0", "s7", "s7"), listOf(5L, 9L, 12L), currentIndex = 0, bound = null, userQueued = emptySet())
		val rows = items.filterIsInstance<Row>()
		assertEquals(listOf(5L, 9L, 12L), rows.map { it.uid })
		assertEquals(listOf(1, 2), rows.filter { it.group == QueueGroup.Next }.map { it.index })
	}

	@Test
	fun nothingPlayingIsAllUpcoming() {
		// A queue whose index is not known yet (the first frame of a restore): no "Now playing".
		val items = queueListItems(ids(2), uids(2), currentIndex = -1, bound = null, userQueued = emptySet())
		assertEquals(listOf(Header(QueueGroup.Next, 100L), Row(0, 100L, QueueGroup.Next), Row(1, 101L, QueueGroup.Next)), items)
	}

	@Test
	fun thePeekRowIsTheNextSongInPlayOrder() {
		assertEquals(3, peekRow(ids(5), currentIndex = 2, order = null))
		assertNull(peekRow(ids(5), currentIndex = 4, order = null))
		assertEquals(1, peekRow(ids(5), currentIndex = 3, order = listOf(0, 3, 1, 2, 4)))
		assertNull(peekRow(ids(5), currentIndex = 4, order = listOf(0, 3, 1, 2, 4)))
		// An order for another queue (a frame behind) is not trusted: queue order.
		assertEquals(3, peekRow(ids(5), currentIndex = 2, order = listOf(0, 1, 2)))
		assertNull(peekRow(emptyList(), currentIndex = -1, order = null))
	}

	@Test
	fun playingFromNamesTheSourceAndOnlySaysWhatItKnows() {
		assertEquals(PlayingFrom("album", "Quiet Engines"), playingFromText(SavedQueueSource.ALBUM, null, "Quiet Engines"))
		assertEquals(PlayingFrom("playlist", "Late"), playingFromText(SavedQueueSource.PLAYLIST, null, "Late"))
		// A radio's name already says what it is.
		assertEquals(PlayingFrom(null, "Paper Lanterns radio"), playingFromText(SavedQueueSource.RADIO, "Paper Lanterns radio", null))
		// Nothing known: the header says nothing rather than a guess.
		assertNull(playingFromText(SavedQueueSource.MANUAL, null, null))
		assertNull(playingFromText(SavedQueueSource.ALBUM, " ", ""))
		// A name the user gave wins.
		assertEquals(PlayingFrom("album", "Sunday"), playingFromText(SavedQueueSource.ALBUM, "Sunday", "Quiet Engines"))
		assertEquals(PlayingFrom(null, "Search: rain"), playingFromText(SavedQueueSource.MOOD_FLOW, null, "Search: rain"))
	}
	@Test
	fun queuedByYouOnlyStandsBesideAnAutoplayGroup() {
		// A station whose songs this device never saw added (a transfer), or all taken out by Off:
		// nothing upcoming is the station's, so the rest is one "Up next", never "Queued by you".
		val unseen = station.copy(served = emptyList())
		assertEquals(
			listOf(Header(QueueGroup.Playing, 100L), Row(0, 100L, QueueGroup.Playing), Header(QueueGroup.Next, 101L), Row(1, 101L, QueueGroup.Next)),
			queueListItems(ids(2), uids(2), currentIndex = 0, bound = unseen, userQueued = emptySet())
		)
		// A served song already played does not make one either.
		val playedOnly = station.copy(served = listOf("s0"))
		assertEquals(
			listOf(Row(0, 100L, QueueGroup.Played), Header(QueueGroup.Playing, 101L), Row(1, 101L, QueueGroup.Playing), Header(QueueGroup.Next, 102L), Row(2, 102L, QueueGroup.Next)),
			queueListItems(ids(3), uids(3), currentIndex = 1, bound = playedOnly, userQueued = emptySet())
		)
	}

	@Test
	fun eachRowKnowsItsPlaceInItsRunForItsShape() {
		val items = queueListItems(ids(6), uids(6), currentIndex = 1, bound = station.copy(served = listOf("s4", "s5")), userQueued = setOf("s2", "s3"))
		// s0 played | Playing s1 | Queued s2 s3 | Autoplay s4 s5
		assertEquals(
			mapOf(100L to (0 to 1), 101L to (0 to 1), 102L to (0 to 2), 103L to (1 to 2), 104L to (0 to 2), 105L to (1 to 2)),
			rowRunPositions(items)
		)
	}

	// ---- audit L4a-04: "Queued by you" only for what the user queued -------------------------- //

	@Test
	fun aStationQueueSaysQueuedOnlyForTheUsersRowsAndUpNextForTheRest() {
		// s1 the station served, s2 the user queued, s3 neither (another device's top-up, a transfer's).
		val bound = station.copy(served = listOf("s1"))
		val items = queueListItems(ids(4), uids(4), currentIndex = 0, bound = bound, userQueued = setOf("s2"))
		assertEquals(
			listOf(
				Header(QueueGroup.Playing, 100L),
				Row(0, 100L, QueueGroup.Playing),
				Header(QueueGroup.Autoplay, 101L),
				Row(1, 101L, QueueGroup.Autoplay),
				Header(QueueGroup.Queued, 102L),
				Row(2, 102L, QueueGroup.Queued),
				Header(QueueGroup.Next, 103L),
				Row(3, 103L, QueueGroup.Next)
			),
			items
		)
	}

	@Test
	fun aHubEditSwapInIsAutoplayOnceItIsInServed() {
		val swapped = station.copy(served = listOf("s3", "s4", "s5", "x1", "x2"))
		val items = queueListItems(listOf("s0", "x1", "x2"), uids(3), currentIndex = 0, bound = swapped, userQueued = emptySet())
		assertEquals(listOf(QueueGroup.Playing, QueueGroup.Autoplay, QueueGroup.Autoplay), items.filterIsInstance<Row>().map { it.group })
	}

	@Test
	fun aQueueWithNoServedRowAfterThePlayingOneIsOneUpNext() {
		val items = queueListItems(ids(3), uids(3), currentIndex = 0, bound = station.copy(served = listOf("s0")), userQueued = setOf("s1"))
		assertEquals(listOf(QueueGroup.Playing, QueueGroup.Next, QueueGroup.Next), items.filterIsInstance<Row>().map { it.group })
	}

	@Test
	fun aSongBothServedAndUserQueuedReadsAutoplay() {
		// Registered cost: membership is by id.
		val items = queueListItems(ids(2), uids(2), currentIndex = 0, bound = station.copy(served = listOf("s1")), userQueued = setOf("s1"))
		assertEquals(QueueGroup.Autoplay, items.filterIsInstance<Row>()[1].group)
	}

	// ---- what the player remembers the user queued ------------------------------------------- //

	@Test
	fun theUsersAddsAreRememberedAndAReplacedQueueForgetsThem() {
		val added = userQueuedAfterAdd(emptySet(), listOf("a", "b"))
		assertEquals(setOf("a", "b"), added)
		assertEquals(setOf("a", "b", "c"), userQueuedAfterAdd(added, listOf("c")))
		// The same queue goes on: kept. Another queue (a mix, a transfer, a new album): forgotten.
		assertEquals(added, userQueuedAfterQueueChange(added, previousQueueId = "q1", queueId = "q1", queueEmpty = false))
		assertEquals(emptySet(), userQueuedAfterQueueChange(added, previousQueueId = "q1", queueId = "q2", queueEmpty = false))
		assertEquals(emptySet(), userQueuedAfterQueueChange(added, previousQueueId = "q1", queueId = null, queueEmpty = false))
		// The add that starts a queue gives it its first id: kept.
		assertEquals(added, userQueuedAfterQueueChange(added, previousQueueId = null, queueId = "q1", queueEmpty = false))
		// Cleared.
		assertEquals(emptySet(), userQueuedAfterQueueChange(added, previousQueueId = "q1", queueId = "q1", queueEmpty = true))
	}

	// Audit L4a-08: the indices after the playing one in play order, the same trust rule as peekRow.
	@Test
	fun upcomingIndicesFollowPlayOrder() {
		assertEquals(listOf(3, 1), upcomingIndices(size = 4, currentIndex = 0, order = listOf(2, 0, 3, 1), count = 2))
		assertEquals(listOf(1, 2), upcomingIndices(size = 4, currentIndex = 0, order = listOf(1, 0), count = 2))
		assertEquals(listOf(1, 2, 3), upcomingIndices(size = 4, currentIndex = 0, order = null, count = 9))
		assertEquals(emptyList(), upcomingIndices(size = 4, currentIndex = 7, order = null, count = 2))
	}

	// ---- B-341 (worbus-android#2): the layer under shuffle lists what plays next ---------------- //

	@Test
	fun shuffledUpNextFollowsPlayOrder() {
		val items = queueListItems(ids(5), uids(5), currentIndex = 0, bound = null, userQueued = emptySet(), playOrder = listOf(0, 3, 1, 4, 2))
		assertEquals(listOf(3, 1, 4, 2), items.filterIsInstance<Row>().filter { it.group == QueueGroup.Next }.map { it.index })
		assertEquals(
			listOf(
				Header(QueueGroup.Playing, 100L),
				Row(0, 100L, QueueGroup.Playing),
				Header(QueueGroup.Next, 103L),
				Row(3, 103L, QueueGroup.Next),
				Row(1, 101L, QueueGroup.Next),
				Row(4, 104L, QueueGroup.Next),
				Row(2, 102L, QueueGroup.Next)
			),
			items
		)
	}

	@Test
	fun shuffledRowsKeepQueueIndex() {
		val order = listOf(0, 3, 1, 4, 2)
		// The playing song is queue index 3, at play-order position 1.
		val rows = queueListItems(ids(5), uids(5), currentIndex = 3, bound = null, userQueued = emptySet(), playOrder = order)
			.filterIsInstance<Row>()
		// Listed in play order, each row still names its queue index, and its key is that index's uid:
		// a tap or a swipe (both go by the row's uid to the mirror's index) acts on the song shown.
		assertEquals(order, rows.map { it.index })
		rows.forEach { assertEquals(uids(5)[it.index], it.uid) }
		val firstUpNext = rows.first { it.group == QueueGroup.Next }
		assertEquals(order[order.indexOf(3) + 1], firstUpNext.index)
		assertEquals(1, firstUpNext.index)
	}

	@Test
	fun shuffledPlayedRowsFollowPlayOrder() {
		val order = listOf(2, 4, 0, 1, 3)
		val rows = queueListItems(ids(5), uids(5), currentIndex = 0, bound = null, userQueued = emptySet(), playOrder = order)
			.filterIsInstance<Row>()
		assertEquals(order.subList(0, 2), rows.filter { it.group == QueueGroup.Played }.map { it.index })
		assertEquals(listOf(0), rows.filter { it.group == QueueGroup.Playing }.map { it.index })
		assertEquals(listOf(1, 3), rows.filter { it.group == QueueGroup.Next }.map { it.index })
	}

	@Test
	fun remoteListsInQueueOrder() {
		// Another device plays: the local player's shuffle order says nothing about that queue.
		val order = queuePlayOrder(isRemoteActive = true, playOrder = listOf(0, 3, 1, 4, 2))
		assertNull(order)
		val queueOrder = listOf(
			Row(0, 100L, QueueGroup.Played),
			Header(QueueGroup.Playing, 101L),
			Row(1, 101L, QueueGroup.Playing),
			Header(QueueGroup.Next, 102L),
			Row(2, 102L, QueueGroup.Next),
			Row(3, 103L, QueueGroup.Next),
			Row(4, 104L, QueueGroup.Next)
		)
		assertEquals(queueOrder, queueListItems(ids(5), uids(5), currentIndex = 1, bound = null, userQueued = emptySet(), playOrder = order))
		assertEquals(queueOrder, queueListItems(ids(5), uids(5), currentIndex = 1, bound = null, userQueued = emptySet()))
		// This device plays: its own order is used.
		assertEquals(listOf(0, 3, 1, 4, 2), queuePlayOrder(isRemoteActive = false, playOrder = listOf(0, 3, 1, 4, 2)))
		// An order for another queue (a frame behind, a mirror mid-rebuild) is not trusted: queue order.
		assertEquals(queueOrder, queueListItems(ids(5), uids(5), currentIndex = 1, bound = null, userQueued = emptySet(), playOrder = listOf(1, 0, 2)))
		assertEquals(queueOrder, queueListItems(ids(5), uids(5), currentIndex = 1, bound = null, userQueued = emptySet(), playOrder = listOf(0, 1, 1, 3, 4)))
	}

	@Test
	fun boundStationGroupsUnderShuffle() {
		// s3 s4 s5 the station served, s1 s2 the user queued; played in the order 0 4 1 5 2 3.
		val items = queueListItems(
			ids(6), uids(6), currentIndex = 0, bound = station, userQueued = setOf("s1", "s2"),
			playOrder = listOf(0, 4, 1, 5, 2, 3)
		)
		assertEquals(
			listOf(
				Header(QueueGroup.Playing, 100L),
				Row(0, 100L, QueueGroup.Playing),
				Header(QueueGroup.Autoplay, 104L),
				Row(4, 104L, QueueGroup.Autoplay),
				Header(QueueGroup.Queued, 101L),
				Row(1, 101L, QueueGroup.Queued),
				Header(QueueGroup.Autoplay, 105L),
				Row(5, 105L, QueueGroup.Autoplay),
				Header(QueueGroup.Queued, 102L),
				Row(2, 102L, QueueGroup.Queued),
				Header(QueueGroup.Autoplay, 103L),
				Row(3, 103L, QueueGroup.Autoplay)
			),
			items
		)
		// "Queued by you" only beside an autoplay group still counts what is upcoming in play order:
		// the station's one song already played (s3, last in the queue) makes no group.
		val playedFirst = queueListItems(
			ids(4), uids(4), currentIndex = 0, bound = station.copy(served = listOf("s3")), userQueued = setOf("s1"),
			playOrder = listOf(3, 0, 1, 2)
		)
		assertEquals(
			listOf(QueueGroup.Played, QueueGroup.Playing, QueueGroup.Next, QueueGroup.Next),
			playedFirst.filterIsInstance<Row>().map { it.group }
		)
	}

	@Test
	fun dragIsOffUnderShuffle() {
		assertTrue(queueReorderAllowed(3, null))
		assertTrue(queueReorderAllowed(3, listOf(0, 1, 2)))
		assertFalse(queueReorderAllowed(3, listOf(0, 2, 1)))
	}

	@Test
	fun remainingCountsTheRowsAsListed() {
		fun remaining(size: Int, current: Int, order: List<Int>?) =
			remainingQueueIndices(queueListItems(ids(size), uids(size), current, bound = null, userQueued = emptySet(), playOrder = order))
		// From the playing song on, in play order: the playing one and what comes after it.
		assertEquals(listOf(3, 1, 4, 2), remaining(5, 3, listOf(0, 3, 1, 4, 2)))
		assertEquals(listOf(2), remaining(5, 2, listOf(0, 3, 1, 4, 2)))
		// Queue order (no shuffle, another device, an order for another queue): today's count.
		assertEquals(listOf(3, 4), remaining(5, 3, null))
		assertEquals(listOf(3, 4), remaining(5, 3, listOf(1, 0)))
		assertEquals(emptyList(), remaining(0, 0, null))
	}

	// ---- Lane N review I1: an order a step ahead of the mirror keeps the listing on screen ------- //

	@Test
	fun orderAStepAheadKeepsTheListing() {
		val order = listOf(0, 3, 1, 4, 2)
		val shown = queueListItems(ids(5), uids(5), currentIndex = 0, bound = null, userQueued = emptySet(), playOrder = order)
		val lastShown = listedUids(shown)
		assertEquals(listOf(100L, 103L, 101L, 104L, 102L), lastShown)
		// An autoplay top-up: the player's order already holds six songs, the mirror still five. The
		// list stays as it is on screen (it never falls back to queue order for a frame).
		assertEquals(
			shown,
			queueListItems(ids(5), uids(5), currentIndex = 0, bound = null, userQueued = emptySet(), playOrder = listOf(0, 3, 5, 1, 4, 2), lastShown = lastShown)
		)
		// A swipe-remove of queue index 4: the order has four, the mirror five.
		assertEquals(
			shown,
			queueListItems(ids(5), uids(5), currentIndex = 0, bound = null, userQueued = emptySet(), playOrder = listOf(0, 3, 1, 2), lastShown = lastShown)
		)
		// Its Remaining holds with it.
		assertEquals(listOf(0, 3, 1, 4, 2), remainingQueueIndices(
			queueListItems(ids(5), uids(5), currentIndex = 0, bound = null, userQueued = emptySet(), playOrder = listOf(0, 3, 1, 2), lastShown = lastShown)
		))
		// The mirror catches up: the new order, rows keyed as before.
		val topUp = queueListItems(ids(6), uids(6), currentIndex = 0, bound = null, userQueued = emptySet(), playOrder = listOf(0, 3, 5, 1, 4, 2), lastShown = lastShown)
		assertEquals(listOf(0, 3, 5, 1, 4, 2), topUp.filterIsInstance<Row>().map { it.index })
		// The mirror moved first (the removed row gone, the order not yet republished): the rows still
		// here keep their places, by uid, with their queue indices in this mirror.
		val removed = queueListItems(
			listOf("s0", "s1", "s2", "s3"), listOf(100L, 101L, 102L, 103L), currentIndex = 0, bound = null, userQueued = emptySet(),
			playOrder = order, lastShown = lastShown
		).filterIsInstance<Row>()
		assertEquals(listOf(100L, 103L, 101L, 102L), removed.map { it.uid })
		assertEquals(listOf(0, 3, 1, 2), removed.map { it.index })
		// A row the listing never had goes after the rest until the order says where it plays.
		val added = queueListItems(ids(6), uids(6), currentIndex = 0, bound = null, userQueued = emptySet(), playOrder = order, lastShown = lastShown)
		assertEquals(listOf(0, 3, 1, 4, 2, 5), added.filterIsInstance<Row>().map { it.index })
		// With nothing on screen yet, or without shuffle, nothing is kept: queue order, as before.
		assertEquals(listOf(0, 1, 2, 3, 4), queueListItems(ids(5), uids(5), 0, null, emptySet(), playOrder = listOf(0, 3, 1, 2)).filterIsInstance<Row>().map { it.index })
		assertEquals(listOf(0, 1, 2, 3, 4), queueListItems(ids(5), uids(5), 0, null, emptySet(), playOrder = null, lastShown = lastShown).filterIsInstance<Row>().map { it.index })
	}

	@Test
	fun boundStationKeepsItsGroupsAStepAhead() {
		val order = listOf(0, 4, 1, 5, 2, 3)
		val shown = queueListItems(ids(6), uids(6), currentIndex = 0, bound = station, userQueued = setOf("s1", "s2"), playOrder = order)
		// A top-up's order arrives before the mirror's seventh row: the groups and their headers stay.
		assertEquals(
			shown,
			queueListItems(
				ids(6), uids(6), currentIndex = 0, bound = station, userQueued = setOf("s1", "s2"),
				playOrder = listOf(0, 4, 1, 6, 5, 2, 3), lastShown = listedUids(shown)
			)
		)
	}

	// ---- Lane N review Minor 1: one trust rule for the list, the peek row and the drag ---------- //

	@Test
	fun peekAndListReadOneTrustRule() {
		// Four songs, an order of the right size that holds the playing one but is not an order of this
		// queue (0 twice, 1 missing). The peek row and the list's first Up-next row must agree.
		val malformed = listOf(2, 0, 0, 3)
		val firstUpNext = queueListItems(ids(4), uids(4), currentIndex = 0, bound = null, userQueued = emptySet(), playOrder = malformed)
			.filterIsInstance<Row>().first { it.group == QueueGroup.Next }.index
		assertEquals(1, firstUpNext)
		assertEquals(firstUpNext, peekRow(ids(4), currentIndex = 0, order = malformed))
		assertEquals(listOf(1, 2), upcomingIndices(size = 4, currentIndex = 0, order = malformed, count = 2))
	}

	@Test
	fun reorderReadsTheTrustedOrder() {
		// Shuffle on and its order a step ahead of four rows: not draggable, whatever the order looks like.
		assertFalse(queueReorderAllowed(4, listOf(0, 1, 2)))
		assertFalse(queueReorderAllowed(4, listOf(0, 1, 1, 3)))
		// This queue's order, in queue order: draggable.
		assertTrue(queueReorderAllowed(4, listOf(0, 1, 2, 3)))
	}
}
