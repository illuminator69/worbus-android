package app.worbus.ui.screens.playlist.components

import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.Modifier
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.info_no_playlists_short
import org.jetbrains.compose.resources.stringResource
import app.worbus.domain.models.DomainPlaylist
import app.worbus.domain.models.settings.ListViewMode
import app.worbus.icons.Icons
import app.worbus.icons.outlined.PlaylistRemove
import app.worbus.ui.components.common.ContentUnavailable
import app.worbus.ui.components.layouts.artGridPlaceholder
import app.worbus.ui.core.UiState
import app.worbus.ui.screens.playlist.components.PlaylistListScreenGridItem

fun LazyGridScope.playlistListScreenContent(
	state: UiState<List<DomainPlaylist>>,
	selectedPlaylist: DomainPlaylist?,
	selectedViewMode: ListViewMode,
	onUpdateSelection: (DomainPlaylist) -> Unit,
	onClearSelection: () -> Unit,
	onSetShareId: (String) -> Unit,
	onSetDeletionId: (String) -> Unit,
	onPlayNext: () -> Unit,
	onAddToQueue: () -> Unit,
) {
	val data = state.data.orEmpty()
	if (data.isNotEmpty()) {
		itemsIndexed(data, { _, it -> it.id }) { index, playlist ->
			if (selectedViewMode == ListViewMode.Grid) {
				PlaylistListScreenGridItem(
					modifier = Modifier.animateItem(),
					tab = "playlists",
					playlist = playlist,
					selected = playlist == selectedPlaylist,
					onSelect = { onUpdateSelection(playlist) },
					onDeselect = { onClearSelection() },
					onSetShareId = onSetShareId,
					onSetDeletionId = onSetDeletionId,
					onPlayNext = onPlayNext,
					onAddToQueue = onAddToQueue,
				)
			} else {
				PlaylistListScreenListItem(
					modifier = Modifier.animateItem(),
					index = index,
					count = data.size,
					playlist = playlist,
					selected = playlist == selectedPlaylist,
					onSelect = { onUpdateSelection(playlist) },
					onDeselect = { onClearSelection() },
					onSetShareId = onSetShareId,
					onSetDeletionId = onSetDeletionId,
					onPlayNext = onPlayNext,
					onAddToQueue = onAddToQueue,
				)
			}
		}
	} else {
		when (state) {
			is UiState.Loading -> {
				artGridPlaceholder(viewMode = selectedViewMode)
			}

			else -> {
				item(span = { GridItemSpan(maxLineSpan) }) {
					ContentUnavailable(
						icon = Icons.Outlined.PlaylistRemove,
						label = stringResource(Res.string.info_no_playlists_short)
					)
				}
			}
		}
	}
}
