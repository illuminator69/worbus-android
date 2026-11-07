package app.worbus.ui.screens.collection.components

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.dropUnlessResumed
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.launch
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.title_more_by_artist
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import app.worbus.data.database.entities.DownloadStatus
import app.worbus.di.LocalNavStack
import app.worbus.domain.manager.DownloadManager
import app.worbus.domain.models.DomainAlbum
import app.worbus.ui.navigation.Screen
import app.worbus.ui.components.layouts.ArtCarousel
import app.worbus.ui.components.layouts.ArtCarouselItem
import app.worbus.ui.components.sheets.CollectionSheet
import app.worbus.ui.screens.playlist.dialogs.PlaylistUpdateDialog

fun LazyListScope.collectionDetailScreenMoreByArtistRow(
	artistName: String,
	artistAlbums: List<DomainAlbum>,
	selectedAlbum: DomainAlbum?,
	onSetShareId: (String) -> Unit,
	onPlayNext: (() -> Unit)?,
	onAddToQueue: (() -> Unit)?,
	selectedAlbumStarred: Boolean,
	selectedAlbumRating: Int,
	onSetAlbumRating: (Int) -> Unit,
	onSetAlbumStarred: (Boolean) -> Unit,
	onSelect: (DomainAlbum) -> Unit,
	onDeselect: () -> Unit,
	tab: String,
) {
	item {
		val backStack = LocalNavStack.current
		val scope = rememberCoroutineScope()

		var albumToAddToPlaylist by remember { mutableStateOf<DomainAlbum?>(null) }

		val downloadManager = koinInject<DownloadManager>()

		ArtCarousel(
			title = stringResource(Res.string.title_more_by_artist, artistName),
			items = artistAlbums.sortedByDescending { it.playCount }.toImmutableList()
		) { album ->
			ArtCarouselItem(
				coverArtId = album.coverArtId,
				title = album.name ?: "[unknown album]",
				contentDescription = album.name,
				onSelect = { onSelect(album) },
				onClick = dropUnlessResumed {
					backStack.add(Screen.CollectionDetail(album.id, tab))
				}
			)
			if (selectedAlbum != null && selectedAlbum == album) {
				// Read only while its sheet is open (row 8), one flow for the sheet's life; null until
				// it answers, so no "Download" is offered for an album already on the device.
				val downloadStatus: DownloadStatus? by remember(downloadManager, album.id, album.songs.size) {
					downloadManager.getCollectionDownloadStatus(album.songs.map { it.id })
				}.collectAsState(initial = null)
				CollectionSheet(
					onDismissRequest = onDeselect,
					collection = album,
					onDownloadAll = {
						scope.launch {
							downloadManager.downloadCollection(album)
						}
					},
					onCancelDownloadAll = {
						scope.launch {
							album.songs.forEach { downloadManager.cancelDownload(it.id) }
						}
					},
					onDeleteDownloadAll = {
						scope.launch {
							downloadManager.deleteDownloadedCollection(album)
						}
					},
					downloadStatus = downloadStatus,
					onShare = { onSetShareId(album.id) },
					onPlayNext = onPlayNext,
					onAddToQueue = onAddToQueue,
					onAddAllToPlaylist = { albumToAddToPlaylist = album },
					onViewArtist = dropUnlessResumed { backStack.add(Screen.ArtistDetail(album.artistId)) },
					rating = selectedAlbumRating,
					onSetRating = onSetAlbumRating,
					starred = selectedAlbumStarred,
					onSetStarred = { onSetAlbumStarred(!selectedAlbumStarred) }
				)
			}
		}
		if (albumToAddToPlaylist != null) {
			albumToAddToPlaylist?.let {
				PlaylistUpdateDialog(
					songs = it.songs.toPersistentList(),
					onDismissRequest = { albumToAddToPlaylist = null }
				)
			}
		}
	}
}
