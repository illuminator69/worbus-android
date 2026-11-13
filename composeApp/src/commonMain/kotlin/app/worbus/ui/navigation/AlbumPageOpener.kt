package app.worbus.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavKey
import app.worbus.di.LocalNavStack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject

/**
 * B-404 review M2: an album page opened by something that holds only the album's id (an lb-bot row, a
 * Discover or Fresh tile resolved to the library, a pending-sync discography row, the album a re-match
 * landed on) still carries its cover in the key, so the page reads the seed store in its first frame
 * instead of sliding in on another page's colour and recolouring when Room answers.
 *
 * The cover is Room's own (`AlbumDao.getAlbumCoverArtId`, one indexed column), asked before the push and
 * waited for at most [LOOKUP_MS]: a hung read opens the page without it rather than not at all. Null
 * means Room holds no cover for that id (none, or not synced yet), and the page then takes no bridge: it
 * is the app's surface until its record names a cover (rule 1 of the colour engine), never a colour
 * borrowed from the page it was opened from. One open at a time per album: a second tap while the first
 * is looking is the same open.
 *
 * Its scope is the app's, not a composable's: the openers include sheets that close on the tap.
 */
class AlbumPageOpener(
	private val coverOf: suspend (albumId: String) -> String?,
	private val scope: CoroutineScope
) {
	private val opening = mutableSetOf<String>()

	fun open(albumId: String, tab: String, openGaps: Boolean = false, push: (NavKey) -> Unit) {
		if (albumId.isBlank() || !opening.add(albumId)) return
		scope.launch {
			try {
				val cover = coverFor(albumId)
				push(Screen.CollectionDetail(albumId, tab, openGaps = openGaps, coverArtId = cover))
			} finally {
				opening.remove(albumId)
			}
		}
	}

	/** Room's cover for [albumId], waited for at most [LOOKUP_MS]; for openers already in a coroutine. */
	suspend fun coverFor(albumId: String): String? =
		withTimeoutOrNull(LOOKUP_MS) { runCatching { coverOf(albumId) }.getOrNull() }

	/**
	 * [key] as it is, unless it is an album page with no cover named (a Fresh tile, a link): then the
	 * cover is Room's, as for [open].
	 */
	fun openKey(key: NavKey, push: (NavKey) -> Unit) {
		if (key is Screen.CollectionDetail && key.coverArtId == null) open(key.collectionId, key.tab, key.openGaps, push)
		else push(key)
	}

	companion object {
		/** Longer than a Room read of one row, shorter than anyone waits for a tap. */
		const val LOOKUP_MS = 250L
	}
}

/** [AlbumPageOpener.openKey] onto the current back stack. */
@Composable
fun rememberKeyOpener(): (NavKey) -> Unit {
	val opener = koinInject<AlbumPageOpener>()
	val backStack = LocalNavStack.current
	return remember(opener, backStack) { { key -> opener.openKey(key) { backStack.add(it) } } }
}

/** [AlbumPageOpener] onto the current back stack: `open(albumId, tab, openGaps)`. */
@Composable
fun rememberAlbumPageOpener(): (albumId: String, tab: String, openGaps: Boolean) -> Unit {
	val opener = koinInject<AlbumPageOpener>()
	val backStack = LocalNavStack.current
	return remember(opener, backStack) {
		{ albumId, tab, openGaps -> opener.open(albumId, tab, openGaps) { backStack.add(it) } }
	}
}
