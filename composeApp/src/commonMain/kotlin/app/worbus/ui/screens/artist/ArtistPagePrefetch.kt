package app.worbus.ui.screens.artist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import app.worbus.domain.manager.SessionManager
import app.worbus.ui.screens.artist.viewmodels.ArtistPagePrefetcher
import app.worbus.ui.util.CoverSeedPrefetcher
import app.worbus.ui.util.OnPageSettled
import app.worbus.util.CoverPlaceholder
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import coil3.compose.LocalPlatformContext as LocalCoilPlatformContext

/**
 * B-446 follow-up: after this page's own slide has ended and its content is shown ([contentShown]), the
 * page of [artistId] is read ahead ([ArtistPagePrefetcher]) so the link to it opens complete in its first
 * frame, as a revisit does. Then what that page's first frame asks of the network anyway, ahead of time:
 * the artist photo's colours ([CoverSeedPrefetcher], so the page is coloured in its first frame) and the
 * photo itself, decoded into Coil's memory under the key the hero and the backdrop read it by (so it is not
 * fetched and decoded during the artist page's slide, the work B-446 measured competing with it).
 *
 * Nothing until the slide has ended: the album page's own first frames never share the CPU with this.
 */
@Composable
fun PrefetchArtistPage(artistId: String?, contentShown: Boolean) {
	val prefetcher = koinInject<ArtistPagePrefetcher>()
	val seeds = koinInject<CoverSeedPrefetcher>()
	val imageLoader = koinInject<ImageLoader>()
	val sessionManager = koinInject<SessionManager>()
	val coilContext = LocalCoilPlatformContext.current
	val photoPx = LocalWindowInfo.current.containerSize.width.coerceAtLeast(1)
	var settled by remember { mutableStateOf(false) }
	OnPageSettled { settled = true }
	LaunchedEffect(artistId, settled, contentShown) {
		if (artistId.isNullOrBlank() || !settled || !contentShown) return@LaunchedEffect
		delay(ARTIST_PREFETCH_AFTER_SETTLE_MS)
		val snapshot = prefetcher.warm(artistId) ?: return@LaunchedEffect
		val photo = snapshot.state.artist.coverArtId
		if (photo.isNullOrBlank() || CoverPlaceholder.isPlaceholder(photo)) return@LaunchedEffect
		seeds.request(photo)
		imageLoader.enqueue(
			ImageRequest.Builder(coilContext)
				.data(sessionManager.getCoverArtUrl(photo))
				.memoryCacheKey(photo)
				.diskCacheKey(photo)
				.diskCachePolicy(CachePolicy.ENABLED)
				.memoryCachePolicy(CachePolicy.ENABLED)
				// As wide as the window: the hero is drawn at its width, the backdrop far smaller.
				.size(photoPx, photoPx)
				.build()
		)
	}
}

/** After the album page has settled: a beat for its last arrivals (the cover's fade, the wash) to land first. */
internal const val ARTIST_PREFETCH_AFTER_SETTLE_MS = 150L
