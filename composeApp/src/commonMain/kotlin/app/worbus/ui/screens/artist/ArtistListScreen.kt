package app.worbus.ui.screens.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.count_albums
import worbus.composeapp.generated.resources.title_artists
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import app.worbus.di.LocalBottomBarScrollManager
import app.worbus.di.LocalNavStack
import app.worbus.di.LocalPlatformContext
import app.worbus.di.isLandscape
import app.worbus.domain.manager.PreferenceManager
import app.worbus.domain.models.DomainAlbum
import app.worbus.domain.models.DomainArtist
import app.worbus.domain.models.DomainArtistListType
import app.worbus.domain.models.settings.BottomBarVisibilityMode
import app.worbus.shared.MediaPlayerViewModel
import app.worbus.ui.components.layouts.ArtGridItem
import app.worbus.ui.components.layouts.NestedTopBar
import app.worbus.ui.components.layouts.PullToRefreshBox
import app.worbus.ui.components.layouts.RootBottomBar
import app.worbus.ui.components.layouts.RootTopBar
import app.worbus.ui.components.sheets.ArtistSheet
import app.worbus.ui.components.snackbars.ErrorSnackBar
import app.worbus.ui.core.UiState
import app.worbus.ui.navigation.PersistentViewModelStoreOwner
import app.worbus.ui.navigation.Screen
import app.worbus.ui.screens.artist.components.ArtistListScreenContent
import app.worbus.ui.screens.artist.components.ArtistListScreenSortButton
import app.worbus.ui.screens.artist.viewmodels.ArtistListViewModel
import app.worbus.ui.screens.playlist.dialogs.PlaylistUpdateDialog
import app.worbus.ui.util.withoutTop
import app.worbus.ui.viewmodel.RootViewModel

/** The artist list's view model, by the store-owner rule (see `albumListViewModel`). */
@Composable
fun artistListViewModel(nested: Boolean, listType: DomainArtistListType): ArtistListViewModel =
	koinViewModel(
		key = listType.toString(),
		parameters = { parametersOf(listType) },
		viewModelStoreOwner = if (nested) {
			LocalViewModelStoreOwner.current!!
		} else {
			koinInject<PersistentViewModelStoreOwner>()
		}
	)

@Composable
fun ArtistListScreen(
	nested: Boolean = false,
	listType: DomainArtistListType
) {
	val platformContext = LocalPlatformContext.current
	val preferenceManager = koinInject<PreferenceManager>()
	val viewModel = artistListViewModel(nested, listType)
	val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

	val actions: @Composable RowScope.() -> Unit = {
		ArtistListSortAction(viewModel, nested)
	}

	Scaffold(
		topBar = {
			if (!nested) {
				RootTopBar(
					{ Text(stringResource(Res.string.title_artists)) },
					scrollBehavior,
					actions
				)
			} else {
				NestedTopBar({ Text(stringResource(Res.string.title_artists)) }, actions = actions)
			}
		},
		bottomBar = {
			val scrollManager = LocalBottomBarScrollManager.current
			val preferVisible = preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens
			if (!nested || (!platformContext.isLandscape() && preferVisible)) {
				RootBottomBar(scrolled = scrollManager.isTriggered)
			}
		}
	) { innerPadding ->
		ArtistListBody(
			viewModel = viewModel,
			contentPadding = innerPadding,
			scrollConnection = if (!nested) scrollBehavior.nestedScrollConnection else null
		)
	}
}

/** The artist list's sort menu (with its filters and the list / grid setting). */
@Composable
fun ArtistListSortAction(viewModel: ArtistListViewModel, nested: Boolean) {
	val preferenceManager = koinInject<PreferenceManager>()
	val selectedSorting by viewModel.listType.collectAsState()
	val selectedFilters by viewModel.selectedFilters.collectAsState()
	ArtistListScreenSortButton(
		nested = nested,
		selectedSorting = selectedSorting,
		onSetSorting = { viewModel.setListType(it) },
		selectedViewMode = preferenceManager.artistListViewMode,
		onSetViewMode = { preferenceManager.artistListViewMode = it },
		selectedFilters = selectedFilters,
		onToggleFilter = { viewModel.toggleFilter(it) }
	)
}

