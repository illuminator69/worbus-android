package app.worbus.ui.screens.artist.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.worbus.data.database.entities.DownloadStatus
import app.worbus.ui.components.common.CoverArt
import app.worbus.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import app.worbus.ui.components.common.SongRowDefaults
import com.kyant.capsule.ContinuousCapsule
import org.jetbrains.compose.resources.stringResource
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.info_loading_page

/**
 * B-446 follow-up (the owner, 2026-11-14: each cold artist page "opens on a short loading screen"): what an
 * artist page with nothing to open on shows until its Room read is shown, once its slide has ended
 * (`FirstShowHold`). It replaced a centred spinner: the page's own shape, laid out as the page lays out, so
 * the content that cross-fades over it lands where its shapes were and nothing below the hero moves.
 *
 * - The hero at the page's height, on the hero's own colour (`surfaceContainer` of the page's scheme, the
 *   colour a hero shows under a photo still loading), with the artist photo when the opener named it
 *   ([coverArtId], `Screen.ArtistDetail.coverArtId`: search, the library's lists), and a bar where the name is.
 * - The action row itself, disabled ([ArtistActionButtons] with nothing to play).
 * - "Frequently played"'s heading as a bar and [SKELETON_ROWS] rows in the rows' own frosted tone and shapes.
 *
 * Every colour is the page's scheme (the bridge colours of the page it came from, B-404), never a grey.
 * Static: it is on screen for a slide and a Room read, and a pulse would be work in every frame of the slide.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ArtistPageSkeleton(
	contentPadding: PaddingValues,
	ambientColor: Color,
	coverArtId: String?,
	modifier: Modifier = Modifier
) {
	val layoutDirection = LocalLayoutDirection.current
	val bar = MaterialTheme.colorScheme.primary.copy(alpha = SKELETON_BAR_ALPHA)
	val loading = stringResource(Res.string.info_loading_page)
	Column(
		modifier = modifier
			.fillMaxSize()
			.semantics { contentDescription = loading },
		verticalArrangement = Arrangement.spacedBy(12.dp),
		horizontalAlignment = Alignment.CenterHorizontally
	) {
		// ArtistDetailScreenHeading's box: the same height, colour, image and gradient.
		BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.height(artistHeroHeight(maxWidth, contentPadding))
					.background(MaterialTheme.colorScheme.surfaceContainer)
			) {
				if (coverArtId != null) {
					CoverArt(
						coverArtId = coverArtId,
						modifier = Modifier.fillMaxSize(),
						shape = RectangleShape,
						square = false
					)
				}
				Box(
					modifier = Modifier
						.fillMaxSize()
						.background(
							Brush.linearGradient(
								0.025f to ambientColor,
								1.0f to Color.Transparent,
								start = Offset(0f, Float.POSITIVE_INFINITY),
								end = Offset(0f, 0f)
							)
						)
				)
				// Where the name is: its line (an empty Text in the name's style sets the height), at the heading's padding.
				Box(
					modifier = Modifier
						.align(Alignment.BottomStart)
						.fillMaxWidth()
						.padding(horizontal = 20.dp)
						.padding(start = contentPadding.calculateStartPadding(layoutDirection))
						.padding(end = contentPadding.calculateEndPadding(layoutDirection)),
					contentAlignment = Alignment.CenterStart
				) {
					Text(" ", style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold))
					Box(
						Modifier
							.fillMaxWidth(0.55f)
							.height(30.dp)
							.clip(ContinuousCapsule)
							.background(bar)
					)
				}
			}
		}
		ArtistActionButtons(
			onPlay = {},
			onShuffle = {},
			onDownload = {},
			onCancelDownload = {},
			onDeleteDownload = {},
			downloadStatus = DownloadStatus.NOT_DOWNLOADED,
			playEnabled = false,
			modifier = Modifier.padding(top = 8.dp)
		)
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(start = contentPadding.calculateStartPadding(layoutDirection))
				.padding(end = contentPadding.calculateEndPadding(layoutDirection))
		) {
			// PageSectionHeading's row: the same modifiers, and empty Texts in its two styles, so it is as tall as the
			// heading (its "See more" sets that), with a bar for the title.
			Row(
				modifier = Modifier
					.heightIn(min = 32.dp)
					.padding(top = 8.dp, bottom = 4.dp)
					.padding(horizontal = 20.dp)
					.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween
			) {
				Box(contentAlignment = Alignment.CenterStart) {
					Text(" ", style = MaterialTheme.typography.titleMediumEmphasized, fontWeight = FontWeight(600))
					Box(
						Modifier
							.width(140.dp)
							.height(16.dp)
							.clip(ContinuousCapsule)
							.background(bar)
					)
				}
				Text(" ", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
			}
			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				repeat(SKELETON_ROWS) { index ->
					Box(
						Modifier
							.padding(horizontal = 16.dp)
							.fillMaxWidth()
							.height(SKELETON_ROW_HEIGHT)
							.clip(segmentedShapes(index, SKELETON_ROWS).shape)
							.background(SongRowDefaults.frostedContainerColor())
					)
				}
			}
		}
	}
}

/** The artist hero's height ([ArtistDetailScreenHeading]): 400 dp at 300 dp wide, scaled, under the top inset. */
internal fun artistHeroHeight(maxWidth: Dp, contentPadding: PaddingValues): Dp =
	(400.dp / (maxWidth / 300.dp)) + contentPadding.calculateTopPadding()

/** As many rows as the page's Frequently played shows (`FREQUENT_ROWS`). */
internal const val SKELETON_ROWS = 5

/** A compact SongRow (a 50 dp cover in a two-line ListItem): 216 px at 3x on the VM AVD (uiautomator, 2026-11-14). */
internal val SKELETON_ROW_HEIGHT = 72.dp

private const val SKELETON_BAR_ALPHA = 0.18f
