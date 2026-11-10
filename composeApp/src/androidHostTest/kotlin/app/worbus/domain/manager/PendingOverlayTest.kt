package app.worbus.domain.manager

import app.worbus.data.database.entities.AlbumEntity
import app.worbus.data.database.entities.ArtistEntity
import app.worbus.data.database.entities.SyncActionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/*
 * B-373 rule 6: a pull writes the server's rows over Room's (an upsert), and the server has not
 * had the pending writes, so its answer would undo them. Every pull applies this overlay to the
 * rows it writes (DbRepository's four write sites, CollectionRepository's refresh).
 */
class PendingOverlayTest {
	private val t1 = Instant.fromEpochSeconds(1_700_000_000)
	private val server = Instant.fromEpochSeconds(1_600_000_000)

	private fun album(id: String, starredAt: Instant?, rating: Int?) = AlbumEntity(
		albumId = id, name = id, artistName = "a", artistId = "ar-1", year = null, coverArtId = null,
		genre = null, genres = emptyList(), songCount = 1, duration = null, createdAt = server,
		starredAt = starredAt, lastPlayedAt = null, userRating = rating, version = null,
		musicBrainzId = null, isExternal = false
	)

	private fun write(id: Long, type: SyncActionType, item: String) = QueuedWrite(id, type, item, t1)

	@Test
	fun anUnsentStarSurvivesAPullThatSaysUnstarred() {
		val overlay = PendingOverlay(listOf(write(1, SyncActionType.STAR, "al-1")))
		assertEquals(t1, overlay.album(album("al-1", starredAt = null, rating = null)).starredAt)
	}

	@Test
	fun anUnsentUnstarSurvivesAPullThatSaysStarred() {
		val overlay = PendingOverlay(listOf(write(1, SyncActionType.UNSTAR, "al-1")))
		assertNull(overlay.album(album("al-1", starredAt = server, rating = null)).starredAt)
	}

	@Test
	fun theLastStarFamilyWriteDecides() {
		val overlay = PendingOverlay(listOf(write(2, SyncActionType.UNSTAR, "al-1"), write(1, SyncActionType.STAR, "al-1")))
		assertNull(overlay.album(album("al-1", starredAt = server, rating = null)).starredAt, "by queue order, not list order")
	}

	@Test
	fun anUnsentRatingSurvivesAndOtherItemsKeepTheServersAnswer() {
		val overlay = PendingOverlay(listOf(write(1, SyncActionType.STAR_4, "al-1")))
		assertEquals(4, overlay.album(album("al-1", starredAt = null, rating = 2)).userRating)
		val other = album("al-2", starredAt = server, rating = 3)
		assertEquals(other, overlay.album(other))
	}

	@Test
	fun anArtistRowIsLookedUpByTheSameId() {
		val overlay = PendingOverlay(listOf(write(1, SyncActionType.STAR, "ar-9")))
		assertEquals(t1, overlay.artist(ArtistEntity(artistId = "ar-9", name = "n", albumCount = 0)).starredAt)
	}

	@Test
	fun aScrobbleOrADeleteChangesNoRow() {
		val overlay = PendingOverlay(listOf(write(1, SyncActionType.SCROBBLE, "al-1"), write(2, SyncActionType.DELETE_PLAYLIST, "al-1")))
		val row = album("al-1", starredAt = server, rating = 1)
		assertEquals(row, overlay.album(row))
	}
}
