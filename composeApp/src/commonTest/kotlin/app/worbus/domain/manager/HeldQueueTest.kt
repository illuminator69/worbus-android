package app.worbus.domain.manager

import app.worbus.domain.models.DomainExplicitStatus
import app.worbus.domain.models.DomainSong
import app.worbus.domain.repositories.withCurrentCovers
import app.worbus.ui.core.PlayerUiState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/*
 * Stage 2 review M2 (B-437 follow-up): after a load that failed and was not a transfer's, the reporter holds its
 * tongue while the player is paused over the queue it was left with ([HeldQueue]). B-437 makes the queue follow
 * Room's cover ids, which rewrites the list (same songs, a new list object). The hold compared the list by
 * reference, so a cover refresh ended it and the next tick reported the old queue's index and position as the
 * session's. What the hold is about is which songs are queued, not which list object holds them.
 */
class HeldQueueTest {
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

	private val queue = listOf(song("1", "al-a_old"), song("2", "al-a_old"), song("3", "al-b_1"))
	private val paused = PlayerUiState(queue = queue, currentSong = queue[1], currentIndex = 1, isPaused = true)
	private val held = HeldQueue.of(queue)

	@Test
	fun aCoverRefreshOfTheHeldQueueKeepsTheHold() {
		val refreshed = assertNotNull(withCurrentCovers(paused, mapOf("1" to "al-a_new", "2" to "al-a_new")))
		assertTrue(held.stillShownBy(refreshed), "the same songs, paused: still the queue the hub does not have")
	}

	@Test
	fun theHeldQueueItselfKeepsTheHold() {
		assertTrue(held.stillShownBy(paused))
	}

	@Test
	fun playingOrAnotherQueueEndsTheHold() {
		assertFalse(held.stillShownBy(paused.copy(isPaused = false)), "playing publishes this queue")
		assertFalse(held.stillShownBy(paused.copy(queue = queue.dropLast(1))), "a song fewer is another queue")
		assertFalse(held.stillShownBy(paused.copy(queue = queue.reversed())), "the same songs in another order too")
		assertFalse(held.stillShownBy(paused.copy(queue = queue + song("4", null))), "and a song more")
	}
}
