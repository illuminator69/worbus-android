package app.worbus.ui.screens.nowPlaying

import app.worbus.di.isLandscape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import kotlinx.coroutines.launch
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.action_lyrics
import worbus.composeapp.generated.resources.action_navigate_back
import worbus.composeapp.generated.resources.action_queue
import worbus.composeapp.generated.resources.title_now_playing
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import app.worbus.di.LocalNavStack
import app.worbus.di.LocalSheetState
import app.worbus.domain.manager.PreferenceManager
import app.worbus.domain.models.settings.NowPlayingBackgroundStyle
import app.worbus.domain.models.settings.ToolbarPosition
import app.worbus.icons.Icons
import app.worbus.icons.outlined.KeyboardArrowDown
import app.worbus.icons.outlined.List
import app.worbus.icons.outlined.Lyrics
import app.worbus.icons.outlined.Radio
import app.worbus.ui.components.sheets.DevicePickerSheet
import app.worbus.shared.MediaPlayerViewModel
import app.worbus.ui.components.common.BlendBackground
import app.worbus.ui.components.layouts.SheetScaffold
import app.worbus.ui.components.layouts.TopBarButton
import app.worbus.ui.components.toolbars.SheetActionButton
import app.worbus.ui.components.toolbars.SheetToolbar
import app.worbus.ui.navigation.Screen
import app.worbus.ui.screens.nowPlaying.components.controls.NowPlayingArtworkPager
import app.worbus.ui.screens.nowPlaying.components.rows.NowPlayingControlsRow
import app.worbus.ui.screens.nowPlaying.viewmodels.NowPlayingViewModel
import app.worbus.ui.screens.nowPlaying.staged.StagedNowPlaying
import app.worbus.ui.screens.nowPlaying.staged.stagedPlayerFits
import app.worbus.ui.navigation.rememberCoveredBySheet

/**
 * The player. A portrait phone (narrower than 600 dp, taller than wide) gets the staged player with
 * the queue as a layer inside it (P2, [StagedNowPlaying]); landscape and wider windows keep this one,
 * with its toolbar and the queue as a sheet of its own (`Screen.Queue`). A rotation while the layer
 * is open drops to this layout, and the layer's place is not carried back.
 */
