@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.worbus.ui.screens.artist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.info_discography_shared
import worbus.composeapp.generated.resources.title_discography
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import app.worbus.di.LocalBottomBarScrollManager
import app.worbus.domain.manager.PreferenceManager
import app.worbus.domain.manager.LbBotManager
import app.worbus.domain.models.settings.BottomBarVisibilityMode
import app.worbus.ui.components.layouts.NestedTopBar
import app.worbus.ui.components.layouts.RootBottomBar
import app.worbus.ui.screens.artist.components.DiscographyFilters
import app.worbus.ui.screens.artist.components.discographyItems
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.remember
import app.worbus.ui.screens.artist.components.DiscographyScanAction
import app.worbus.ui.screens.artist.components.indexFreshnessLabel
import app.worbus.ui.screens.artist.viewmodels.ArtistDetailViewModel
import app.worbus.ui.core.UiState
import app.worbus.ui.util.OnPageSettled

/**
 * An owned artist's whole discography (P4 round 2), behind the chips the owner's mockups draw: type
 * chips with counts, the ownership menu, the sort and a rescan. The artist page shows a few releases
 * and "See discography" opens this; it keeps the page short, which is what the chips are for.
 *
 * Its own view model instance (a back-stack entry has its own store), reading the same Room mirror
 * the artist page does, so it opens on what that page showed. Without lb-bot it lists the library's
 * albums, with no pills, since each would say "Owned".
 */
@Composable
fun ArtistDiscographyScreen(artistId: String) {
	val viewModel = koinViewModel<ArtistDetailViewModel>(
		key = artistId,
		parameters = { parametersOf(artistId) }
	)
	val preferenceManager = koinInject<PreferenceManager>()
	val artistState by viewModel.artistState.collectAsStateWithLifecycle()
	val discography by viewModel.discography.collectAsStateWithLifecycle()
	val artistMbid by viewModel.artistMbid.collectAsStateWithLifecycle()
	val downloads by koinInject<LbBotManager>().downloads.collectAsStateWithLifecycle()
	val state = artistState.data
	val fromIndex = discography.available && discography.indexed && discography.sections.isNotEmpty()
	val sections = if (fromIndex) discography.sections else ownedSections(state?.albums.orEmpty())
	var filter by rememberSaveable(stateSaver = FilterSaver) { mutableStateOf(DiscographyFilter()) }
	val view = remember(sections, filter) { discographyView(sections, filter) }
	val types = remember(sections) { entryTypes(sections) }
	val ledger by koinInject<LbBotManager>().ledger.collectAsStateWithLifecycle()
	// Review M2, M3, m2: the arrival and the open groups, saved with the page and started over per filter.
	// B-405: rows the page has on its first frame (it opened on the artist page's state, ArtistPageMemory)
	// slide in with the page; only rows that come later play the arrival.
	val presentAtOpen = remember { viewModel.artistState.value is UiState.Success }
	val memory = rememberDiscographyListMemory(filter, view, presentAtOpen)
	val open = rememberDiscographyOpener(viewModel, artistId)
	// B-405: the page reads Room again once it has slid in, not during the slide.
	OnPageSettled { viewModel.pageSettled() }

	Scaffold(
		topBar = {
			NestedTopBar(
				title = {
					Column {
						Text(stringResource(Res.string.title_discography), maxLines = 1)
						// "Moth Season · last scanned 2 days ago", the mockup's line under the title.
						val freshness = if (fromIndex) indexFreshnessLabel(discography) else null
						val line = listOfNotNull(state?.artist?.name, freshness).joinToString(" · ")
						if (line.isNotBlank()) {
							Text(
								line,
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis
							)
						}
					}
				}
			)
		},
		bottomBar = {
			val scrollManager = LocalBottomBarScrollManager.current
			if (preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens) {
				RootBottomBar(scrolled = scrollManager.isTriggered)
			}
		}
	) { innerPadding ->
		if (state == null) {
			Box(Modifier.fillMaxSize()) {
				ContainedLoadingIndicator(Modifier.size(80.dp).align(Alignment.Center))
			}
			return@Scaffold
		}
		// B-405: one lazy list, so only the rows on screen are composed. It was a scrolling Column holding
		// every group's first 40 rows (DiscographyBrowser), all composed in the first frame of the page's
		// transition: 300 to 850 ms on the VM AVD's release build for an artist of ~300 releases, the
		// stutter the owner saw as the page opened.
		LazyColumn(
			modifier = Modifier.fillMaxSize(),
			contentPadding = PaddingValues(
				top = innerPadding.calculateTopPadding() + 4.dp,
				bottom = innerPadding.calculateBottomPadding() + 24.dp
			)
		) {
			item(key = "filters") {
				DiscographyFilters(
					sections = sections,
					filter = filter,
					onFilter = { filter = it },
					// The rescan chip once there is an index; before that the scan action below offers it.
					onRescan = if (fromIndex && artistMbid.effective.isNotBlank()) {
						{ viewModel.indexArtist() }
					} else null,
					rescanning = discography.indexing,
					modifier = Modifier.padding(bottom = 12.dp)
				)
			}
			discographyItems(
				view = view,
				filter = filter,
				types = types,
				onOpenEntry = open,
				onLongClick = null,
				ledger = ledger,
				gate = memory.gate,
				expanded = memory.expanded,
				onExpand = { memory.expanded = memory.expanded + it },
				pills = fromIndex
			)
			// A rescan lb-bot answered "another artist holds this discography", or one that failed:
			// said under the list, as plain lines.
			if (fromIndex && discography.scanShared) item(key = "shared") {
				Text(
					stringResource(Res.string.info_discography_shared),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					textAlign = TextAlign.Center,
					modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
				)
			}
			if (fromIndex && discography.scanError.isNotBlank()) item(key = "scan-error") {
				Text(
					discography.scanError,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.error,
					modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
				)
			}
			if (!fromIndex) item(key = "scan-action") {
				DiscographyScanAction(
					ui = discography,
					mbid = artistMbid.effective,
					scanByNdId = scanByNdIdOffered(downloads, discography.ndIdScanRefused),
					onIndex = { viewModel.indexArtist() }
				)
			}
		}
	}
}

/** The filter survives a rotation and coming back to the page. */
internal val FilterSaver = listSaver<DiscographyFilter, String>(
	save = { listOf(it.type.orEmpty(), it.ownership.name, it.sort.name) },
	restore = { saved ->
		DiscographyFilter(
			type = saved[0].ifBlank { null },
			ownership = Ownership.entries.firstOrNull { it.name == saved[1] } ?: Ownership.ALL,
			sort = DiscographySort.entries.firstOrNull { it.name == saved[2] } ?: DiscographySort.MOST_PLAYED
		)
	}
)
