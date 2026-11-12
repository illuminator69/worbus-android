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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/*
 * Q-165, the owner's ruling (2026-11-12): a downloaded song that changed on the server is fetched
 * again (the old copy playing meanwhile); a song gone from the server has its copy removed and its
 * record dropped; a Navidrome answering "not found" for many songs at once wipes nothing; offline
 * nothing happens.
 *
 * The follow-up (REVIEW-phase-b-android-ownerbuild-2026-11-12, the owner's principle: a copy goes only
 * when its track is truly gone): a re-keyed song's download is carried to its new id, another server's
 * or user's downloads are never judged, a missing share (the library shrinking) judges nothing, the
 * confirmation is three complete pulls over 72 h of the upkeep's own clock, a playing or queued song is
 * never swapped, and re-fetches are capped.
 *
 * Fakes stand in for Room and the server: the rules are DownloadUpkeep's, and these hold them.
 */
class DownloadUpkeepTest {
	private val ownerA = DownloadOwner.of("http://nas.lan:4533/", "alice")
	private val ownerB = DownloadOwner.of("https://music.example.com", "bob")

	private fun song(
		id: String,
		title: String = "Song $id",
		size: Long = 5_000_000L,
		suffix: String = "flac",
		track: Int = 1,
		disc: Int = 1,
		album: String = "Album $id",
		seconds: Int = 200,
		artist: String = "Artist"
	) = SongEntity(
		songId = id, title = title, artistName = artist, artistId = "ar-1", albumTitle = album,
		belongsToAlbumId = "al-$album", parentId = null, comment = null, trackNumber = track, discNumber = disc,
		isrc = emptyList(), year = 2001, genre = "Rock", genres = emptyList(), moods = emptyList(),
		duration = seconds.seconds, bpm = null, contributors = emptyList(), playCount = 3, userRating = null,
		averageRating = null, bitRate = 900, bitDepth = 16, sampleRate = 44100, audioChannelCount = 2,
		replayGain = null, fileSize = size, fileExtension = suffix, mimeType = "audio/flac",
		filePath = "Artist/Album/01.$suffix", starredAt = null, coverArtId = "al-1", musicBrainzId = "mb-$id",
		explicitStatus = DomainExplicitStatus.Unknown, artists = emptyList(), albumArtists = emptyList(),
		isExternal = false
	)

	private fun row(id: String) = DownloadEntity(
		songId = id, status = DownloadStatus.DOWNLOADED, progress = 1f, filePath = "/d/$id.flac", fileSize = 5_000_000L
	)