@Composable
fun NowPlayingScreen() {
	BoxWithConstraints(Modifier.fillMaxSize()) {
		if (stagedPlayerFits(maxWidth.value, maxHeight.value)) StagedNowPlaying()
		else LegacyNowPlaying()
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegacyNowPlaying() {
	val preferenceManager = koinInject<PreferenceManager>()
	val player = koinInject<MediaPlayerViewModel>()
	val backStack = LocalNavStack.current


	val currentScreen = backStack.lastOrNull()
	val isPlayerCurrent = currentScreen is Screen.NowPlaying
		|| currentScreen is Screen.Queue
		|| currentScreen is Screen.PlaybackSpeed
		|| currentScreen is Screen.SongDetailSheet

	var showDevicePicker by remember { mutableStateOf(false) }
	val coveredBySheet = rememberCoveredBySheet()

	// The player UI mirrors the remote session directly when another
	// navi-connect device is active (MediaPlayerViewModel.uiState blends it in),
	// so this screen no longer needs to minimize itself on a handoff.
	val playerState by player.steadyState.collectAsStateWithLifecycle()
	val song = playerState.currentSong

	val viewModel = koinViewModel<NowPlayingViewModel> { parametersOf(player) }
	val songIsStarred by viewModel.songIsStarred.collectAsStateWithLifecycle()
	val songRating by viewModel.songRating.collectAsStateWithLifecycle()

	val sheetState = LocalSheetState.current
	val closeScope = rememberCoroutineScope()
	val animateToDismiss = dropUnlessResumed {
		closeScope.launch {
			sheetState.hide()
		}.invokeOnCompletion {
			if (!sheetState.isVisible) {
				backStack.remove(Screen.NowPlaying)
			}
		}
	}

	SheetScaffold(
		toolbar = { windowInsets ->
			SheetToolbar(
				modifier = Modifier.alpha(if (isPlayerCurrent) 1f else 0f),
				windowInsets = windowInsets,
				title = {
					Text(stringResource(Res.string.title_now_playing))
				},
				navigationIcon = {
					TopBarButton(
						onClick = animateToDismiss,
						content = {
							Icon(
								imageVector = Icons.Outlined.KeyboardArrowDown,
								contentDescription = stringResource(Res.string.action_navigate_back)
							)
						}
					)
				},
				actions = {
					SheetActionButton(
						icon = Icons.Outlined.Lyrics,
						contentDescription = stringResource(Res.string.action_lyrics),
						onClick = dropUnlessResumed { backStack.add(Screen.Lyrics) },
						isStartRounded = true
					)
					SheetActionButton(
						icon = Icons.Outlined.List,
						contentDescription = stringResource(Res.string.action_queue),
						onClick = dropUnlessResumed { backStack.add(Screen.Queue) }
					)
					SheetActionButton(
						icon = Icons.Outlined.Radio,
						contentDescription = "Connect to a device",
						onClick = { showDevicePicker = true },
						isEndRounded = true
					)
				}
			)
		}
	) { contentPadding ->
		Box(Modifier.fillMaxSize()) {
			if (preferenceManager.nowPlayingBackgroundStyle
				== NowPlayingBackgroundStyle.Dynamic
			) {
				BlendBackground(
					coverArtId = song?.coverArtId,
					// Held while the queue covers the whole screen: nobody sees it rotate, and
					// the 80 dp blur redrawn every frame took the queue's frames (SheetCover).
					isPaused = playerState.isPaused || coveredBySheet
				)
			}
				if (!isPlayerCurrent) return@Box
			BoxWithConstraints(
				modifier = Modifier
					.padding(horizontal = 8.dp)
					.fillMaxSize()
			) {
				val isLandscape = maxWidth > maxHeight
				val toolbarPosition = preferenceManager.nowPlayingToolbarPosition
				val padding = when {
					isLandscape -> contentPadding
					toolbarPosition == ToolbarPosition.Top -> contentPadding.plus(
						PaddingValues(
							bottom = 40.dp
						)
					)

					toolbarPosition == ToolbarPosition.Bottom -> contentPadding.plus(
						PaddingValues(
							top = 40.dp
						)
					)

					else -> contentPadding
				}
				if (isLandscape) {
					Row(
						modifier = Modifier.fillMaxSize().padding(padding),
						horizontalArrangement = Arrangement.SpaceEvenly,
						verticalAlignment = Alignment.CenterVertically
					) {
						NowPlayingArtworkPager(
							modifier = Modifier.weight(1f).fillMaxHeight(),
							isLandscape = true
						)
						NowPlayingControlsRow(
							modifier = Modifier.weight(1f).fillMaxHeight(),
							isLandscape = true,
							songIsStarred = songIsStarred,
							onSetSongIsStarred = { viewModel.starSong(it) },
							songRating = songRating,
							onSetSongRating = { viewModel.rateSong(it) }
						)
					}
				} else {
					Column(
						modifier = Modifier.fillMaxSize().padding(padding),
						horizontalAlignment = Alignment.CenterHorizontally,
						verticalArrangement = Arrangement.Center
					) {
						NowPlayingArtworkPager(
							modifier = Modifier.weight(1f).fillMaxWidth(),
							isLandscape = false
						)
						NowPlayingControlsRow(
							modifier = Modifier.weight(1f),
							isLandscape = false,
							songIsStarred = songIsStarred,
							onSetSongIsStarred = { viewModel.starSong(it) },
							songRating = songRating,
							onSetSongRating = { viewModel.rateSong(it) }
						)
					}
				}
			}
		}
	}

	if (showDevicePicker) {
		DevicePickerSheet(onDismissRequest = { showDevicePicker = false })
	}
}
