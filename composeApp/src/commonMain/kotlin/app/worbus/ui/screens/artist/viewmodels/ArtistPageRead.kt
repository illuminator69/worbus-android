package app.worbus.ui.screens.artist.viewmodels

import app.worbus.data.database.dao.AlbumDao
import app.worbus.data.database.dao.ArtistDao
import app.worbus.data.database.mappers.toDomainModel
import app.worbus.domain.manager.LbBotManager
import app.worbus.domain.manager.LbDiscography
import app.worbus.domain.manager.LbIndexSync
import app.worbus.domain.manager.LbRelease
import app.worbus.domain.models.DomainAlbum
import app.worbus.domain.models.DomainArtist
import app.worbus.domain.models.DomainSong
import app.worbus.domain.models.displayName
import app.worbus.domain.repositories.ArtistRepository
import app.worbus.ui.screens.artist.discographyUnresolved
import app.worbus.util.Logger
import app.worbus.util.albumTitleKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

/*
 * B-446 follow-up (the owner, 2026-11-14: every cold artist page opened on a short loading screen): the
 * artist page's own Room read, as one piece the page and the album page's prefetch ([ArtistPagePrefetcher])
 * both run, so a page opened on a prefetch is seeded with exactly what its own read would have shown, and a
 * prefetched open is a revisit's.
 */

/** What one Room read of an artist gives the page, before anything is asked of the network. */
data class ArtistRoomRead(
	val artist: DomainArtist,
	val albums: List<DomainAlbum>,
	val topSongs: List<DomainSong>,
	/** The similar artists Room holds rows for (the server's own list arrives later, with the metadata). */
	val similarArtists: List<DomainArtist>,
	val starred: Boolean
)

/** The shelf's sections, and the albums filed under another artist that its rows resolved to. */
data class DiscographyBuild(
	val sections: List<DiscographySection>,
	val strayAlbumIds: Set<String>
)

class ArtistPageReader(
	private val artistDao: ArtistDao,
	private val albumDao: AlbumDao,
	private val artistRepository: ArtistRepository,
	private val lbBotManager: LbBotManager,
	private val lbIndexSync: LbIndexSync
) {
	/**
	 * The artist, every album with its songs (by the artist's id, else by name), the twelve most played
	 * songs, the similar artists Room holds and the favourite. Off the main thread (the mapping of every
	 * album's songs ran on it in the page's own scope). Throws when Room has no such artist.
	 */
	suspend fun readRoom(artistId: String): ArtistRoomRead = withContext(Dispatchers.Default) {
		val artistEntity = artistDao.getArtistById(artistId)
			?: throw Exception("Artist not found in database")
		val domainArtist = artistEntity.toDomainModel()

		var albumsWithSongs =
			albumDao.getAlbumsByArtist(artistId).firstOrNull() ?: emptyList()

		if (albumsWithSongs.isEmpty()) {
			albumsWithSongs =
				albumDao.getAlbumsByArtistName(domainArtist.name).firstOrNull()
					?: emptyList()
		}

		val domainAlbums = albumsWithSongs.map { it.toDomainModel() }

		val domainSongs = albumsWithSongs.flatMap { it.songs }
			.map { it.toDomainModel() }
			.sortedByDescending { it.playCount }
			.take(12)

		val initialSimilarArtists = domainArtist.similarArtistIds.mapNotNull { id ->
			artistDao.getArtistById(id)?.toDomainModel()
		}

		ArtistRoomRead(
			artist = domainArtist,
			albums = domainAlbums,
			topSongs = domainSongs,
			similarArtists = initialSimilarArtists,
			starred = artistRepository.isArtistStarred(domainArtist)
		)
	}

	/** Which MusicBrainz artist [artist] is to lb-bot, from the override map as it stands now (I-019). */
	fun mbidFor(artist: DomainArtist): ArtistMbidUi = ArtistMbidUi(
		effective = lbBotManager.effectiveArtistMbid(artist.id, artist.musicBrainzId),
		tag = artist.musicBrainzId.orEmpty(),
		overridden = lbBotManager.artistOverride(artist.id) != null,
		// The flow is the hub's word, as of its last advert; the hub toggle is read live.
		canOverride = lbBotManager.artistOverridesSupported.value && lbBotManager.isConfigured
	)

	/**
	 * lb-bot's discography for this artist from the local index mirror (Room, no network), looked up
	 * exactly as lb-bot looks up the network read (MBID key first, then the Navidrome id); not the row
	 * still filed under [leftMbid], the id the page has just stopped asking with.
	 */
	suspend fun readMirror(artistId: String, mbid: String, leftMbid: String): LbDiscography? {
		if (!lbBotManager.isConfigured) return null
		return runCatching { lbIndexSync.discography(artistId, mbid) }
			.onFailure { Logger.w("ArtistPageReader", "index mirror read failed", it) }
			.getOrNull()
			?.takeUnless { leftMbid.isNotBlank() && it.artistMbid == leftMbid }
	}

	/**
	 * What a page of [artistId] shows after its own Room read, as [ArtistPageMemory] keeps it: the read,
	 * the MusicBrainz id, and the shelf from the mirror, built as [ArtistDetailViewModel]'s first read
	 * builds it. Nothing from the network (the server's similar artists, "Appears on", the metadata, the
	 * network discography for an artist the mirror does not hold): a page opened on this asks for those
	 * once its slide has ended, as a revisited page does.
	 */
	suspend fun snapshot(artistId: String): ArtistPageSnapshot {
		val room = readRoom(artistId)
		val mbid = mbidFor(room.artist)
		val mirrored = readMirror(artistId, mbid.effective, leftMbid = "")
		val built = mirrored?.let { buildDiscographySections(albumDao, room.albums, it.releases) }
		return ArtistPageSnapshot(
			state = ArtistState(
				artist = room.artist,
				albums = room.albums,
				topSongs = room.topSongs,
				similarArtists = room.similarArtists
			),
			discography = if (mirrored != null && built != null) {
				discographyUiFor(mirrored, built.sections, DiscographyUi(), mirror = true)
			} else {
				DiscographyUi()
			},
			mbid = mbid,
			starred = room.starred,
			lbData = mirrored,
			fromMirror = mirrored != null,
			strayAlbumIds = built?.strayAlbumIds.orEmpty()
		)
	}
}