	private inner class World(ids: List<String>, songs: List<SongEntity> = ids.map { song(it) }) {
		var clock = 1_000_000_000L
		var online = true
		var owner: DownloadOwner? = ownerA
		val rows = ids.associateWith { row(it) }.toMutableMap()
		/** The current session's library as Room has it. */
		val room = songs.associateBy { it.songId }.toMutableMap()
		val origins = mutableMapOf<String, DownloadOriginEntity>()
		val answers = mutableMapOf<String, SongAnswer>()
		val asked = mutableListOf<String>()
		val refetched = mutableListOf<String>()
		val removed = mutableListOf<String>()
		val moved = mutableListOf<Pair<String, String>>()
		var playing = emptySet<String>()
		/** Re-fetches of these always fail (a transcode error, a length mismatch). */
		var failing = emptySet<String>()
		var memory = UpkeepMemory()
		val store = object : DownloadOriginStore {
			override suspend fun all() = origins.values.toList()
			override suspend fun put(rows: List<DownloadOriginEntity>) { rows.forEach { origins[it.songId] = it } }
			override suspend fun remove(songIds: List<String>) { songIds.forEach { origins.remove(it) } }
		}
		val downloads = object : UpkeepDownloads {
			override suspend fun downloaded() = rows.values.toList()
			override fun refetch(song: SongEntity, row: DownloadEntity) {
				refetched += song.songId
				if (song.songId in failing) return // the transfer failed: nothing recorded
				// The swap finished: DownloadManager records what the new file was made from.
				val o = owner!!
				origins[song.songId] = DownloadOriginEntity(song.songId, songOrigin(song), clock, server = o.server, account = o.account)
			}
			override suspend fun remove(songId: String) { removed += songId; rows.remove(songId) }
			override suspend fun move(fromId: String, to: SongEntity): Boolean {
				val r = rows.remove(fromId) ?: return false
				rows[to.songId] = r.copy(songId = to.songId, filePath = "/d/${to.songId}.flac")
				moved += fromId to to.songId
				return true
			}
		}
		val upkeep = DownloadUpkeep(
			store = store,
			downloads = downloads,
			songsById = { wanted -> wanted.mapNotNull { room[it] } },
			askSong = { id -> asked += id; answers[id] ?: SongAnswer.PRESENT },
			isOnline = { online },
			now = { clock },
			owner = { owner },
			memory = object : UpkeepMemoryStore {
				override suspend fun load() = memory
				override suspend fun save(memory: UpkeepMemory) { this@World.memory = memory }
			},
			inUse = { playing },
			songsAt = { track, disc -> room.values.filter { (it.trackNumber ?: 0) == track && (it.discNumber ?: 0) == disc } }
		)

		/** Each download as it was fetched under [by]: its origin is the song as Room has it now. */
		fun recordAll(by: DownloadOwner = ownerA, only: Collection<String> = rows.keys.toList()) = only.forEach {
			origins[it] = DownloadOriginEntity(it, songOrigin(room.getValue(it)), clock, server = by.server, account = by.account)
		}

		fun pass(listed: Set<String>?, songsBefore: Int? = null) = runBlocking { upkeep.afterPull(PullSeen(listed, songsBefore)) }

		/** [n] passes, [every] apart on the wall clock, each a complete pull listing [listed]. */
		fun passes(n: Int, every: kotlin.time.Duration, listed: () -> Set<String>?) = (1..n).map {
			pass(listed()).also { clock += every.inWholeMilliseconds }
		}
	}

	// ---- CHANGED --------------------------------------------------------------------------- //

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
	fun aDownloadWithNoRecordIsRecordedUnderThisSessionNotFetched() {
		val w = World(listOf("s1"))
		w.pass(listed = null)
		assertTrue("s1" !in w.origins, "follow-up 2: a sweep with no list places nothing (Room may hold another session's songs)")
		w.pass(listed = setOf("s1"))
		assertEquals(emptyList(), w.refetched, "a file from before this build is taken as it is")
		val record = w.origins.getValue("s1")
		assertEquals(songOrigin(w.room.getValue("s1")), record.origin, "and what it is now is recorded")
		assertEquals(ownerA.server to ownerA.account, record.server to record.account, "under the session whose library holds it")
	}

	@Test
	fun aPlayingOrQueuedSongIsNotSwapped() {
		val w = World(listOf("s1", "s2"))
		w.recordAll()
		w.room["s1"] = w.room.getValue("s1").copy(title = "changed")
		w.playing = setOf("s1")
		val report = w.pass(listed = null)
		assertEquals(emptyList(), w.refetched, "not while it is playing or queued: the player reads its path")
		assertEquals(listOf("s1"), report.deferred)
		w.playing = emptySet()
		w.clock += 1.hours.inWholeMilliseconds
		w.pass(listed = null)
		assertEquals(listOf("s1"), w.refetched, "the next pass, once it is out of the queue")
	}

	@Test
	fun reFetchesAreCappedPerPassAndPerDay() {
		val ids = (100..249).map { "s$it" }
		val w = World(ids)
		w.recordAll()
		ids.forEach { w.room[it] = w.room.getValue(it).copy(year = 1999) }
		val perPass = w.passes(6, 1.hours) { null }.map { it.refetched.size }
		assertEquals(listOf(20, 20, 20, 20, 20, 0), perPass, "20 a pass, 100 a day")
		// 25 h of wall time since the last pass counts 12 h on the upkeep's clock (17 h into its day), so the
		// day ends one pass later.
		w.clock += 24.hours.inWholeMilliseconds
		val laterDay = w.passes(3, 12.hours) { null }.map { it.refetched.size }
		assertEquals(listOf(0, 20, 20), laterDay, "a new day of the upkeep's clock, a new budget")
		assertEquals(ids.size, w.refetched.toSet().size + ids.count { w.origins.getValue(it).origin != songOrigin(w.room.getValue(it)) },
			"each changed song is asked for once, the rest wait their turn")
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
		val fields = originFields(songOrigin(a))
		assertEquals(OriginFields(5_000_000L, "flac", 900, 200L, "Song s1", "Artist", "", "Album s1", 1, 1, 2001, "mb-s1"), fields)
		assertEquals(null, originFields(""), "no record is no fields")
	}

