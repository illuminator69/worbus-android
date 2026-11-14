package app.worbus.shared

import app.worbus.data.database.dao.SongCoverId
import app.worbus.domain.models.DomainExplicitStatus
import app.worbus.domain.models.DomainSong
import app.worbus.domain.repositories.chunkedCoverIds
import app.worbus.domain.repositories.withCurrentCovers
import app.worbus.ui.core.PlayerUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/*
 * B-437 follow-ups.
 * - Review N1: the queue's cover ids follow Room through one flow, and one exception (a locked database, the IN
 *   query's variable limit on a long queue) ended it for the rest of the process: covers stopped following, silently.
 *   It reads Room again after a back-off, and the IN query is split.
 * - The notification and the lock screen draw the player's own items, built with the cover id the song had when it
 *   was queued; the queue's state followed Room, they did not until the next restore. What changed is handed to the
 *   player ([coverChanges]), so it can re-point those items' artwork.
 */
class QueueCoverFollowTest {
	private fun song(id: String, cover: String?) = DomainSong(
		id = id, title = "Song $id", artistName = "Artist", artistId = "ar", albumTitle = "Album",
		albumId = "al", parentId = null, comment = null, trackNumber = 1, discNumber = 1,
		isrc = emptyList(), year = null, genre = null, genres = emptyList(), moods = emptyList(),
		duration = 200.seconds, bpm = null, contributors = emptyList(), userRating = null,
		averageRating = null, bitRate = null, bitDepth = null, sampleRate = null,
		audioChannelCount = null, replayGain = null, fileSize = 0, fileExtension = null,
		mimeType = null, filePath = null, starredAt = null, coverArtId = cover, musicBrainzId = null,
		explicitStatus = DomainExplicitStatus.Unknown, artists = emptyList(), albumArtists = emptyList()
	)

	@Test
	fun n1_oneFailureDoesNotStopTheCoversFollowingRoom() = runBlocking {
		val queue = listOf(song("1", "al-a_old"))
		var subscriptions = 0
		val observe: (List<String>) -> kotlinx.coroutines.flow.Flow<Map<String, String?>> = { _ ->
			flow {
				subscriptions++
				if (subscriptions == 1) error("database is locked")
				emit(mapOf("1" to "al-a_new"))
			}
		}
		val seen = withTimeout(5_000) {
			queueCoverFlow(MutableStateFlow(queue), observe, retryAfter = { Duration.ZERO }).first()
		}
		assertEquals(mapOf("1" to "al-a_new"), seen, "read again after the failure")
		assertEquals(2, subscriptions)
	}

	@Test
	fun n1_aLongQueueIsReadInChunks() = runBlocking {
		val ids = (1..2_000).map { "s$it" }
		val asked = mutableListOf<Int>()
		val covers = chunkedCoverIds(ids, 900) { chunk ->
			asked += chunk.size
			flowOf(chunk.map { SongCoverId(it, "c-$it") })
		}.first()
		assertTrue(asked.all { it <= 900 }, "no query over the bound: $asked")
		assertEquals(2_000, covers.size)
		assertEquals("c-s1999", covers["s1999"])
	}

	@Test
	fun theSongsWhoseCoverChangedAreHandedToThePlayer() {
		val queue = listOf(song("1", "al-a_old"), song("2", "al-a_old"), song("3", "al-b_1"))
		val state = PlayerUiState(queue = queue, currentSong = queue[0], currentIndex = 0)
		val fresh = assertNotNull(withCurrentCovers(state, mapOf("1" to "al-a_new", "2" to "al-a_new", "3" to "al-b_1")))
		assertEquals(mapOf("1" to "al-a_new", "2" to "al-a_new"), coverChanges(state.queue, fresh.queue))
		assertEquals(emptyMap(), coverChanges(state.queue, state.queue), "nothing changed, nothing handed on")
	}

	@Test
	fun theRetryBacksOff() {
		assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 32L, 60L, 60L), (0L..7L).map { coverFollowRetryAfter(it).inWholeSeconds })
	}
}
