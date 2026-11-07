package app.worbus.ui.screens.radio

import app.worbus.di.isLandscape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFloatingActionButton
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.title_create_playlist
import worbus.composeapp.generated.resources.title_radios
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import app.worbus.di.LocalBottomBarScrollManager
import app.worbus.di.LocalPlatformContext
import app.worbus.domain.manager.PreferenceManager
import app.worbus.domain.models.settings.BottomBarCollapseMode
import app.worbus.domain.models.settings.BottomBarVisibilityMode
import app.worbus.icons.Icons
import app.worbus.icons.outlined.Add
import app.worbus.shared.MediaPlayerViewModel
import app.worbus.ui.components.layouts.ArtGrid
import app.worbus.ui.components.layouts.NestedTopBar
import app.worbus.ui.components.layouts.PullToRefreshBox
import app.worbus.ui.components.layouts.RootBottomBar
import app.worbus.ui.components.layouts.RootTopBar
import app.worbus.ui.components.snackbars.ErrorSnackBar
import app.worbus.ui.core.UiState
import app.worbus.ui.navigation.PersistentViewModelStoreOwner
import app.worbus.ui.screens.radio.components.radioListScreenContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.plus
import androidx.compose.runtime.remember
import worbus.composeapp.generated.resources.action_cancel
import worbus.composeapp.generated.resources.action_delete
import worbus.composeapp.generated.resources.title_delete_radio
import app.worbus.domain.models.DomainRadio
import app.worbus.domain.models.settings.ListViewMode
import app.worbus.icons.outlined.Delete
import app.worbus.ui.components.common.SegmentedListButton
import app.worbus.ui.components.common.SegmentedListButtonDefaults
import app.worbus.ui.components.common.SegmentedListItemDefaults
import app.worbus.ui.components.dialogs.FormDialog
import app.worbus.ui.screens.radio.components.RadioSheet
import app.worbus.ui.screens.radio.dialogs.RadioCreateDialog
import app.worbus.ui.screens.radio.viewmodels.RadioListViewModel
import app.worbus.ui.util.withoutTop
import app.worbus.ui.viewmodel.RootViewModel

/** The internet-radio list's view model, by the store-owner rule (see `albumListViewModel`). */
@Composable
fun radioListViewModel(nested: Boolean): RadioListViewModel =
	koinViewModel(
		viewModelStoreOwner = if (nested) {
			LocalViewModelStoreOwner.current!!
		} else {
			koinInject<PersistentViewModelStoreOwner>()
		}
	)

