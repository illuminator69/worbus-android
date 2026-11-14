package app.worbus.ui.screens.artist.viewmodels

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

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
class ArtistPageMemory(
	/**
	 * B-405 review L3: which session the pages were shown in (`SessionManager.sessionGeneration`, moved by
	 * every login and logout). A snapshot holds one user's data (top songs by their play counts, their
	 * guest albums), and Navidrome's artist ids are derived from the name, so they match across servers:
	 * after a logout, another user or another server, nothing kept is shown.
	 */
	private val session: () -> Long = { 0L }
) {
	private val pages = LinkedHashMap<String, ArtistPageSnapshot>()
	private var keptFor: Long? = null

	operator fun get(artistId: String): ArtistPageSnapshot? {
		forgetAnotherSession()
		return pages[artistId]
	}

	fun put(artistId: String, snapshot: ArtistPageSnapshot) {
		forgetAnotherSession()
		pages.remove(artistId)
		pages[artistId] = snapshot
		while (pages.size > KEPT) pages.remove(pages.keys.first())
	}

	private fun forgetAnotherSession() {
		val now = session()
		if (keptFor != now) {
			pages.clear()
			keptFor = now
		}
	}

	private companion object {
		const val KEPT = 8
	}
}

/** One artist page's loaded state. */
data class ArtistPageSnapshot(
	val state: ArtistState,
	val discography: DiscographyUi,
	val mbid: ArtistMbidUi,
	/** B-405 review L1: the favourite, so the menu of a page opened on this offers the right one at once. */
	val starred: Boolean = false,
	/**
	 * B-405 review L2: what the page's own Room read had worked out beyond what it shows, which a seeded
	 * page needs before its deferred read: lb-bot's rows the shelf was built from ([lbData]), whether the
	 * mirror answered for this artist ([fromMirror]: a library bump then rebuilds locally rather than
	 * asking the network), and the albums filed under another artist that a discography row resolved to
	 * ([strayAlbumIds]: a pending-sync row opens the album, not the unowned page).
	 */
	val lbData: app.worbus.domain.manager.LbDiscography? = null,
	val fromMirror: Boolean = false,
	val strayAlbumIds: Set<String> = emptySet()
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

/**
 * B-446 (the owner, 2026-11-14: "the stutter ... now happens when opening ... an artist page for the first
 * time. it's gone after the pages are cached"): an artist page with nothing in [ArtistPageMemory] slid in on
 * its spinner, and its Room read landed during the slide, so the whole page (hero, top songs, the shelf, the
 * artist photo's scheme) was composed and measured in one frame of the transition, beside the shared
 * transition's lookahead pass: one 60-250 ms frame mid-slide on the VM AVD. A page opened on the memory
 * composes in its first frame, before anything moves, and slides smoothly; that is the cached open.
 *
 * So a page that opened on nothing ([hold]) shows its first content only once its slide has ended
 * ([settled], the page's `OnPageSettled`), at most [waitMs] after the read: the spinner slides in as before
 * and the page fades in at rest. Once only: everything after the first show (the server's similar artists, a
 * star) is shown as it comes.
 */
class FirstShowHold(private val settled: Deferred<Unit>, private val waitMs: Long) {
	private var holding = false

	/** The page opened with nothing to show: its first content waits for the slide. */
	fun hold() {
		holding = true
	}

	/** Before the page's first content: waits for the slide to end (at most [waitMs]) when held, once. */
	suspend fun beforeFirstShow() {
		if (!holding) return
		holding = false
		withTimeoutOrNull(waitMs) { settled.await() }
	}
}