/** The shelf's state for lb-bot's [data] and the [sections] built from it, carrying [previous]'s scan state. */
internal fun discographyUiFor(
	data: LbDiscography,
	sections: List<DiscographySection>,
	previous: DiscographyUi,
	mirror: Boolean
): DiscographyUi = if (!data.indexed) {
	DiscographyUi(
		available = true,
		indexed = false,
		indexing = previous.indexing,
		scanError = previous.scanError,
		scanShared = previous.scanShared,
		scanDone = previous.scanDone,
		scanTotal = previous.scanTotal,
		ndIdScanRefused = previous.ndIdScanRefused,
		justScanned = previous.justScanned
	)
} else {
	DiscographyUi(
		available = true,
		indexed = true,
		indexing = previous.indexing,
		scanError = previous.scanError,
		scanShared = previous.scanShared,
		scannedAt = (data.scannedAt * 1000).toLong(),
		stale = data.stale,
		sections = sections,
		scanDone = previous.scanDone,
		scanTotal = previous.scanTotal,
		// lb-bot's own read says it; the mirror cannot, so a mirror row keeps what the read said
		// for as long as it still looks like "no match" ([discographyUnresolved]).
		unresolved = if (mirror) previous.unresolved && discographyUnresolved(data) else data.unresolved,
		ndIdScanRefused = previous.ndIdScanRefused,
		justScanned = previous.justScanned
	)
}

/**
 * Build the discography shelf: everything Navidrome has by this artist, plus
 * everything lb-bot's MusicBrainz index says exists and the library doesn't.
 *
 * Navidrome comes first and is never dropped. lb-bot's list is its own view of
 * the artist, and an album whose Navidrome record its matcher couldn't claim has
 * no row at all — so building the shelf out of lb-bot's list would silently hide
 * albums the user owns.
 *
 * Pure list-crunching, so it runs on [Dispatchers.Default].
 *
 * `viewModelScope` dispatches on `Main.immediate`, so without this the whole matching pass —
 * two maps over every lb-bot release, a pass over every owned album, then a groupBy and a sort
 * per section — ran on the UI thread, landing precisely as the discography painted. A prolific
 * artist with a large lb-bot index is where that was felt.
 */
