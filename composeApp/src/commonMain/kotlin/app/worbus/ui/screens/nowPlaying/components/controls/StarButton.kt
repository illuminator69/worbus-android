package app.worbus.ui.screens.nowPlaying.components.controls

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.action_star
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import app.worbus.icons.Icons
import app.worbus.icons.filled.Star
import app.worbus.icons.outlined.Star
import app.worbus.shared.MediaPlayerViewModel

@Composable
fun NowPlayingStarButton(
	songIsStarred: Boolean,
	onSetSongIsStarred: (Boolean) -> Unit
) {
	val player = koinInject<MediaPlayerViewModel>()
	val playerState by player.steadyState.collectAsStateWithLifecycle()
	IconButton(
		onClick = {
			onSetSongIsStarred(!songIsStarred)
		},
		colors = IconButtonDefaults.filledTonalIconButtonColors(),
		modifier = Modifier.size(32.dp),
		enabled = playerState.currentSong != null
	) {
		Icon(
			if (songIsStarred) Icons.Filled.Star else Icons.Outlined.Star,
			contentDescription = stringResource(Res.string.action_star)
		)
	}
}
