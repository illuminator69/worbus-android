package app.worbus.ui.screens.queue.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.action_add_to_queue
import worbus.composeapp.generated.resources.action_play_next
import worbus.composeapp.generated.resources.action_remove_from_queue
import worbus.composeapp.generated.resources.action_reorder
import worbus.composeapp.generated.resources.queue_row_autoplay_mark
import org.jetbrains.compose.resources.stringResource
import app.worbus.data.database.entities.DownloadStatus
import app.worbus.domain.models.DomainSong
import app.worbus.icons.Icons
import app.worbus.icons.outlined.Delete
import app.worbus.icons.outlined.DragHandle
import app.worbus.icons.outlined.PlaylistPlay
import app.worbus.icons.outlined.Queue
import app.worbus.icons.outlined.QueuePlayNext
import app.worbus.ui.components.common.CoverArt
import app.worbus.ui.components.common.MarqueeText
import app.worbus.ui.components.common.SongRowDefaults
import app.worbus.ui.components.common.SongRowStatus
import app.worbus.ui.components.common.SegmentedListItem
import app.worbus.ui.components.common.SegmentedListItemDefaults
import app.worbus.ui.components.common.SwipeToDismissBox
import app.worbus.ui.components.common.Waveform
import app.worbus.ui.util.DraggableListState
import app.worbus.ui.util.dragHandle
import app.worbus.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import app.worbus.domain.models.creditText

@Composable
fun QueueScreenItem(
	index: Int,
	/**
	 * The list key of this row, for the drag handle.
	 *
	 * The key overload of [dragHandle] is used rather than the index one because the
	 * pointerInput modifier never restarts, so a captured index goes stale the moment the drag
	 * reorders anything. It is also what every other reorderable list in the app passes.
	 */
	dragKey: Any,
	count: Int,
	song: DomainSong,
	isPlaying: Boolean,
	isSelected: Boolean,
	isDragging: Boolean,
	draggableState: DraggableListState,
	onClick: () -> Unit,
	/** Long-press opens this row's song sheet — every card, not just the playing one. */
	onLongClick: () -> Unit,
	onRemove: () -> Unit,
	dragEnabled: Boolean = true,
	isOffline: Boolean = false,
	isDownloaded: Boolean = false,
	/**
	 * B-350: a quiet mark before the duration for a song autoplay added, shown while the queue is
	 * listed shuffled (its own section header is gone then). The screen reads
	 * `rowShowsAutoplayMark`, nothing else.
	 */
	autoplayMark: Boolean = false
) {
	val canPlay = !isOffline || isDownloaded

	val elevation by animateDpAsState(
		targetValue = if (isDragging) 8.dp else 0.dp,
		animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec()
	)

	val dismissState = rememberSwipeToDismissBoxState()
	val scope = rememberCoroutineScope()

	val itemShape = segmentedShapes(
		index = index,
		count = count,
		dismissDirection = dismissState.dismissDirection
	)

	SwipeToDismissBox(
		state = dismissState,
		onDismiss = {
			onRemove()
			scope.launch {
				dismissState.reset()
			}
		},
		backgroundContent = {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.clip(itemShape.shape)
					// Only paint the red delete background while actively swiping —
					// otherwise it would bleed through the translucent (frosted) card.
					.background(
						if (dismissState.dismissDirection == SwipeToDismissBoxValue.Settled)
							Color.Transparent
						else MaterialTheme.colorScheme.errorContainer
					)
					.padding(horizontal = 20.dp)
			) {
				Icon(
					imageVector = Icons.Outlined.Delete,
					contentDescription = stringResource(Res.string.action_remove_from_queue),
					tint = MaterialTheme.colorScheme.onErrorContainer,
					modifier = Modifier.align(
						when (dismissState.dismissDirection) {
							SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
							else -> Alignment.CenterEnd
						}
					)
				)
			}
		},
		content = {
			QueueSongCard(
				song = song,
				shapes = itemShape,
				isCurrentTrack = isSelected,
				isPlaying = isPlaying,
				canPlay = canPlay,
				downloadStatus = if (isDownloaded) DownloadStatus.DOWNLOADED else null,
				autoplayMark = autoplayMark,
				elevation = elevation,
				onClick = onClick,
				onLongClick = onLongClick,
				// Appended AFTER the status cluster so the handle keeps the same
				// position whichever icons a given row happens to show. Drag reorders
				// the local queue, or the hub session queue when another device is
				// active (the hub broadcasts the new order back).
				trailing = if (dragEnabled) {
					{
						IconButton(
							modifier = Modifier.dragHandle(
								state = draggableState,
								key = dragKey
							),
							onClick = {}
						) {
							Icon(
								Icons.Outlined.DragHandle,
								contentDescription = stringResource(Res.string.action_reorder)
							)
						}
					}
				} else null
			)
		}
	)
}

/**
 * A row of the queue sheet's Related tab. The same card as [QueueScreenItem] (it is drawn by the
 * same [QueueSongCard]), with the queue-building gestures in place of the queue-editing ones:
 * swipe right to add to the queue, swipe left to play next. No trailing button: the swipe and the
 * long-press sheet are the ways in.
 */
