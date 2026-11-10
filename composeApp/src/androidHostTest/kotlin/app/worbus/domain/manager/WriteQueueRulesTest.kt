package app.worbus.domain.manager

import app.worbus.data.database.entities.SyncActionType
import app.worbus.data.database.entities.SyncActionType.SCROBBLE
import app.worbus.data.database.entities.SyncActionType.STAR
import app.worbus.data.database.entities.SyncActionType.STAR_3
import app.worbus.data.database.entities.SyncActionType.STAR_5
import app.worbus.data.database.entities.SyncActionType.UNSTAR
import dev.zt64.subsonic.api.model.SubsonicErrorCode
import dev.zt64.subsonic.api.model.SubsonicException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/*
 * B-373: the write-queue rules (RESULTS-phase-b-android-2026-11-10.md, "The write-queue rules").
 * These drive WriteQueue through a store that keeps rows the way the Room table does: ids grow,
 * rows come back oldest first, a remove of an absent id does nothing.
 */
class WriteQueueRulesTest {
	/** Stands in for a host's "nothing answered" (`java.net.ConnectException`; named by simple name). */
	private class ConnectException(message: String) : Exception(message)

	/** As `PendingWriteDao`: ids grow, oldest first, `replace` deletes the superseded rows then inserts. */
	private class MemoryStore : WriteQueueStore {
		val rows = mutableListOf<QueuedWrite>()
		private var next = 1L

		override suspend fun pending(): List<QueuedWrite> = rows.sortedBy { it.id }
		override suspend fun add(type: SyncActionType, itemId: String, time: Instant, supersedes: Set<SyncActionType>) {
			rows.removeAll { it.itemId == itemId && it.type in supersedes }
			rows += QueuedWrite(next++, type, itemId, time)
		}

		override suspend fun remove(id: Long) {
			rows.removeAll { it.id == id }
		}

		override suspend fun markRefused(id: Long, reason: String) {
			rows.replaceAll { if (it.id == id) it.copy(refusals = it.refusals + 1, lastError = reason) else it }
		}

		override suspend fun clear() = rows.clear()
		override fun observe(): Flow<List<QueuedWrite>> = flowOf(rows.toList())
	}

	private val t1 = Instant.fromEpochSeconds(1_700_000_000)
	private val t2 = Instant.fromEpochSeconds(1_700_000_300)

	private fun queue(store: MemoryStore) = WriteQueue(store, log = { _, _ -> })

	@Test
	fun aStarThenAnUnstarOfOneItemLeaveOnlyTheUnstar() = runBlocking {
		val store = MemoryStore()
		val q = queue(store)
		q.enqueue(STAR, "al-1", t1)
		q.enqueue(UNSTAR, "al-1", t2)
		assertEquals(listOf(UNSTAR to "al-1"), store.rows.map { it.type to it.itemId })
	}

	@Test
	fun aLaterRatingOfOneItemReplacesTheEarlierOne() = runBlocking {
		val store = MemoryStore()
		val q = queue(store)
		q.enqueue(STAR_3, "s-1", t1)
		q.enqueue(STAR, "s-2", t1)
		q.enqueue(STAR_5, "s-1", t2)
		assertEquals(listOf(STAR to "s-2", STAR_5 to "s-1"), store.rows.map { it.type to it.itemId })
	}

	@Test
	fun scrobblesAreNeverMergedAndKeepTheirTimes() = runBlocking {
		val store = MemoryStore()
		val q = queue(store)
		q.enqueue(SCROBBLE, "s-1", t1)
		q.enqueue(SCROBBLE, "s-1", t2)
		val sent = mutableListOf<Instant>()
		q.drain { sent += it.time }
		assertEquals(listOf(t1, t2), sent)
	}

