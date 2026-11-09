package app.worbus.shared

import app.worbus.domain.models.DomainExplicitStatus
import app.worbus.domain.models.DomainSong
import app.worbus.ui.core.PlayerUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/*
 * W2 (B-155, B-148, and through them B-282 and B-142): the playing song was taken from two
 * sources, the controller's index applied to the app's own queue list.
 *
 * B-155: media3 runs a MediaController's listeners INLINE inside `setMediaItems`, before
 * `playSongsLocal` / `playCollectionLocal` replace `_uiState.queue`, so `updatePlaybackState`
 * applies the NEW queue's start index to the OLD queue: the cover, the wash, the gain and the hub
 * report pass through `oldQueue[start]`, a song nothing is playing.
 *
 * B-148: while another device plays, the session's player is `RemoteSessionPlayer`, and the app's
 * own MediaController reads that facade. `updatePlaybackState` wrote the remote's index and
 * `isPaused = false` into the LOCAL state, and when the desktop dropped,
 * `HubManager.adoptIfNoLiveReceiver` read "local is playing" and re-claimed the session with the
 * phone's old queue and `play = true`.
 */
class PlaybackDerivationTest {
	private fun song(id: String) = DomainSong(
		id = id, title = "Song $id", artistName = "Artist", artistId = "ar", albumTitle = "Album",
		albumId = "al", parentId = null, comment = null, trackNumber = 1, discNumber = 1,
		isrc = emptyList(), year = null, genre = null, genres = emptyList(), moods = emptyList(),
		duration = 200.seconds, bpm = null, contributors = emptyList(), userRating = null,
		averageRating = null, bitRate = null, bitDepth = null, sampleRate = null,
		audioChannelCount = null, replayGain = null, fileSize = 0, fileExtension = null,
		mimeType = null, filePath = null, starredAt = null, coverArtId = null, musicBrainzId = null,
		explicitStatus = DomainExplicitStatus.Unknown, artists = emptyList(), albumArtists = emptyList()
	)

	private val old = listOf(song("o1"), song("o2"), song("o3"))
	private val playingOld = PlayerUiState(queue = old, currentIndex = 0, currentSong = old[0], isPaused = false)

	private fun view(index: Int, mediaId: String?, playing: Boolean = true, shuffle: Boolean = false, remote: Boolean = false) =
		ControllerView(index, mediaId, playWhenReady = playing, shuffle = shuffle, repeatMode = 0, showsRemote = remote)

	@Test
	fun aNewQueueSeenBeforeTheAppsQueueIsReplacedDoesNotNameASongOfTheOldOne() {
		// `setMediaItems(newItems, 2, 0)` has run: the controller is on the new queue's third song
		// ("n3"); `_uiState.queue` is still the old one.
		val derived = derivePlayback(playingOld, view(index = 2, mediaId = "n3"))

		assertEquals("o1", derived.currentSong?.id, "the old queue's third song is not playing; nothing changed yet")
		assertEquals(0, derived.currentIndex)
	}

	@Test
	fun aSongTheQueueHoldsAtTheControllersIndexIsThePlayingSong() {
		val queue = listOf(song("a"), song("b"), song("a"))
		val derived = derivePlayback(PlayerUiState(queue = queue, currentIndex = 0, currentSong = queue[0]), view(index = 2, mediaId = "a"))

		assertEquals(2, derived.currentIndex, "a repeated song is told apart by its index")
		assertEquals("a", derived.currentSong?.id)
	}

	@Test
	fun thePlayStateIsTheControllersEvenWhileTheQueuesDisagree() {
		val derived = derivePlayback(playingOld, view(index = 2, mediaId = "n3", playing = false, shuffle = true))

		assertTrue(derived.isPaused)
		assertTrue(derived.isShuffleEnabled)
	}

	@Test
	fun theRemoteFacadesStateIsNeverWrittenIntoTheLocalPlayer() {
		val local = PlayerUiState(queue = old, currentIndex = 0, currentSong = old[0], isPaused = true, isShuffleEnabled = true)

		// Another device plays the session's song 2 of something; the facade says "playing".
		val derived = derivePlayback(local, view(index = 1, mediaId = "o2", playing = true, shuffle = false, remote = true))

		assertEquals(0, derived.currentIndex, "the remote's index is not the local player's")
		assertEquals("o1", derived.currentSong?.id)
		assertTrue(derived.isPaused, "the local player is paused while another device plays")
		assertTrue(derived.isShuffleEnabled, "the remote's shuffle is not the local player's")
	}

	@Test
	fun aLocalPlayerThatWasPlayingReadsPausedOnceTheFacadeIsShown() {
		// The service pauses the local ExoPlayer before it swaps the facade in; the controller may
		// see the facade's state before (or instead of) that pause.
		val derived = derivePlayback(playingOld, view(index = 2, mediaId = "o3", playing = true, remote = true))

		assertTrue(derived.isPaused, "adoptIfNoLiveReceiver must not read the remote's play state as local playing")
		assertEquals(0, derived.currentIndex)
	}
}
