package app.worbus.ui.screens.playlist.dialogs

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.action_cancel
import worbus.composeapp.generated.resources.action_ok
import worbus.composeapp.generated.resources.action_smart_playlist
import worbus.composeapp.generated.resources.option_playlist_name
import worbus.composeapp.generated.resources.title_create_playlist
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import app.worbus.di.LocalNavStack
import app.worbus.domain.models.DomainPlaylist
import app.worbus.domain.models.DomainSong
import app.worbus.icons.Icons
import app.worbus.icons.outlined.PlaylistAdd
import app.worbus.ui.components.common.SegmentedListButton
import app.worbus.ui.components.common.SegmentedListButtonDefaults
import app.worbus.ui.components.dialogs.FormDialog
import app.worbus.ui.core.UiState
import app.worbus.ui.navigation.Screen
import app.worbus.ui.screens.playlist.viewmodels.PlaylistCreateDialogViewModel

@Composable
fun PlaylistCreateDialog(
	onDismissRequest: () -> Unit,
	onRefresh: () -> Unit,
	songs: ImmutableList<DomainSong> = persistentListOf(),
	navigateAfterwards: Boolean = true,
	onSmartRequested: (() -> Unit)? = null,
	/** navi-connect P2: the playlist just made, for a caller that shows it (the player's playlist sheet). */
	onCreated: ((DomainPlaylist) -> Unit)? = null,
	/** The name the field starts with (P5 round 2: a mix saved as a playlist is offered its own). */
	initialName: String = ""
) {
	val buttonCount = if (onSmartRequested != null) 3 else 2
	val viewModel = koinViewModel<PlaylistCreateDialogViewModel>(
		key = songs.joinToString { it.id },
		parameters = { parametersOf(songs) }
	)
	val backStack = LocalNavStack.current
	val state by viewModel.creationState.collectAsStateWithLifecycle()
	LaunchedEffect(initialName) {
		if (initialName.isNotBlank() && viewModel.name.text.isEmpty()) viewModel.name.setTextAndPlaceCursorAtEnd(initialName)
	}

	LaunchedEffect(Unit) {
		viewModel.events.collect { event ->
			when (event) {
				is PlaylistCreateDialogViewModel.Event.Dismiss -> {
					onCreated?.invoke(event.playlist)
					onDismissRequest()
					onRefresh()
					if (navigateAfterwards) {
						if (backStack.contains(Screen.NowPlaying)) {
							backStack.remove(Screen.NowPlaying)
						}
						backStack.add(Screen.CollectionDetail(event.playlist.id, "playlists"))
					}
				}
			}
		}
	}

	FormDialog(
		onDismissRequest = onDismissRequest,
		icon = { Icon(Icons.Outlined.PlaylistAdd, null) },
		title = { Text(stringResource(Res.string.title_create_playlist)) },
		buttons = {
			SegmentedListButton(
				modifier = Modifier.fillMaxWidth(),
				onClick = viewModel::create,
				enabled = state !is UiState.Loading && viewModel.name.text.isNotBlank(),
				colors = SegmentedListButtonDefaults.primaryColors(),
				shapes = SegmentedListButtonDefaults.shapes(index = 0, count = buttonCount)
			) {
				if (state is UiState.Loading) {
					CircularProgressIndicator(Modifier.size(20.dp))
				}
				Text(stringResource(Res.string.action_ok))
			}
			// navi-connect: a smart playlist instead, as a third button of upstream's stack.
			if (onSmartRequested != null) {
				SegmentedListButton(
					modifier = Modifier.fillMaxWidth(),
					onClick = {
						onDismissRequest()
						onSmartRequested()
					},
					enabled = state !is UiState.Loading,
					shapes = SegmentedListButtonDefaults.shapes(index = 1, count = buttonCount)
				) {
					Text(stringResource(Res.string.action_smart_playlist))
				}
			}
			SegmentedListButton(
				modifier = Modifier.fillMaxWidth(),
				onClick = onDismissRequest,
				enabled = state !is UiState.Loading,
				shapes = SegmentedListButtonDefaults.shapes(index = buttonCount - 1, count = buttonCount)
			) {
				Text(stringResource(Res.string.action_cancel))
			}
		},
		content = {
			(state as? UiState.Error)?.error?.let {
				SelectionContainer {
					Text("$it")
				}
			}
			TextField(
				state = viewModel.name,
				label = { Text(stringResource(Res.string.option_playlist_name)) },
				lineLimits = TextFieldLineLimits.SingleLine
			)
		}
	)
}
