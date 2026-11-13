package app.worbus.ui.screens.artist.viewmodels

/**
 * B-405 (the owner's re-walk, 2026-11-13): what an artist's page last showed, so the next page of the
 * same artist opens on it.
 *
 * "See discography" opens a page with its own view model (a back-stack entry has its own store), which
 * read the whole artist again from Room (artist, every album with its songs, similar artists, the index
 * mirror, the sections) before it had anything to show: the page slid in on a spinner and its ~300 rows
 * were composed mid-slide when the read landed, one 70-90 ms frame on the VM AVD and a second stage the
 * owner saw. The new page now starts from this, and reads Room again only once its slide has ended.
 *
 * Process memory only, a few artists. Main-thread only (view models write and read it there).
 */
class ArtistPageMemory {
	private val pages = LinkedHashMap<String, ArtistPageSnapshot>()

	operator fun get(artistId: String): ArtistPageSnapshot? = pages[artistId]

	fun put(artistId: String, snapshot: ArtistPageSnapshot) {
		pages.remove(artistId)
		pages[artistId] = snapshot
		while (pages.size > KEPT) pages.remove(pages.keys.first())
	}

	private companion object {
		const val KEPT = 8
	}
}

/** One artist page's loaded state. */
data class ArtistPageSnapshot(
	val state: ArtistState,
	val discography: DiscographyUi,
	val mbid: ArtistMbidUi
)

/**
 * What a new page takes of another page's discography: what lb-bot's index says (the sections, when it
 * was scanned, whether it is stale or unresolved), none of that page's own work in flight (a scan it
 * waits on, the scan's counts, an error or a "just scanned" it said), which only that page can finish.
 */
fun DiscographyUi.seeded(): DiscographyUi = DiscographyUi(
	available = available,
	indexed = indexed,
	scannedAt = scannedAt,
	stale = stale,
	sections = sections,
	unresolved = unresolved
)