	// ---- GONE ------------------------------------------------------------------------------ //

	@Test
	fun aSongGoneOnThreePullsOverThreeDaysIsRemoved() {
		val ids = (1..8).map { "s$it" }
		val w = World(ids)
		w.recordAll()
		val listed = ids.toSet() - "s1"
		w.answers["s1"] = SongAnswer.GONE
		w.pass(listed)
		assertEquals(listOf("s1"), w.asked, "only a song the complete pull did not list is asked about")
		assertEquals(1, w.origins.getValue("s1").gonePulls, "it is marked")
		w.clock += 12.hours.inWholeMilliseconds
		val early = w.passes(5, 12.hours) { listed }
		assertEquals(emptyList(), early.flatMap { it.removed }, "six pulls over 60 h: not yet")
		w.pass(listed)
		assertEquals(listOf("s1"), w.removed, "gone on three or more complete pulls over 72 h: the copy and the row go")
		assertTrue("s1" !in w.origins, "and the record")
	}

	@Test
	fun threeDaysOnOnePullIsNotEnough() {
		val ids = (1..8).map { "s$it" }
		val w = World(ids)
		w.recordAll()
		w.answers["s1"] = SongAnswer.GONE
		val listed = ids.toSet() - "s1"
		w.pass(listed)
		repeat(8) { w.clock += 12.hours.inWholeMilliseconds; w.pass(null) } // partial pulls and sweeps only
		w.pass(listed)
		assertEquals(emptyList(), w.removed, "two complete pulls, however far apart")
		w.clock += 1.minutes.inWholeMilliseconds
		w.pass(listed)
		assertEquals(listOf("s1"), w.removed, "the third")
	}

	@Test
	fun aClockSetForwardDoesNotHurryIt() {
		val ids = (1..8).map { "s$it" }
		val w = World(ids)
		w.recordAll()
		w.answers["s1"] = SongAnswer.GONE
		val listed = ids.toSet() - "s1"
		w.pass(listed)
		w.clock += 10.days.inWholeMilliseconds
		w.pass(listed)
		w.clock += 1.minutes.inWholeMilliseconds
		w.pass(listed)
		w.clock -= 30.days.inWholeMilliseconds // and back
		w.pass(listed)
		assertEquals(emptyList(), w.removed, "a jumped clock counts one step (12 h), and a clock set back nothing")
		assertTrue(w.memory.clockMs in 1..(13.hours.inWholeMilliseconds), "the upkeep's clock: ${w.memory.clockMs}")
	}

	@Test
	fun aSongSeenAgainStartsOver() {
		val w = World(listOf("s1", "s2", "s3", "s4"))
		w.recordAll()
		w.answers["s1"] = SongAnswer.GONE
		w.passes(4, 12.hours) { setOf("s2", "s3", "s4") }
		w.pass(setOf("s1", "s2", "s3", "s4"))
		assertEquals(0L to 0, w.origins.getValue("s1").let { it.goneSeenAt to it.gonePulls }, "listed again: the mark is cleared")
		w.clock += 1.hours.inWholeMilliseconds
		w.passes(2, 1.minutes) { setOf("s2", "s3", "s4") }
		assertEquals(emptyList(), w.removed, "a new first word, not a fifth")
	}

	@Test
	fun aNotFoundStormRemovesNothing() {
		// The library unmounted, a rescan, the wrong server: Navidrome lists none of 30 and says 70 for each.
		val ids = (1..30).map { "s$it" }
		val w = World(ids)
		w.recordAll()
		ids.forEach { w.answers[it] = SongAnswer.GONE }
		val reports = w.passes(10, 12.hours) { emptySet() }
		assertEquals(emptyList(), w.removed, "nothing is removed")
		assertTrue(w.origins.values.all { it.gonePulls == 0 }, "nor marked")
		assertEquals(emptyList(), w.asked, "and Navidrome is not asked song by song")
		assertTrue(reports.last().goneHeld.isNotBlank(), "the pass says it held back")
	}

