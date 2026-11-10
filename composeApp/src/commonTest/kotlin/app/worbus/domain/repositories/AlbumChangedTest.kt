package app.worbus.domain.repositories

import app.worbus.data.database.dao.AlbumFingerprint
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/*
 * Q-165: the changed-albums sweep (every Navidrome scan, every lb-bot landing) re-read an album only
 * when its track count or cover id moved. Navidrome's cover id suffix is a hash of the picture
 * (`_7d269e89dbc75c3a` for one picture on every album that carries it; measured 2026-10-31,
 * RESULTS-lbbot-embedded-cover-task1), so a tag edit that keeps the count and the art changed
 * nothing the sweep looked at, and Room kept the old title, year, genre or artist until the hourly
 * full pull.
 */
class AlbumChangedTest {
	private val room = AlbumFingerprint(
		albumId = "al-1", songCount = 10, coverArtId = "al-1_abc", name = "Jubilee", artistName = "Japanese Breakfast",
		artistId = "ar-1", year = 2021, genre = "Indie", duration = 2213.seconds
	)
	private val same = AlbumListingEntry(
		id = "al-1", songCount = 10, coverArtId = "al-1_abc", name = "Jubilee", artistName = "Japanese Breakfast",
		artistId = "ar-1", year = 2021, genre = "Indie", duration = 2213.seconds
	)

	@Test
	fun anUnchangedAlbumIsNotReRead() = assertFalse(albumChanged(room, same))

	@Test
	fun aNewAlbumIsRead() = assertTrue(albumChanged(null, same))

	@Test
	fun aRetitledAlbumIsReRead() = assertTrue(albumChanged(room, same.copy(name = "Jubilee (Deluxe)")))

	@Test
	fun aNewYearOrGenreIsReRead() {
		assertTrue(albumChanged(room, same.copy(year = 2022)), "year")
		assertTrue(albumChanged(room, same.copy(genre = "Pop")), "genre")
	}

	@Test
	fun aRecreditedAlbumIsReRead() {
		assertTrue(albumChanged(room, same.copy(artistName = "Japanese Breakfast & Friends")), "artist name")
		assertTrue(albumChanged(room, same.copy(artistId = "ar-2")), "artist id")
	}

	@Test
	fun aReplacedFileOfTheSameCountIsReRead() = assertTrue(albumChanged(room, same.copy(duration = 2219.seconds)))
}
