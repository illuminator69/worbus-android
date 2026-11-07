package app.worbus.ui.components.sheets

import androidx.compose.runtime.Composable
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.info_mood_search_empty
import worbus.composeapp.generated.resources.title_mood_search
import org.jetbrains.compose.resources.stringResource
import app.worbus.domain.models.DomainSong
import app.worbus.ui.util.rememberNowPlayingCoverAmbient

/**
 * desktop-style preview for a CLAP text→mood search: shows the proposed queue
 * (best match first) and lets the user Play it as the queue or append it — it
 * does NOT auto-play on open, so the user reviews the matches first.
 */
@Composable
fun MoodSearchSheet(
	query: String,
	songs: List<DomainSong>,
	loading: Boolean,
	onPlay: () -> Unit,
	onAddToQueue: () -> Unit,
	onDismissRequest: () -> Unit
) {
	// The now-playing cover's wash: what a mood search proposes is a queue to follow it.
	GeneratedQueueSheet(
		title = stringResource(Res.string.title_mood_search),
		subtitle = "\"$query\"",
		songs = songs,
		loading = loading,
		emptyText = stringResource(Res.string.info_mood_search_empty),
		ambient = rememberNowPlayingCoverAmbient(),
		onPlay = onPlay,
		onAddToQueue = onAddToQueue,
		onDismissRequest = onDismissRequest
	)
}