	@Test
	fun mostOfASmallSetGoneAtOnceRemovesNothing() {
		val w = World(listOf("s1", "s2", "s3", "s4", "s5", "s6"))
		w.recordAll()
		listOf("s1", "s2", "s3", "s4").forEach { w.answers[it] = SongAnswer.GONE }
		w.passes(10, 12.hours) { setOf("s5", "s6") }
		assertEquals(emptyList(), w.removed, "four of six at once is a storm too")
	}

	@Test
	fun aTransientNotFoundRemovesNothing() {
		// A 404 from a proxy, a 503, a timeout: not Navidrome's word that the song is gone.
		val w = World(listOf("s1", "s2", "s3", "s4"))
		w.recordAll()
		w.answers["s1"] = SongAnswer.NO_ANSWER
		w.answers["s2"] = SongAnswer.GONE
		w.passes(10, 12.hours) { setOf("s3", "s4") }
		assertEquals(emptyList(), w.removed, "nothing is removed")
		assertEquals(0, w.origins.getValue("s1").gonePulls, "and no answer marks nothing")
	}

	@Test
	fun aPartialPullJudgesNothingGone() {
		val w = World(listOf("s1"))
		w.recordAll()
		w.room.remove("s1")
		w.answers["s1"] = SongAnswer.GONE
		w.passes(10, 12.hours) { null }
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
		w.passes(10, 12.hours) { setOf("s3", "s4") }
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

	// ---- Major 1: a re-key is a move, before any gone rule -------------------------------- //

	/** [n] downloads; the first [rekeyed] are tracks 1.. of one album, re-keyed by Navidrome to `n<k>`. */
	private fun rekeyWorld(n: Int, rekeyed: Int, newAlbum: String = "Re-key Album"): World {
		val old = (1..rekeyed).map { song("s$it", title = "Track $it", track = it, album = "Re-key Album", seconds = 180 + it) } +
			((rekeyed + 1)..n).map { song("s$it") }
		val w = World(old.map { it.songId }, old)
		w.recordAll()
		(1..rekeyed).forEach { k ->
			w.room.remove("s$k")
			w.room["n$k"] = song("n$k", title = "Track $k", track = k, album = newAlbum, seconds = 180 + k + (k % 3) - 1)
				.copy(musicBrainzId = "mb-new-$k")
			w.answers["s$k"] = SongAnswer.GONE
		}
		return w
	}

	@Test
	fun aReKeyedAlbumKeepsItsDownloads() {
		// The reviewer's case: 40 downloads, 12 of them one album Navidrome re-keyed.
		val w = rekeyWorld(40, 12)
		val reports = w.passes(10, 12.hours) { w.room.keys.toSet() }
		assertEquals(emptyList(), w.removed, "nothing is removed")
		assertEquals((1..12).map { "s$it" to "n$it" }, w.moved, "each download is carried to its new id, once")
		assertEquals((1..12).map { "n$it" }.toSet(), w.rows.keys.filter { it.startsWith("n") }.toSet())
		assertEquals(emptyList(), w.asked.filter { it.startsWith("s") }, "a moved song is never asked about")
		assertEquals(12, reports.first().moved.size)
		// The move keeps the recorded origin, so the retag (a new MusicBrainz id) is fetched again as a change.
		assertEquals((1..12).map { "n$it" }.toSet(), w.refetched.toSet())
	}

	@Test
	fun aReKeyOnASmallDeviceIsMovedNotHeld() {
		// Under 24 downloads the same album was a storm, held for ever with its new ids never downloaded.
		val w = rekeyWorld(12, 12)
		w.passes(10, 12.hours) { w.room.keys.toSet() }
		assertEquals(12, w.moved.size, "moved, though it is every download on the device")
		assertEquals(emptyList(), w.removed)
	}

	@Test
	fun aRenamedAlbumStillMoves() {
		// An override can rename the album ("X (Deluxe)" to "X"): the album title only breaks a tie.
		val w = rekeyWorld(10, 3, newAlbum = "Re-key")
		w.pass(w.room.keys.toSet())
		assertEquals(3, w.moved.size)
	}

	@Test
	fun anAmbiguousSuccessorFallsThroughAndTheAlbumBreaksATie() {
		val w = rekeyWorld(10, 1)
		// A second song at disc 1, track 1, the same title and length, on another album: two fit.
		w.room["x1"] = song("x1", title = "Track 1", track = 1, album = "Other Album", seconds = 181)
		w.pass(w.room.keys.toSet())
		assertEquals(listOf("s1" to "n1"), w.moved, "the album title tells them apart")
		val v = rekeyWorld(10, 1, newAlbum = "Renamed")
		v.room["x1"] = song("x1", title = "Track 1", track = 1, album = "Other Album", seconds = 181)
		v.pass(v.room.keys.toSet())
		assertEquals(emptyList(), v.moved, "two fit and neither album matches: no guess")
		assertEquals(1, v.origins.getValue("s1").gonePulls, "it falls through to the gone rules")
	}

	@Test
	fun aSuccessorMustBeTheSameTrack() {
		val w = rekeyWorld(10, 1)
		w.room["n1"] = w.room.getValue("n1").copy(duration = 190.seconds) // more than 2 s off
		w.pass(w.room.keys.toSet())
		assertEquals(emptyList(), w.moved, "another length is another recording")
		val v = rekeyWorld(10, 1)
		v.room["n1"] = v.room.getValue("n1").copy(artistName = "Someone Else")
		v.pass(v.room.keys.toSet())
		assertEquals(emptyList(), v.moved, "another artist")
		val u = rekeyWorld(10, 1)
		u.rows["n1"] = row("n1")
		u.recordAll(only = listOf("n1"))
		u.pass(u.room.keys.toSet())
		assertEquals(emptyList(), u.moved, "a successor already downloaded is not overwritten")
		val t = rekeyWorld(10, 1)
		t.playing = setOf("s1")
		t.pass(t.room.keys.toSet())
		assertEquals(emptyList(), t.moved, "a queued file is not renamed under the player")
	}

	@Test
	fun aStormOfReKeysIsMovedToo() {
		// 30 re-keyed at once is over the storm line; a move removes nothing, so it needs no guard.
		val w = rekeyWorld(30, 30)
		w.pass(w.room.keys.toSet())
		assertEquals(30, w.moved.size)
	}

	// ---- Major 2: whose a download is ------------------------------------------------------- //

	@Test
	fun anotherServersDownloadsAreNeverJudged() {
		// The reviewer's case: 20 downloads from A, then a login to B and 21 of B's.
		val a = (1..20).map { song("a$it") }
		val b = (1..21).map { song("b$it") }
		val w = World((a + b).map { it.songId }, a)
		w.recordAll(ownerA, a.map { it.songId })
		w.owner = ownerB
		w.room.clear()
		b.forEach { w.room[it.songId] = it }
		w.recordAll(ownerB, b.map { it.songId })
		a.forEach { w.answers[it.songId] = SongAnswer.GONE }
		w.passes(10, 12.hours) { w.room.keys.toSet() }
		assertEquals(emptyList(), w.removed, "A's downloads are kept")
		assertEquals(emptyList(), w.asked, "and B is never asked about A's songs")
		assertTrue(a.all { w.origins.getValue(it.songId).server == ownerA.server }, "and stay A's")
	}

	@Test
	fun anotherUserOnTheSameServerIsAnotherOwner() {
		val w = World(listOf("s1", "s2", "s3", "s4"))
		w.recordAll(ownerA)
		w.owner = DownloadOwner.of(ownerA.server, "carol")
		w.answers["s1"] = SongAnswer.GONE // carol has no access to s1's library
		w.passes(10, 12.hours) { setOf("s2", "s3", "s4") }
		assertEquals(emptyList(), w.removed)
	}

	@Test
	fun aRecordFromBeforeThreeIsNeverJudgedGoneUntilItsSongIsProved() {
		val w = World(listOf("s1", "s2", "s3", "s4"))
		w.recordAll(DownloadOwner("", ""))
		w.room.remove("s1")
		w.answers["s1"] = SongAnswer.GONE
		w.passes(10, 12.hours) { setOf("s2", "s3", "s4") }
		assertEquals(emptyList(), w.removed, "a record nobody can place is kept")
		assertEquals(emptyList(), w.asked)
		assertEquals("", w.origins.getValue("s1").server, "and stays unplaced")
		assertEquals(ownerA.server, w.origins.getValue("s2").server, "one whose song this session's library holds is adopted")
	}

	@Test
	fun noRecordIsInventedForAGoneSong() {
		val w = World(listOf("s1", "s2", "s3", "s4"))
		w.recordAll(only = listOf("s2", "s3", "s4"))
		w.room.remove("s1") // no record, and this session's library never held it
		w.answers["s1"] = SongAnswer.GONE
		w.passes(10, 12.hours) { setOf("s2", "s3", "s4") }
		assertEquals(emptyList(), w.removed)
		assertEquals(emptyList(), w.asked)
		assertTrue("s1" !in w.origins, "nothing is recorded for a song this session never described")
	}

	@Test
	fun noSessionNothingHappens() {
		val w = World(listOf("s1", "s2", "s3", "s4"))
		w.recordAll()
		w.owner = null
		w.answers["s1"] = SongAnswer.GONE
		w.passes(10, 12.hours) { setOf("s2", "s3", "s4") }
		assertEquals(emptyList(), w.asked)
		assertEquals(emptyList(), w.removed)
	}

	// ---- Minor 3: a missing share ----------------------------------------------------------- //

	@Test
	fun aShareMissingForDaysKeepsItsDownloads() {
		// The reviewer's case: 200 downloads, 20 on a share that goes missing; the library loses 2,000 songs.
		val ids = (1..200).map { "s$it" }
		val w = World(ids)
		w.recordAll()
		val others = (1..9_800).map { "o$it" }.toSet()
		w.pass(ids.toSet() + others)
		val missing = (1..20).map { "s$it" }.toSet()
		missing.forEach { w.answers[it] = SongAnswer.GONE }
		val shrunk = ids.toSet() - missing + others.drop(1_980)
		val reports = w.passes(14, 12.hours) { shrunk }
		assertEquals(emptyList(), w.removed, "20 of 200 is no storm, but the library shrank by 20 %")
		assertTrue(reports.last().goneHeld.contains("shrank"), reports.last().goneHeld)
	}

	@Test
	fun aRealDeletionInABigLibraryIsStillRemoved() {
		val ids = (1..200).map { "s$it" }
		val w = World(ids)
		w.recordAll()
		val others = (1..9_800).map { "o$it" }.toSet()
		w.pass(ids.toSet() + others)
		val deleted = (1..20).map { "s$it" }.toSet()
		deleted.forEach { w.answers[it] = SongAnswer.GONE }
		w.passes(8, 12.hours) { ids.toSet() - deleted + others }
		assertEquals(deleted, w.removed.toSet(), "20 songs of 10,000: under max(25, 2 %), judged as before")
	}

	// ---- follow-up 2 (REVIEW-phase-b-android-ownerfollowup-2026-11-12) ------------------------ //

	@Test
	fun roomHoldingAnotherSessionsSongsPlacesNothing() {
		// Minor 1: an upgrade with no records, a switch A to B before any pass, and Room still holding A's
		// songs (logout's wipe lost the race): only B's own complete pull may place a download.
		val a = (1..5).map { song("a$it") }
		val b = (1..10).map { song("b$it") }
		val w = World(a.map { it.songId } + b.map { it.songId }, a + b) // Room holds both
		w.owner = ownerB
		a.forEach { w.answers[it.songId] = SongAnswer.GONE }
		w.pass(listed = null)
		assertTrue(a.none { it.songId in w.origins }, "a sweep places nothing")
		w.passes(10, 12.hours) { b.map { it.songId }.toSet() }
		assertTrue(a.none { it.songId in w.origins }, "B's complete pulls never listed A's songs, so they are never B's")
		assertEquals(emptyList(), w.removed)
		assertEquals(emptyList(), w.asked)
		assertTrue(b.all { w.origins.getValue(it.songId).server == ownerB.server }, "B's own are placed by B's pull")
	}

	@Test
	fun aFirstPullIsMeasuredAgainstTheCachedLibrary() {
		// Minor 3: no reference yet (an upgrade), and a share is already missing at the first complete pull.
		val ids = (1..200).map { "s$it" }
		val w = World(ids)
		w.recordAll()
		val missing = (1..20).map { "s$it" }.toSet()
		missing.forEach { w.answers[it] = SongAnswer.GONE }
		val others = (1..7_800).map { "o$it" }.toSet()
		val reports = (1..14).map {
			w.pass(ids.toSet() - missing + others, songsBefore = 10_000).also { w.clock += 12.hours.inWholeMilliseconds }
		}
		assertEquals(emptyList(), w.removed, "Room held 10,000 songs before the pull, and the pull lists 7,980")
		assertTrue(reports.first().goneHeld.contains("shrank"), reports.first().goneHeld)
	}

	@Test
	fun aSwitchAndBackKeepsTheOwnersReference() {
		// Minor 3: A to B to A must not reopen the gap.
		val ids = (1..200).map { "s$it" }
		val w = World(ids)
		w.recordAll()
		val others = (1..9_800).map { "o$it" }.toSet()
		w.pass(ids.toSet() + others)
		w.owner = ownerB
		w.passes(2, 12.hours) { setOf("b1") }
		w.owner = ownerA
		val missing = (1..20).map { "s$it" }.toSet()
		missing.forEach { w.answers[it] = SongAnswer.GONE }
		w.passes(14, 12.hours) { ids.toSet() - missing + others.drop(1_980) }
		assertEquals(emptyList(), w.removed, "A's reference of 10,000 survived B")
	}

	@Test
	fun twoOldIdsClaimingOneSuccessorMoveTheBetterOne() {
		// Minor 5: s1 is the exact match (same length, same album); s2 a near one on another album.
		val old = listOf(
			song("s1", title = "Track 1", track = 1, album = "Re-key Album", seconds = 181),
			song("s2", title = "Track 1", track = 1, album = "Other Album", seconds = 182)
		) + (3..10).map { song("s$it") }
		val w = World(old.map { it.songId }, old)
		w.recordAll()
		w.room.remove("s1"); w.room.remove("s2")
		w.room["n1"] = song("n1", title = "Track 1", track = 1, album = "Re-key Album", seconds = 181)
		w.answers["s2"] = SongAnswer.GONE
		w.pass(w.room.keys.toSet())
		assertEquals(listOf("s1" to "n1"), w.moved, "the closer one moves")
		assertEquals(1, w.origins.getValue("s2").gonePulls, "the other falls through to the gone rules")
	}

	@Test
	fun aFailingReFetchDoesNotStarveTheOthers() {
		// Minor 6: twenty re-fetches that always fail were first by id on every pass.
		val ids = (10..39).map { "s$it" }
		val w = World(ids)
		w.recordAll()
		ids.forEach { w.room[it] = w.room.getValue(it).copy(year = 1999) }
		w.failing = (10..29).map { "s$it" }.toSet()
		val first = w.pass(listed = null)
		assertEquals((10..29).map { "s$it" }, first.refetched, "the first twenty by id")
		w.clock += 1.hours.inWholeMilliseconds
		val second = w.pass(listed = null)
		assertEquals((30..39).map { "s$it" }, second.refetched.take(10), "next pass: the ones never asked first")
		assertTrue(second.refetched.size <= 10, "and the failed ones wait out their backoff: ${second.refetched}")
		assertTrue((30..39).all { w.origins.getValue("s$it").origin == songOrigin(w.room.getValue("s$it")) })
		// A failure keeps backing off: over a day, a failing song is asked far less than once a pass.
		repeat(23) { w.clock += 1.hours.inWholeMilliseconds; w.pass(listed = null) }
		val asksOfOne = w.refetched.count { it == "s10" }
		assertTrue(asksOfOne in 2..6, "s10 asked $asksOfOne times in 25 hourly passes")
	}
}
