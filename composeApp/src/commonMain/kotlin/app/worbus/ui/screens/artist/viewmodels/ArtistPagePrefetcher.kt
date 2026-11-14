package app.worbus.ui.screens.artist.viewmodels

import app.worbus.util.Logger
import kotlinx.coroutines.CancellationException

/**
 * B-446 follow-up (the owner, 2026-11-14: "each artist page now opens on a short loading screen"): an artist
 * page with nothing in [ArtistPageMemory] slides in on a placeholder and shows its Room read once its slide
 * has ended (B-446's `FirstShowHold`); a page that is in memory is complete in its first frame, before
 * anything moves. So while an album page is shown, its artist's page is read ahead into the memory, and the
 * album's artist link opens a seeded page: the cached open, not the cold one.
 *
 * The read is the page's own ([ArtistPageReader.snapshot]: Room and the index mirror, nothing from the
 * network), so a page opened on it is what the page's own read would have shown; the network half (the
 * server's similar artists, "Appears on", the metadata) is asked for once its slide has ended, as on a
 * revisit. One read per artist at a time, none for an artist already kept, and a read that ends in another
 * session is dropped ([ArtistPageMemory.putPrefetched]). Main thread: [warm] is called from a composition
 * effect, and the read itself runs on [kotlinx.coroutines.Dispatchers.Default].
 */
class ArtistPagePrefetcher(
	/** The page's own read ([ArtistPageReader.snapshot]); a function, so the rules here are testable. */
	private val read: suspend (artistId: String) -> ArtistPageSnapshot,
	private val memory: ArtistPageMemory
) {
	private val inFlight = mutableSetOf<String>()

	/**
	 * Reads [artistId]'s page into the memory unless it is kept already or being read. Returns the
	 * snapshot it kept (for the caller to warm the artist photo), or null when it kept nothing.
	 */
	suspend fun warm(artistId: String): ArtistPageSnapshot? {
		if (artistId.isBlank() || memory.holds(artistId) || !inFlight.add(artistId)) return null
		val session = memory.currentSession
		try {
			val snapshot = read(artistId)
			return snapshot.takeIf { memory.putPrefetched(artistId, it, readIn = session) }
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			// An artist Room does not hold (a compilation's "Various Artists" before a sync): the page
			// says so itself when opened. Nothing is kept.
			Logger.w("ArtistPagePrefetcher", "could not read artist $artistId ahead", e)
			return null
		} finally {
			inFlight.remove(artistId)
		}
	}
}
