package app.worbus.domain.manager

import app.worbus.data.database.entities.DownloadEntity
import app.worbus.data.database.entities.DownloadOriginEntity
import app.worbus.data.database.entities.DownloadStatus
import app.worbus.data.database.entities.SongEntity
import app.worbus.domain.models.DomainExplicitStatus
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/*
 * Q-165, the owner's ruling (2026-11-12): a downloaded song that changed on the server is fetched
 * again (the old copy playing meanwhile); a song gone from the server has its copy removed and its
 * record dropped; a Navidrome answering "not found" for many songs at once (a rescan, the wrong
 * server, an unmounted library) wipes nothing; offline nothing happens.
 *
 * Fakes stand in for Room and the server: the rules are DownloadUpkeep's, and these hold them.
 */
class DownloadUpkeepTest {
	private fun song(id: String, title: String = "Song $id", size: Long = 5_000_000L, suffix: String = "flac") = SongEntity(
		songId = id, title = title, artistName = "Artist", artistId = "ar-1", albumTitle = "Album",
		belongsToAlbumId = "al-1", parentId = null, comment = null, trackNumber = 1, discNumber = 1,
		isrc = emptyList(), year = 2001, genre = "Rock", genres = emptyList(), moods = emptyList(),
		duration = 200.seconds, bpm = null, contributors = emptyList(), playCount = 3, userRating = null,
		averageRating = null, bitRate = 900, bitDepth = 16, sampleRate = 44100, audioChannelCount = 2,
		replayGain = null, fileSize = size, fileExtension = suffix, mimeType = "audio/flac",
		filePath = "Artist/Album/01.$suffix", starredAt = null, coverArtId = "al-1", musicBrainzId = "mb-$id",
		explicitStatus = DomainExplicitStatus.Unknown, artists = emptyList(), albumArtists = emptyList(),
		isExternal = false
	)

	private fun row(id: String) = DownloadEntity(
		songId = id, status = DownloadStatus.DOWNLOADED, progress = 1f, filePath = "/d/$id.flac", fileSize = 5_000_000L
	)

	private inner class World(val ids: List<String>) {
		var clock = 1_000_000_000L
		var online = true
		val rows = ids.associateWith { row(it) }.toMutableMap()
		val room = ids.associateWith { song(it) }.toMutableMap()
		val origins = mutableMapOf<String, DownloadOriginEntity>()
		val answers = mutableMapOf<String, SongAnswer>()
		val asked = mutableListOf<String>()
		val refetched = mutableListOf<String>()
		val removed = mutableListOf<String>()
		val store = object : DownloadOriginStore {
			override suspend fun all() = origins.values.toList()
			override suspend fun put(rows: List<DownloadOriginEntity>) { rows.forEach { origins[it.songId] = it } }
			override suspend fun remove(songIds: List<String>) { songIds.forEach { origins.remove(it) } }
		}
		val downloads = object : UpkeepDownloads {
			override suspend fun downloaded() = rows.values.toList()
			override fun refetch(song: SongEntity, row: DownloadEntity) { refetched += song.songId }
			override suspend fun remove(songId: String) { removed += songId; rows.remove(songId) }
		}
		val upkeep = DownloadUpkeep(
			store = store,
			downloads = downloads,
			songsById = { ids -> ids.mapNotNull { room[it] } },
			askSong = { id -> asked += id; answers[id] ?: SongAnswer.PRESENT },
			isOnline = { online },
			now = { clock }
		)

		/** Each download as it was fetched: its origin is the song as Room has it now. */
		fun recordAll() = ids.forEach { origins[it] = DownloadOriginEntity(it, songOrigin(room.getValue(it)), clock) }

		fun pass(listed: Set<String>?) = runBlocking { upkeep.afterPull(PullSeen(listed)) }
	}

	@Test
	fun aChangedSongIsFetchedAgain() {
		val w = World(listOf("s1", "s2", "s3"))
		w.recordAll()
		w.room["s1"] = w.room.getValue("s1").copy(title = "Song s1 (retagged)")
		w.room["s2"] = w.room.getValue("s2").copy(fileSize = 4_100_000L, fileExtension = "opus")
		w.pass(listed = null)
		assertEquals(listOf("s1", "s2"), w.refetched.sorted(), "a retag and a replaced file are fetched again; the third is not")
		assertEquals(emptyList(), w.removed)
	}

	@Test
	fun aChangeOnlyInPlaysStarsOrRatingIsNotAChange() {
		val w = World(listOf("s1"))
		w.recordAll()
		w.room["s1"] = w.room.getValue("s1").copy(playCount = 99, userRating = 5, starredAt = kotlin.time.Instant.fromEpochMilliseconds(1))
		w.pass(listed = null)
		assertEquals(emptyList(), w.refetched, "the file did not change")
	}

	@Test
	fun aDownloadWithNoRecordIsRecordedNotFetched() {
		val w = World(listOf("s1"))
		w.pass(listed = null)
		assertEquals(emptyList(), w.refetched, "a file from before this build is taken as it is")
		assertEquals(songOrigin(w.room.getValue("s1")), w.origins["s1"]?.origin, "and what it is now is recorded")
		assertTrue(w.origins.getValue("s1").origin.isNotBlank())
	}

