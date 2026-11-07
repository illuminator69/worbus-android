package app.worbus.ui.screens.library.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.worbus.data.database.entities.SavedQueueEntity
import app.worbus.ui.components.layouts.ArtGridItem
import app.worbus.ui.screens.savedqueues.savedQueueCoverArtId
import app.worbus.ui.screens.savedqueues.savedQueueSubtitle
import app.worbus.ui.screens.savedqueues.savedQueueTitle

/**
 * A "Continue listening" card: upstream's grid card (`ArtGridItem`), the size of the album cards
 * beside it, with the queue's cover (its resume track's, B-046, so it changes as playback moves),
 * what it's called, and how it was made. Tapping resumes the queue at its saved playhead; a long
 * press opens its sheet, as an album card's does.
 *
 * The queue playing right now appears here too, first, and says so in its second line; see
 * LibraryScreen.
 */
@Composable
fun ContinueListeningCard(
	queue: SavedQueueEntity,
	isActive: Boolean,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	ArtGridItem(
		modifier = modifier,
		onClick = onClick,
		onLongClick = onLongClick,
		coverArtId = savedQueueCoverArtId(queue),
		title = savedQueueTitle(queue),
		subtitle = savedQueueSubtitle(queue, isActive),
		id = queue.id,
		tab = "library-continue"
	)
}
