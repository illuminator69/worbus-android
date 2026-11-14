package app.worbus.domain.repositories

import app.worbus.domain.models.DomainExplicitStatus
import app.worbus.domain.models.DomainSong
import app.worbus.ui.core.PlayerUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/*
 * B-437 (the owner, 2026-11-14): the queue's songs' cover ids follow Room. A queued song kept the cover id it
 * was queued with; Navidrome versions that id and answers it `immutable`, so a cover changed outside the app
 * stayed old in the queue, the player and the persisted queue, across restarts.
 */
class QueueCoverRefreshTest {
	private fun song(id: String, cover: String?, external: Boolean = false) = DomainSong(
		id = id, title = "Song $id", artistName = "Artist", artistId = "ar", albumTitle = "Album",
		albumId = "al", parentId = null, comment = null, trackNumber = 1, discNumber = 1,
		isrc = emptyList(), year = null, genre = null, genres = emptyList(), moods = emptyList(),
		duration = 200.seconds, bpm = null, contributors = emptyList(), userRating = null,
		averageRating = null, bitRate = null, bitDepth = null, sampleRate = null,
		audioChannelCount = null, replayGain = null, fileSize = 0, fileExtension = null,
		mimeType = null, filePath = null, starredAt = null, coverArtId = cover, musicBrainzId = null,
		explicitStatus = DomainExplicitStatus.Unknown, artists = emptyList(), albumArtists = emptyList(),
		isExternal = external
	)

	private val queue = listOf(song("1", "al-a_old"), song("2", "al-a_old"), song("ext:3", "x", external = true), song("4", "al-b_1"))
	private val state = PlayerUiState(queue = queue, currentSong = queue[1], currentIndex = 1)

	@Test
	fun aCoverRoomHoldsNewerReplacesTheQueuedOneInTheQueueAndTheCurrentSong() {
		val fresh = assertNotNull(withCurrentCovers(state, mapOf("1" to "al-a_new", "2" to "al-a_new", "4" to "al-b_1")))
		assertEquals(listOf("al-a_new", "al-a_new", "x", "al-b_1"), fresh.queue.map { it.coverArtId })
		assertEquals("al-a_new", fresh.currentSong?.coverArtId)
		assertEquals(1, fresh.currentIndex, "nothing else moves")
		assertEquals(queue.map { it.id }, fresh.queue.map { it.id })
	}

	@Test
	fun nothingNewerIsNoChange() {
		assertNull(withCurrentCovers(state, mapOf("1" to "al-a_old", "2" to "al-a_old", "4" to "al-b_1")))
		assertNull(withCurrentCovers(state, emptyMap()), "songs Room does not hold keep theirs")
	}

	@Test
	fun aSongRoomDoesNotHoldOrAPreviewKeepsItsCover() {
		val fresh = assertNotNull(withCurrentCovers(state, mapOf("ext:3" to "y", "4" to "al-b_2")))
		assertEquals(listOf("al-a_old", "al-a_old", "x", "al-b_2"), fresh.queue.map { it.coverArtId })
	}

	@Test
	fun aCoverRemovedOnTheServerIsRemovedHere() {
		val fresh = assertNotNull(withCurrentCovers(state, mapOf("4" to null)))
		assertNull(fresh.queue[3].coverArtId)
	}
}