	@Test
	fun aWriteTheServerRefusesDoesNotHoldBackTheOthers() = runBlocking {
		val store = MemoryStore()
		val q = queue(store)
		q.enqueue(STAR, "s-1", t1)
		q.enqueue(STAR, "s-2", t1)
		val sent = mutableListOf<String>()
		q.drain { w ->
			if (w.itemId == "s-1") throw SubsonicException("refused", SubsonicErrorCode.GENERIC)
			sent += w.itemId
		}
		assertEquals(listOf("s-2"), sent, "the refusal of s-1 does not stop s-2")
		assertEquals(listOf("s-1"), store.rows.map { it.itemId }, "the refused write stays to be tried again")
	}

	@Test
	fun aWriteForAnItemTheServerNoLongerHasLeavesTheQueue() = runBlocking {
		val store = MemoryStore()
		val q = queue(store)
		q.enqueue(STAR, "gone", t1)
		q.enqueue(STAR, "s-2", t1)
		val sent = mutableListOf<String>()
		q.drain { w ->
			if (w.itemId == "gone") throw SubsonicException("not found", SubsonicErrorCode.DATA_NOT_FOUND)
			sent += w.itemId
		}
		assertEquals(listOf("s-2"), sent)
		assertEquals(emptyList(), store.rows, "a write on an item the server does not have is moot")
	}

	@Test
	fun nothingAnsweringEndsThePassAndKeepsEverything() = runBlocking {
		val store = MemoryStore()
		val q = queue(store)
		q.enqueue(STAR, "s-1", t1)
		q.enqueue(SCROBBLE, "s-2", t1)
		var calls = 0
		q.drain { calls++; throw ConnectException("Failed to connect") }
		assertEquals(1, calls, "every other write would meet the same closed door")
		assertEquals(listOf("s-1", "s-2"), store.rows.map { it.itemId })
	}

	@Test
	fun writesGoOutInTheOrderTheyWereMade() = runBlocking {
		val store = MemoryStore()
		val q = queue(store)
		q.enqueue(SCROBBLE, "a", t1)
		q.enqueue(STAR, "b", t1)
		q.enqueue(SCROBBLE, "c", t2)
		val sent = mutableListOf<String>()
		q.drain { sent += it.itemId }
		assertEquals(listOf("a", "b", "c"), sent)
		assertEquals(emptyList(), store.rows)
	}

	@Test
	fun aRefusedWriteIsKeptWithWhatTheServerSaid() = runBlocking {
		val store = MemoryStore()
		val q = queue(store)
		q.enqueue(SyncActionType.DELETE_PLAYLIST, "pl-1", t1)
		q.drain { throw SubsonicException("not yours", SubsonicErrorCode.UNAUTHORIZED) }
		val row = store.rows.single()
		assertEquals(1, row.refusals)
		assertTrue(row.lastError.orEmpty().contains("not yours"), "the server's words are kept: ${row.lastError}")
	}

	@Test
	fun aRefusedSessionEndsThePass() = runBlocking {
		val store = MemoryStore()
		val q = queue(store)
		q.enqueue(STAR, "s-1", t1)
		q.enqueue(STAR, "s-2", t1)
		var calls = 0
		q.drain { calls++; throw SubsonicException("wrong password", SubsonicErrorCode.WRONG_USERNAME_OR_PASSWORD) }
		assertEquals(1, calls, "a refused session refuses every write")
		assertEquals(listOf(0, 0), store.rows.map { it.refusals }, "nothing is counted against the writes")
	}

	@Test
	fun aWriteReplacedWhileItIsBeingSentGoesOutNextPass() = runBlocking {
		val store = MemoryStore()
		val q = queue(store)
		q.enqueue(STAR, "s-1", t1)
		val sent = mutableListOf<SyncActionType>()
		q.drain { w ->
			// The user unstars while the star is on the wire.
			q.enqueue(UNSTAR, "s-1", t2)
			sent += w.type
		}
		assertEquals(listOf(UNSTAR), store.rows.map { it.type }, "the unstar is still waiting")
		q.drain { sent += it.type }
		assertEquals(listOf(STAR, UNSTAR), sent, "the item ends unstarred")
		assertEquals(emptyList(), store.rows)
	}
}