	@Test
	fun aSongGoneTwiceADayApartIsRemoved() {
		val w = World(listOf("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8"))
		w.recordAll()
		val listed = setOf("s2", "s3", "s4", "s5", "s6", "s7", "s8")
		w.answers["s1"] = SongAnswer.GONE
		w.pass(listed)
		assertEquals(listOf("s1"), w.asked, "only a song the complete pull did not list is asked about")
		assertEquals(emptyList(), w.removed, "one word is not enough")
		assertTrue(w.origins.getValue("s1").goneSeenAt > 0L, "it is marked")
		w.clock += 2.hours.inWholeMilliseconds
		w.pass(listed)
		assertEquals(emptyList(), w.removed, "two hours later is not a day")
		w.clock += 23.hours.inWholeMilliseconds
		w.pass(listed)
		assertEquals(listOf("s1"), w.removed, "gone on two pulls a day apart: the copy and the row go")
		assertTrue("s1" !in w.origins, "and the record")
	}

	@Test
	fun aSongSeenAgainStartsOver() {
		val w = World(listOf("s1", "s2", "s3", "s4"))
		w.recordAll()
		w.answers["s1"] = SongAnswer.GONE
		w.pass(setOf("s2", "s3", "s4"))
		w.clock += 25.hours.inWholeMilliseconds
		w.pass(setOf("s1", "s2", "s3", "s4"))
		assertEquals(0L, w.origins.getValue("s1").goneSeenAt, "listed again: the mark is cleared")
		w.clock += 1.hours.inWholeMilliseconds
		w.pass(setOf("s2", "s3", "s4"))
		assertEquals(emptyList(), w.removed, "a new first word, not a second")
	}

	@Test
	fun aNotFoundStormRemovesNothing() {
		// The library unmounted, a rescan, the wrong server: Navidrome lists none of 30 and says 70 for each.
		val ids = (1..30).map { "s$it" }
		val w = World(ids)
		w.recordAll()
		ids.forEach { w.answers[it] = SongAnswer.GONE }
		repeat(3) {
			w.pass(listed = emptySet())
			w.clock += 25.hours.inWholeMilliseconds
		}
		assertEquals(emptyList(), w.removed, "nothing is removed")
		assertTrue(w.origins.values.all { it.goneSeenAt == 0L }, "nor marked")
		assertEquals(emptyList(), w.asked, "and Navidrome is not asked song by song")
		assertTrue(w.pass(listed = emptySet()).goneHeld.isNotBlank(), "the pass says it held back")
	}

	@Test
	fun mostOfASmallSetGoneAtOnceRemovesNothing() {
		val w = World(listOf("s1", "s2", "s3", "s4", "s5", "s6"))
		w.recordAll()
		listOf("s1", "s2", "s3", "s4").forEach { w.answers[it] = SongAnswer.GONE }
		repeat(2) { w.pass(setOf("s5", "s6")); w.clock += 25.hours.inWholeMilliseconds }
		assertEquals(emptyList(), w.removed, "four of six at once is a storm too")
	}

	@Test
	fun aTransientNotFoundRemovesNothing() {
		// A 404 from a proxy, a 503, a timeout: not Navidrome's word that the song is gone.
		val w = World(listOf("s1", "s2", "s3", "s4"))
		w.recordAll()
		w.answers["s1"] = SongAnswer.NO_ANSWER
		w.answers["s2"] = SongAnswer.GONE
		repeat(3) { w.pass(setOf("s3", "s4")); w.clock += 25.hours.inWholeMilliseconds }
		assertEquals(emptyList(), w.removed, "nothing is removed")
		assertEquals(0L, w.origins.getValue("s1").goneSeenAt, "and no answer marks nothing")
	}

	@Test
	fun aPartialPullJudgesNothingGone() {
		val w = World(listOf("s1"))
		w.recordAll()
		w.room.remove("s1")
		w.answers["s1"] = SongAnswer.GONE
		repeat(3) { w.pass(listed = null); w.clock += 25.hours.inWholeMilliseconds }
		assertEquals(emptyList(), w.asked, "only a complete pull's list can say a song is missing")
		assertEquals(emptyList(), w.removed)
	}

	@Test
	fun offlineNothingHappens() {
		val w = World(listOf("s1", "s2", "s3", "s4"))
		w.recordAll()
		w.room["s1"] = w.room.getValue("s1").copy(title = "changed")
		w.answers["s2"] = SongAnswer.GONE
		w.online = false
		val before = w.origins.toMap()
		repeat(3) { w.pass(setOf("s3", "s4")); w.clock += 25.hours.inWholeMilliseconds }
		assertEquals(emptyList(), w.refetched)
		assertEquals(emptyList(), w.asked)
		assertEquals(emptyList(), w.removed)
		assertEquals(before, w.origins.toMap(), "nothing written")
		assertEquals("offline", w.pass(setOf("s3", "s4")).skipped)
	}

	@Test
	fun aDeletedDownloadsRecordIsDropped() {
		val w = World(listOf("s1", "s2"))
		w.recordAll()
		w.rows.remove("s2")
		w.pass(listed = null)
		assertTrue("s2" !in w.origins, "a record with no download left is dropped")
	}

	@Test
	fun theOriginIsTheFileNotTheUsersMarks() {
		val a = song("s1")
		assertEquals(songOrigin(a), songOrigin(a.copy(playCount = 7, userRating = 2, averageRating = 3f)))
		for (changed in listOf(
			a.copy(title = "x"), a.copy(artistName = "x"), a.copy(albumTitle = "x"), a.copy(trackNumber = 2),
			a.copy(discNumber = 2), a.copy(year = 1999), a.copy(fileSize = 1L), a.copy(fileExtension = "mp3"),
			a.copy(bitRate = 320), a.copy(duration = 201.seconds), a.copy(musicBrainzId = "other")
		)) assertTrue(songOrigin(changed) != songOrigin(a), "changed: $changed")
	}
}
