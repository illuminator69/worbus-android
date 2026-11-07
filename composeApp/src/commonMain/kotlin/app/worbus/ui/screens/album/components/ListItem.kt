package app.worbus.ui.screens.album.components

import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.launch
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.notice_deleted_download
import worbus.composeapp.generated.resources.notice_download_started
import org.koin.compose.koinInject
import app.worbus.domain.manager.RadioManager
import app.worbus.data.database.entities.DownloadStatus
import app.worbus.di.LocalNavStack
import app.worbus.domain.manager.DownloadManager
import app.worbus.domain.manager.PreferenceManager
import app.worbus.domain.manager.SnackBarManager
import app.worbus.domain.models.DomainAlbum
import app.worbus.domain.models.DomainSong
import app.worbus.ui.components.common.LibraryRow
import app.worbus.ui.components.common.LibraryRowCover
import app.worbus.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import app.worbus.ui.components.sheets.CollectionSheet
import app.worbus.ui.navigation.Screen
import app.worbus.ui.screens.playlist.dialogs.PlaylistUpdateDialog

@Composable
fun AlbumListScreenListItem(
	modifier: Modifier = Modifier,
	/** Where the row sits in its run of rows, for its rounded segment's corners. */
	index: Int,
	count: Int,
	album: DomainAlbum,
	selected: Boolean,
	starred: Boolean,
	rating: Int,
	onSelect: () -> Unit,
	onDeselect: () -> Unit,
	onSetStarred: (starred: Boolean) -> Unit,
	onSetShareId: (String) -> Unit,
	onPlayNext: () -> Unit,
	onAddToQueue: () -> Unit,
	onSetRating: (Int) -> Unit
) {
	val backStack = LocalNavStack.current
	val preferenceManager = koinInject<PreferenceManager>()
	val snackBarManager = koinInject<SnackBarManager>()
	val radioManager = koinInject<RadioManager>()
	val scope = rememberCoroutineScope()

	// The songs "Add to playlist" acts on, captured at the tap (null = no dialog). The sheet closes
	// right after the tap and that clears the selection, so `album` is then the list's row again,
	// which carries no songs (row 4a): a dialog reading `album.songs` added nothing.
	var playlistSongs by remember { mutableStateOf<ImmutableList<DomainSong>?>(null) }

	val downloadManager = koinInject<DownloadManager>()

	Box(modifier) {
		// The Library's row (P5 round 2): Recents' look, the sleeve, the title, the artist and year.
		LibraryRow(
			title = album.name ?: "[unknown album]",
			line = listOfNotNull(album.artistName?.ifBlank { null }, album.year?.toString()).joinToString(" • "),
			shapes = segmentedShapes(index, count),
			art = { LibraryRowCover(album.coverArtId, album.name) },
			onClick = dropUnlessResumed {
				scope.launch {
					backStack.add(Screen.CollectionDetail(album.id, ""))
				}
			},
			onLongClick = onSelect
		)
		if (selected) {
			// Read only while its sheet is open (row 8): every tile used to watch the download
			// table for a status nothing but the sheet shows.
			// Null until the first answer: the sheet hides its download row rather than offer a
			// "Download" for an album that is already on the device.
			val downloadStatus: DownloadStatus? by remember(downloadManager, album.id, album.songs.size) {
				downloadManager.getCollectionDownloadStatus(album.songs.map { it.id })
			}.collectAsState(initial = null)
			CollectionSheet(
				onDismissRequest = onDeselect,
				collection = album,
				onShare = { onSetShareId(album.id) },
				onPlayNext = onPlayNext,
				onAddToQueue = onAddToQueue,
				onStartRadio = { radioManager.startAlbumRadio(album.id, album.name.orEmpty()) },
				downloadStatus = downloadStatus,
				onDownloadAll = {
					scope.launch {
						downloadManager.downloadCollection(album)
						snackBarManager.notify(Res.string.notice_download_started)
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
						snackBarManager.notify(Res.string.notice_deleted_download)
					}
				},
				starred = starred,
				onSetStarred = onSetStarred,
				onAddAllToPlaylist = { playlistSongs = album.songs.toPersistentList() },
				onViewArtist = dropUnlessResumed {
					backStack.add(Screen.ArtistDetail(album.artistId))
				},
				rating = rating,
				onSetRating = onSetRating
			)
		}

		playlistSongs?.let { songs ->
			PlaylistUpdateDialog(
				songs = songs,
				onDismissRequest = { playlistSongs = null }
			)
		}
	}
}
