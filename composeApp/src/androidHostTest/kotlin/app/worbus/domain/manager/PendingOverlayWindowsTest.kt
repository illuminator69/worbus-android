package app.worbus.domain.manager

import app.worbus.data.database.entities.AlbumEntity
import app.worbus.data.database.entities.SyncActionType
import app.worbus.sourceFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/*
 * Review minor 6: rule 6 ("a pull never overwrites an unsent write") had two windows besides the
 * known one (a write SENT between a pull's read and its write):
 *  (i)  the star's queue insert was launched after the star had updated Room, so a batch reading
 *       the overlay in between did not see it;
 *  (ii) a star made between a batch's overlay read and its insert was written over by the server's
 *       value.
 * Both end with the server right and the local row wrong until that item is pulled again.
 */
class PendingOverlayWindowsTest {
	private class MemoryStore : WriteQueueStore {
		val rows = mutableListOf<QueuedWrite>()
		private var next = 1L
		override suspend fun pending() = rows.sortedBy { it.id }
		override suspend fun add(type: SyncActionType, itemId: String, time: Instant, supersedes: Set<SyncActionType>) {
			rows.removeAll { it.itemId == itemId && it.type in supersedes }
			rows += QueuedWrite(next++, type, itemId, time)
		}
		override suspend fun remove(id: Long) { rows.removeAll { it.id == id } }
		override suspend fun markRefused(id: Long, reason: String) {}
		override suspend fun clear() = rows.clear()
		override fun observe(): Flow<List<QueuedWrite>> = MutableStateFlow(rows.toList())
	}

	private val starredAt = Instant.fromEpochMilliseconds(1_700_000_000_000)

	@Test
	fun aStarMadeWhileABatchIsBeingWrittenIsNotWrittenOver() = runBlocking(Dispatchers.Default) {
		val q = WriteQueue(MemoryStore(), log = { _, _ -> })
		// Room's row for the album: null = not starred.
		var room: Instant? = null
		val overlayRead = CompletableDeferred<Unit>()
		val insertNow = CompletableDeferred<Unit>()

		// The pull: the server says "not starred"; the overlay is read, then the batch is inserted.
		val pull = async {
			q.withOverlay { overlay ->
				val serverSays = overlay.album(album("al-1"))
				overlayRead.complete(Unit)
				insertNow.await()
				room = serverSays.starredAt
			}
		}
		overlayRead.await()
		// The user stars the album, as a repository does: the queue first, then Room.
		val star = async(start = CoroutineStart.UNDISPATCHED) {
			q.enqueue(SyncActionType.STAR, "al-1", starredAt)
			room = starredAt
		}
		insertNow.complete(Unit)
		withTimeout(5_000) { pull.await(); star.await() }

		assertEquals(starredAt, room, "the star the user made is what Room holds")
	}

	private fun album(id: String) = AlbumEntity(
		albumId = id, name = id, artistName = "a", artistId = "ar-1", year = null, coverArtId = null,
		genre = null, genres = emptyList(), songCount = 1, duration = null, createdAt = starredAt,
		starredAt = null, lastPlayedAt = null, userRating = null, version = null,
		musicBrainzId = null, isExternal = false
	)

	@Test
	fun everyStarAndRatingIsQueuedBeforeRoomIsWritten() {
		val offenders = listOf("SongRepository", "AlbumRepository", "ArtistRepository").flatMap { name ->
			val text = sourceFile("src/commonMain/kotlin/app/worbus/domain/repositories/$name.kt").readText()
			Regex("""suspend fun (star|unstar|rate)\w*\([^)]*\)\s*\{""").findAll(text).mapNotNull { m ->
				val body = text.substring(m.range.last, text.indexOf("\n\t}\n", m.range.last))
				val queued = body.indexOf("syncManager.enqueue(")
				val roomWrite = Regex("""Dao\.(set|insert)""").find(body)?.range?.first ?: -1
				when {
					"enqueueAction(" in body -> "$name.${m.groupValues[1]}…: fire-and-forget enqueueAction"
					queued < 0 -> "$name.${m.groupValues[1]}…: not queued"
					roomWrite in 0 until queued -> "$name.${m.groupValues[1]}…: Room written before the queue"
					else -> null
				}
			}.toList()
		}
		assertEquals(emptyList(), offenders)
	}
}