/** The artist list itself (P5): hosted by [ArtistListScreen] and by the Library's Artists chip. */
@Composable
fun ArtistListBody(
	viewModel: ArtistListViewModel,
	contentPadding: PaddingValues,
	modifier: Modifier = Modifier,
	scrollConnection: NestedScrollConnection? = null,
	/** The "N artists" line over the list; the Library's sort row says it instead (P5 round 2). */
	showCount: Boolean = true
) {
	val preferenceManager = koinInject<PreferenceManager>()
	val selectedViewMode = preferenceManager.artistListViewMode
	val artistsState by viewModel.artistsState.collectAsState()
	val selectedArtist by viewModel.selectedArtist.collectAsState()
	val selectedArtistAlbums by viewModel.selectedArtistAlbums.collectAsState()
	val selectedSorting by viewModel.listType.collectAsState()
	val starred by viewModel.starred.collectAsState()
	val player = koinInject<MediaPlayerViewModel>()

	val rootViewModel = koinViewModel<RootViewModel>()
	LaunchedEffect(Unit) {
		rootViewModel.events.collect { event ->
			if (event is RootViewModel.Event.ScrollToTop) {
				viewModel.gridState.animateScrollToItem(0)
			}
		}
	}

	PullToRefreshBox(
		modifier = modifier
			.padding(top = contentPadding.calculateTopPadding()),
		finished = artistsState !is UiState.Loading,
		onRefresh = { viewModel.refreshArtists(true) },
		key = artistsState
	) {
		ArtistListScreenContent(
			state = artistsState,
			starred = starred,
			selectedSorting = selectedSorting,
			selectedArtist = selectedArtist,
			selectedArtistAlbums = selectedArtistAlbums,
			selectedViewMode = selectedViewMode,
			gridState = viewModel.gridState,
			scrollConnection = scrollConnection,
			innerPadding = contentPadding,
			showCount = showCount,
			onUpdateSelection = { viewModel.selectArtist(it) },
			onClearSelection = { viewModel.clearSelection() },
			onSetStarred = { viewModel.starArtist(it) },
			onPlayNext = { viewModel.playArtistAlbumsNext(player) },
			onAddToQueue = { viewModel.addArtistAlbumsToQueue(player) }
		)
	}

	ErrorSnackBar(
		error = (artistsState as? UiState.Error)?.error,
		onClearError = { viewModel.clearError() }
	)
}

@Composable
fun ArtistListScreenGridItem(
	modifier: Modifier = Modifier,
	tab: String,
	artist: DomainArtist,
	selected: Boolean,
	selectedArtistAlbums: ImmutableList<DomainAlbum>?,
	starred: Boolean,
	onSelect: () -> Unit,
	onDeselect: () -> Unit,
	onPlayNext: () -> Unit,
	onAddToQueue: () -> Unit,
	onSetStarred: (starred: Boolean) -> Unit
) {
	val backStack = LocalNavStack.current

	var playlistDialogShown by rememberSaveable { mutableStateOf(false) }

	Box(modifier) {
		ArtGridItem(
			onClick = dropUnlessResumed {
				backStack.add(Screen.ArtistDetail(artist.id))
			},
			onLongClick = onSelect,
			coverArtId = artist.coverArtId,
			title = artist.name,
			isArtist = true,
			subtitle = pluralStringResource(
				Res.plurals.count_albums,
				artist.albumCount,
				artist.albumCount
			),
			id = artist.id,
			tab = tab
		)
		if (selected) {
			ArtistSheet(
				onDismissRequest = onDeselect,
				artist = artist,
				onPlayNext = onPlayNext,
				onAddToQueue = onAddToQueue,
				onAddAllToPlaylist = { playlistDialogShown = true },
				starred = starred,
				onSetStarred = { onSetStarred(!starred) }
			)
		}
		if (playlistDialogShown) {
			PlaylistUpdateDialog(
				songs = selectedArtistAlbums?.flatMap { it.songs }.orEmpty().toPersistentList(),
				onDismissRequest = { playlistDialogShown = false }
			)
		}
	}
}