internal suspend fun buildDiscographySections(
	albumDao: AlbumDao,
	albums: List<DomainAlbum>,
	releases: List<LbRelease>
): DiscographyBuild = withContext(Dispatchers.Default) {
	val claimedRelease = mutableMapOf<String, LbRelease>()
	val usedRgids = mutableSetOf<String>()

	// Navidrome album ids are authoritative — lb-bot wrote them itself when it
	// matched the release-group. Fall back to a normalised title only for the rows
	// it couldn't attach an id to.
	val byNdId = mutableMapOf<String, LbRelease>()
	val byTitle = mutableMapOf<String, LbRelease>()
	for (release in releases) {
		release.navidromeAlbumIds.forEach { id ->
			if (id.isNotBlank() && id !in byNdId) byNdId[id] = release
		}
		val key = albumTitleKey(release.title)
		if (key.isNotEmpty() && key !in byTitle) byTitle[key] = release
	}
	for (album in albums) {
		val match = byNdId[album.id] ?: byTitle[albumTitleKey(album.displayName)]
		if (match != null && usedRgids.add(match.rgid)) claimedRelease[album.id] = match
	}

	// Now the other direction, for albums Navidrome filed under SOMEBODY ELSE.
	//
	// The loop above walks this artist's albums, so an id can only ever match if the album is
	// already on this page. A collaboration record — downloaded from A's page, tagged so that
	// Navidrome files it under B — is by definition not, and lb-bot's perfectly good album id
	// had nothing to match against. The row fell through to `absent` with album == null, which
	// is precisely `pendingSync`: "Added — syncing", forever, on an album the library holds.
	//
	// Room, not `albums`, is the right place to look those up: the album exists, it just isn't
	// one of this artist's.
	val knownIds = albums.mapTo(mutableSetOf()) { it.id }
	val strayIds = releases
		.filter { it.rgid.isNotBlank() && it.rgid !in usedRgids }
		.flatMap { release -> release.navidromeAlbumIds.map { it to release } }
		.filter { (id, _) -> id.isNotBlank() && id !in knownIds }
	val strayAlbums = if (strayIds.isEmpty()) {
		emptyList()
	} else {
		runCatching { albumDao.getAlbumsByIds(strayIds.map { it.first }.distinct()) }
			.getOrDefault(emptyList())
			.map { it.toDomainModel() }
	}
	val strayById = strayAlbums.associateBy { it.id }
	val crossArtist = mutableListOf<DomainAlbum>()
	for ((id, release) in strayIds) {
		val album = strayById[id] ?: continue
		// One album, one tile: two releases can name the same Navidrome album id, and the
		// per-rgid guard below would let the second through as a duplicate row.
		if (album.id in claimedRelease) continue
		if (!usedRgids.add(release.rgid)) continue
		claimedRelease[album.id] = release
		crossArtist.add(album)
	}

	val owned = (albums + crossArtist).map { album ->
		val release = claimedRelease[album.id]
		DiscographyEntry(
			key = album.id,
			title = album.displayName,
			year = release?.year ?: album.year?.toString().orEmpty(),
			album = album,
			release = release
		)
	}
	// Every lb-bot row that didn't attach to a Navidrome album, whatever its
	// status — NOT just the `missing` ones.
	//
	// Filtering to `missing` here is what made a filled album vanish. lb-bot
	// flips its index row to `present` as soon as the files are placed, so a
	// successful download turned the row non-missing while Room still had no
	// album for it, and the tile fell out of both lists. A `missing` row that
	// matched an owned album is still absorbed into it above rather than
	// dropped, which is what stops the same record rendering twice.
	// Review m1: `add`, so two lb-bot rows with one rgid make one entry (one key), not two.
	val absent = releases
		.filter { it.rgid.isNotBlank() && usedRgids.add(it.rgid) }
		.map { release ->
			DiscographyEntry(
				key = release.rgid,
				title = release.title,
				year = release.year,
				release = release
			)
		}

	val sections = (owned + absent)
		.groupBy { sectionType(it) }
		.map { (type, entries) ->
			DiscographySection(
				type = type,
				entries = entries.sortedWith(
					compareByDescending<DiscographyEntry> { it.year }
						.thenBy { it.title.lowercase() }
				)
			)
		}
		.sortedBy { TYPE_ORDER.indexOf(it.type).takeIf { i -> i >= 0 } ?: TYPE_ORDER.size }
	DiscographyBuild(sections, strayById.keys)
}

/**
 * lb-bot's `effective_type`, raw and lowercase. The display name is the
 * composable's job — turning "ep" into a heading here produced "Ep", and a
 * `replaceFirstChar` can never know that this particular vocabulary contains an
 * initialism. An album lb-bot doesn't know about is an album.
 */
private fun sectionType(entry: DiscographyEntry): String =
	entry.release?.effectiveType?.takeIf { it.isNotBlank() }
		?: entry.release?.primaryType?.takeIf { it.isNotBlank() }
		?: "album"


/** Shelf order, in lb-bot's own vocabulary (`_TYPE_DEFINING_SECONDARY` plus the
 *  primary types it browses). Anything new lands at the end rather than
 *  disappearing. */
private val TYPE_ORDER = listOf(
	"album", "ep", "single", "compilation", "soundtrack", "live", "remix", "demo"
)
