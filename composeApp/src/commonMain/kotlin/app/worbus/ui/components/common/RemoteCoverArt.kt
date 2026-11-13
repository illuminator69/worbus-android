package app.worbus.ui.components.common

import app.worbus.ui.util.motionMs
import app.worbus.ui.util.COVER_FADE_MS
import app.worbus.ui.util.crossfadeMsFor
import app.worbus.ui.util.rememberReducedMotion
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import coil3.compose.LocalPlatformContext as LocalCoilPlatformContext
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koin.compose.koinInject
import app.worbus.domain.manager.PreferenceManager
import app.worbus.ui.util.placeholderBrush

/**
 * Cover art from a third-party host, for a release the library does not have.
 *
 * Separate from [CoverArt], which signs a Navidrome cover id into a URL. The user's custom
 * headers (a reverse proxy's auth, a Cloudflare Access token) are added by the image loader's one
 * client, and only toward the Navidrome they were set for (`navidromeHeadersFor`, B-363): a cover
 * on coverartarchive.org gets none, whichever composable asks.
 *
 * A miss is the normal case — plenty of obscure release-groups have no art in the
 * Archive — so both the empty and the failed states render the same plain
 * placeholder box the null branch of [CoverArt] does. A broken-image icon here
 * would be noise, not information.
 */
@Composable
fun RemoteCoverArt(
	modifier: Modifier = Modifier,
	url: String?,
	contentDescription: String? = null,
	onClick: (() -> Unit)? = null,
	onLongClick: (() -> Unit)? = null,
	square: Boolean = true,
	crossfadeMs: Int = COVER_FADE_MS,
	shape: Shape? = null,
	/** The card's own, so a press anywhere on the card ripples the cover (as `CoverArt`). */
	interactionSource: MutableInteractionSource? = null,
	/** navi-connect P1: the box behind the image is this name's tinted gradient (as `CoverArt`'s). */
	placeholderName: String? = null
) {
	val preferenceManager = koinInject<PreferenceManager>()
	val resolvedShape = shape ?: preferenceManager.coverArtShape.shape
	val coilPlatformContext = LocalCoilPlatformContext.current

	val fadeMs = crossfadeMsFor(rememberReducedMotion(), motionMs(crossfadeMs))
	val model = remember(url, fadeMs) {
		ImageRequest.Builder(coilPlatformContext)
			.data(url)
			.memoryCacheKey(url)
			.diskCacheKey(url)
			.diskCachePolicy(CachePolicy.ENABLED)
			.memoryCachePolicy(CachePolicy.ENABLED)
			.crossfade(fadeMs)
			.build()
	}

	val tint = remember(placeholderName) { placeholderName?.let { placeholderBrush(it) } }
	val boxColor = MaterialTheme.colorScheme.surfaceContainer

	val commonModifier = modifier
		.then(if (square) Modifier.aspectRatio(1f) else Modifier)
		.clip(resolvedShape)
		.then(if (tint != null) Modifier.background(tint) else Modifier.background(boxColor))
		.then(
			if (onClick != null)
				Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
			else Modifier
		)
		.then(
			if (interactionSource != null) Modifier.indication(interactionSource, ripple())
			else Modifier
		)

	if (url.isNullOrBlank()) return Box(commonModifier)
	// AsyncImage (row 12): a failure draws nothing over the placeholder box, as the empty error
	// slot did, with no subcomposition per tile.
	AsyncImage(
		model = model,
		contentDescription = contentDescription,
		modifier = commonModifier,
		contentScale = ContentScale.Crop
	)
}