@Composable
fun RadioListScreen(
	nested: Boolean
) {
	val platformContext = LocalPlatformContext.current
	val scrollManager = LocalBottomBarScrollManager.current
	val viewModel = radioListViewModel(nested)
	val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
	val slideSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
	val scaleInSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
	var createDialogShown by rememberSaveable { mutableStateOf(false) }
	val preferenceManager = koinInject<PreferenceManager>()

	Scaffold(
		topBar = {
			if (!nested) {
				RootTopBar(
					{ Text(stringResource(Res.string.title_radios)) },
					scrollBehavior
				)
			} else {
				NestedTopBar({ Text(stringResource(Res.string.title_radios)) })
			}
		},
		floatingActionButton = {
			AnimatedContent(
				!scrollManager.isTriggered
					|| preferenceManager.bottomBarCollapseMode == BottomBarCollapseMode.Never,
				transitionSpec = {
					val transformOrigin = TransformOrigin(0f, 1f)
					(slideInHorizontally(slideSpec) { it / 2 }
						+ scaleIn(scaleInSpec, transformOrigin = transformOrigin)
						+ slideInVertically(slideSpec) { it / 2 })
						.togetherWith(slideOutHorizontally(slideSpec) { it / 2 }
							+ scaleOut(transformOrigin = transformOrigin)
							+ slideOutVertically(slideSpec) { it / 2 })
						.using(SizeTransform(clip = false))
				}
			) { notScrolled ->
				if (notScrolled) {
					MediumFloatingActionButton(
						shape = MaterialTheme.shapes.large,
						containerColor = MaterialTheme.colorScheme.primary,
						onClick = {
							createDialogShown = true
						}
					) {
						Icon(
							imageVector = Icons.Outlined.Add,
							contentDescription = stringResource(Res.string.title_create_playlist),
							modifier = Modifier.size(26.dp)
						)
					}
				}
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
		RadioListBody(
			viewModel = viewModel,
			contentPadding = innerPadding,
			scrollConnection = if (!nested) scrollBehavior.nestedScrollConnection else null,
			createDialogShown = createDialogShown,
			onCreateDialogDismissed = { createDialogShown = false }
		)
	}
}

/**
 * The internet-radio list itself (P5): hosted by [RadioListScreen] and by the Library's Radios
 * chip. The add dialog is here, opened by the host ([createDialogShown]): the page's button, the
 * Library's "Add" in the sort row.
 */
@Composable
fun RadioListBody(
	viewModel: RadioListViewModel,
	contentPadding: PaddingValues,
	modifier: Modifier = Modifier,
	scrollConnection: NestedScrollConnection? = null,
	createDialogShown: Boolean = false,
	onCreateDialogDismissed: () -> Unit = {}
) {
	val player = koinInject<MediaPlayerViewModel>()
	val radiosState by viewModel.radiosState.collectAsState()
	var sheetTarget by remember { mutableStateOf<DomainRadio?>(null) }
	var editing by remember { mutableStateOf<DomainRadio?>(null) }
	var deleting by remember { mutableStateOf<DomainRadio?>(null) }

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
		finished = radiosState !is UiState.Loading,
		onRefresh = { viewModel.refreshRadios(true) },
		key = radiosState
	) {
		// The Library's rows (P5 round 2), one column, 3 dp apart, in place of the coloured cards.
		ArtGrid(
			modifier = if (scrollConnection != null)
				Modifier.nestedScroll(scrollConnection)
			else Modifier,
			contentPadding = contentPadding.withoutTop() + PaddingValues(top = 4.dp, bottom = 24.dp),
			state = viewModel.gridState,
			verticalArrangement = if ((radiosState as? UiState.Success)?.data?.isEmpty() == true)
				Arrangement.Center
			else Arrangement.spacedBy(SegmentedListItemDefaults.SegmentedGap),
			selectedViewMode = ListViewMode.List
		) {
			radioListScreenContent(
				state = radiosState,
				onRadioClick = { radio ->
					player.playRadio(radio)
				},
				onRadioMenu = { sheetTarget = it }
			)
		}
	}

	sheetTarget?.let { radio ->
		RadioSheet(
			radio = radio,
			onDismissRequest = { sheetTarget = null },
			onEdit = { editing = radio },
			onDelete = { deleting = radio }
		)
	}

	editing?.let { radio ->
		RadioCreateDialog(
			onDismissRequest = { editing = null },
			onRefresh = { viewModel.refreshRadios(true) },
			editing = radio
		)
	}

	deleting?.let { radio ->
		FormDialog(
			onDismissRequest = { deleting = null },
			icon = { Icon(Icons.Outlined.Delete, null) },
			title = { Text(stringResource(Res.string.title_delete_radio)) },
			buttons = {
				SegmentedListButton(
					modifier = Modifier.fillMaxWidth(),
					onClick = {
						viewModel.delete(radio)
						deleting = null
					},
					shapes = SegmentedListButtonDefaults.shapes(index = 0, count = 2),
					colors = SegmentedListButtonDefaults.errorColors()
				) { Text(stringResource(Res.string.action_delete)) }
				SegmentedListButton(
					modifier = Modifier.fillMaxWidth(),
					onClick = { deleting = null },
					shapes = SegmentedListButtonDefaults.shapes(index = 1, count = 2)
				) { Text(stringResource(Res.string.action_cancel)) }
			},
			content = { Text(radio.name) }
		)
	}

	ErrorSnackBar(
		error = (radiosState as? UiState.Error)?.error,
		onClearError = { viewModel.clearError() }
	)

	if (createDialogShown) {
		RadioCreateDialog(
			onDismissRequest = onCreateDialogDismissed,
			onRefresh = { viewModel.refreshRadios(true) }
		)
	}
}
