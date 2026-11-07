package app.worbus.ui.screens.song.components

import app.worbus.data.database.entities.bySongId
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import app.worbus.ui.components.common.rowPlaceholders
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.Modifier
import kotlinx.collections.immutable.ImmutableList
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.info_no_songs
import org.jetbrains.compose.resources.stringResource
import app.worbus.data.database.entities.DownloadEntity
import app.worbus.domain.models.DomainSong
import app.worbus.icons.Icons
import app.worbus.icons.outlined.Note
import app.worbus.ui.components.common.ContentUnavailable
import app.worbus.ui.core.UiState

fun LazyListScope.songListScreenContent(
	state: UiState<ImmutableList<DomainSong>>,
	selectedSong: DomainSong?,
	selectedSongIsStarred: Boolean,
	selectedSongRating: Int,
	allDownloads: List<DownloadEntity>,
	onUpdateSelection: (DomainSong) -> Unit,
	onClearSelection: () -> Unit,
	onSetShareId: (String) -> Unit,
	onSetStarred: (Boolean) -> Unit,
	onPlayNext: (DomainSong) -> Unit,
	onAddToQueue: (DomainSong) -> Unit,
	onPlaySong: (DomainSong) -> Unit,
	onSetRating: (Int) -> Unit,
	onDownload: (DomainSong) -> Unit,
	onCancelDownload: (DomainSong) -> Unit,
	onDeleteDownload: (DomainSong) -> Unit
) {
	val data = state.data.orEmpty()
	val downloadsBySong = allDownloads.bySongId()
	if (data.isNotEmpty()) {
		itemsIndexed(data) { index, song ->
			val download = downloadsBySong[song.id]
			SongListScreenItem(
				modifier = Modifier.animateItem(),
				index = index,
				count = data.size,
				song = song,
				selected = song == selectedSong,
				starred = if (song == selectedSong) selectedSongIsStarred else song.starredAt != null,
				rating = if (song == selectedSong) selectedSongRating else 0,
				onSelect = { onUpdateSelection(song) },
				onDeselect = { onClearSelection() },
				onSetStarred = { onSetStarred(it) },
				onSetShareId = onSetShareId,
				onPlayNext = { onPlayNext(song) },
				onAddToQueue = { onAddToQueue(song) },
				onClick = { onPlaySong(song) },
				onSetRating = onSetRating,
				download = download,
				onDownload = { onDownload(song) },
				onCancelDownload = { onCancelDownload(song) },
				onDeleteDownload = { onDeleteDownload(song) }
			)
		}
	} else {
		when (state) {
			// Waiting rows, never a blank list (audit L1-06).
			is UiState.Loading -> rowPlaceholders("songs", modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))

			else -> {
				item {
					ContentUnavailable(
						icon = Icons.Outlined.Note,
						label = stringResource(Res.string.info_no_songs)
					)
				}
			}
		}
	}
}