@Composable
fun RelatedSongItem(
	modifier: Modifier = Modifier,
	index: Int,
	count: Int,
	song: DomainSong,
	canPlay: Boolean,
	downloadStatus: DownloadStatus?,
	downloadProgress: Float,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	onAddToQueue: () -> Unit,
	onPlayNext: () -> Unit
) {
	val dismissState = rememberSwipeToDismissBoxState()
	val scope = rememberCoroutineScope()

	val itemShape = segmentedShapes(
		index = index,
		count = count,
		dismissDirection = dismissState.dismissDirection
	)

	SwipeToDismissBox(
		modifier = modifier,
		state = dismissState,
		onDismiss = {
			if (it == SwipeToDismissBoxValue.StartToEnd) onAddToQueue()
			if (it == SwipeToDismissBoxValue.EndToStart) onPlayNext()
			scope.launch { dismissState.reset() }
		},
		backgroundContent = {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.clip(itemShape.shape)
					// Only while swiping, as in the Up Next rows: a background painted all the time
					// tints every translucent card above it.
					.background(
						if (dismissState.dismissDirection == SwipeToDismissBoxValue.Settled)
							Color.Transparent
						else MaterialTheme.colorScheme.primaryContainer
					)
					.padding(horizontal = 20.dp)
			) {
				when (dismissState.dismissDirection) {
					SwipeToDismissBoxValue.StartToEnd -> Icon(
						imageVector = Icons.Outlined.Queue,
						contentDescription = stringResource(Res.string.action_add_to_queue),
						tint = MaterialTheme.colorScheme.onPrimaryContainer,
						modifier = Modifier.align(Alignment.CenterStart)
					)

					SwipeToDismissBoxValue.EndToStart -> Icon(
						imageVector = Icons.Outlined.QueuePlayNext,
						contentDescription = stringResource(Res.string.action_play_next),
						tint = MaterialTheme.colorScheme.onPrimaryContainer,
						modifier = Modifier.align(Alignment.CenterEnd)
					)

					else -> {}
				}
			}
		},
		content = {
			QueueSongCard(
				song = song,
				shapes = itemShape,
				isCurrentTrack = false,
				isPlaying = false,
				canPlay = canPlay,
				downloadStatus = downloadStatus,
				downloadProgress = downloadProgress,
				onClick = onClick,
				onLongClick = onLongClick,
				trailing = null
			)
		}
	)
}

/**
 * The card both queue tabs draw, so Up Next and Related cannot drift apart again.
 *
 * Shared with the album/search rows through [SongRowDefaults] — translucent, so the queue's ONE
 * ambient wash (from the now-playing song) shows through every card rather than each row tinting
 * itself from its own cover.
 */
@Composable
private fun QueueSongCard(
	song: DomainSong,
	shapes: ListItemShapes,
	isCurrentTrack: Boolean,
	isPlaying: Boolean,
	canPlay: Boolean,
	downloadStatus: DownloadStatus?,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	trailing: (@Composable () -> Unit)?,
	downloadProgress: Float = 0f,
	autoplayMark: Boolean = false,
	elevation: Dp = 0.dp
) {
	val color = SongRowDefaults.containerColor(isCurrentTrack)
	val contentColor = SongRowDefaults.contentColor(isCurrentTrack)
	val supportingContentColor = SongRowDefaults.supportingContentColor(isCurrentTrack)

	Surface(
		shadowElevation = elevation,
		shape = shapes.shape
	) {
		SegmentedListItem(
			onClick = onClick,
			onLongClick = onLongClick,
			enabled = canPlay,
			colors = ListItemDefaults.colors(
				containerColor = color,
				selectedContainerColor = color,
				disabledContainerColor = color,
				draggedContainerColor = color,
				contentColor = contentColor,
				supportingContentColor = supportingContentColor
			),
			shapes = shapes,
			verticalAlignment = Alignment.CenterVertically,
			content = { MarqueeText(song.title, scrolls = isCurrentTrack) },
			supportingContent = { MarqueeText(song.creditText, scrolls = isCurrentTrack) },
			leadingContent = {
				CoverArt(
					modifier = Modifier.size(SongRowDefaults.CoverSize),
					coverArtId = song.coverArtId,
					fallbackAlbumId = song.albumId,
					shape = SongRowDefaults.CoverShape
				)
			},
			trailingContent = {
				SongRowStatus(
					canPlay = canPlay,
					downloadStatus = downloadStatus,
					downloadProgress = downloadProgress,
					isCurrentTrack = isCurrentTrack,
					isPlaying = isPlaying,
					duration = song.duration,
					durationColor = supportingContentColor,
					beforeDuration = if (autoplayMark) {
						{
							Icon(
								Icons.Outlined.PlaylistPlay,
								contentDescription = stringResource(Res.string.queue_row_autoplay_mark),
								modifier = Modifier.size(SongRowDefaults.StatusIconSize),
								tint = MaterialTheme.colorScheme.onSurfaceVariant
							)
						}
					} else null,
					trailing = trailing
				)
			},
			contentPadding = SongRowDefaults.ContentPadding
		)
	}
}
