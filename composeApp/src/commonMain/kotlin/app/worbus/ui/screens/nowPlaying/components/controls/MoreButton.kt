package app.worbus.ui.screens.nowPlaying.components.controls

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import kotlinx.collections.immutable.persistentListOf
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.action_more
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import app.worbus.di.LocalNavStack
import app.worbus.di.LocalPlatformContext
import app.worbus.domain.manager.RadioManager
import app.worbus.ui.navigation.NowPlayingSheetController
import app.worbus.ui.navigation.Screen
import app.worbus.icons.Icons
import app.worbus.icons.outlined.MoreHoriz
import app.worbus.shared.MediaPlayerViewModel
import app.worbus.ui.components.sheets.SongSheet
import app.worbus.ui.screens.playlist.dialogs.PlaylistUpdateDialog
import app.worbus.ui.screens.share.dialogs.ShareDialog
import app.worbus.ui.theme.NavicTheme
import app.worbus.ui.util.rememberColorSchemeFromCoverArt
import kotlin.time.Duration

@Composable
fun NowPlayingMoreButton(
	songRating: Int,
	onSetSongRating: (Int) -> Unit
) {
	val backStack = LocalNavStack.current
	val platformContext = LocalPlatformContext.current
	val player = koinInject<MediaPlayerViewModel>()
	val radioManager = koinInject<RadioManager>()
	val playerState by player.steadyState.collectAsState()
	val song = playerState.currentSong
	var expanded by remember { mutableStateOf(false) }
	var playlistDialogShown by rememberSaveable { mutableStateOf(false) }
	var shareId by remember { mutableStateOf<String?>(null) }
	var shareExpiry by remember { mutableStateOf<Duration?>(null) }

	IconButton(
		onClick = {
			platformContext.clickSound()
			expanded = true
		},
		colors = IconButtonDefaults.filledTonalIconButtonColors(),
		modifier = Modifier.size(32.dp),
		enabled = song != null
	) {
		Icon(
			imageVector = Icons.Outlined.MoreHoriz,
			contentDescription = stringResource(Res.string.action_more)
		)
	}

	if (expanded && song != null) {
		NavicTheme {
			SongSheet(
				onDismissRequest = { expanded = false },
				song = song,
				collection = playerState.currentCollection,
				onViewAlbum = dropUnlessResumed {
					playerState.currentCollection?.let { collection ->
						NowPlayingSheetController.requestHide {
							backStack.add(Screen.CollectionDetail(collection.id, ""))
						}
					}
				},
				onViewArtist = dropUnlessResumed {
					// Animated close, then navigate — see NowPlayingSheetController.requestHide.
					NowPlayingSheetController.requestHide {
						backStack.add(Screen.ArtistDetail(song.artistId))
					}
				},
				onViewArtistId = { id ->
					NowPlayingSheetController.requestHide {
						backStack.add(Screen.ArtistDetail(id))
					}
				},
				onShare = {
					shareId = song.id
				},
				onAddToPlaylist = {
					playlistDialogShown = true
				},
				onStartRadio = {
					radioManager.startRadio(song)
				},
				onTrackInfo = dropUnlessResumed {
					backStack.remove(Screen.NowPlaying)
					backStack.add(Screen.SongDetailSheet(song.id))
				},
				rating = songRating,
				onSetRating = onSetSongRating,
				showSleepTimer = true,
				showPlaybackSpeed = true
			)
		}
	}

	if (playlistDialogShown && song != null) {
		NavicTheme {
			PlaylistUpdateDialog(
				songs = persistentListOf(song),
				onDismissRequest = { playlistDialogShown = false }
			)
		}
	}

	NavicTheme {
		ShareDialog(
			id = shareId,
			onIdClear = { shareId = null },
			expiry = shareExpiry,
			onExpiryChange = { shareExpiry = it }
		)
	}
}
