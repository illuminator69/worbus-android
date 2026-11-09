package app.worbus.exoplayer

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder

/*
 * W1 (navi-connect DESIGN-w1-play-order-2026-11-10 §5): what the service's ExoPlayer orders a
 * shuffled queue by. One seam, called by PlaybackService on the player it builds and by
 * PlayOrderPlayerTest on a real ExoPlayer, so the test runs the rule the service runs.
 */

/** Gives [player] the play-order rules. Today: nothing, media3's DefaultShuffleOrder. */
@UnstableApi
internal fun installPlayOrder(player: ExoPlayer) {
}

/** Sets an explicit play order ([order] lists every row once, in play order). */
@UnstableApi
internal fun setPlayOrder(player: ExoPlayer, order: IntArray) {
	player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, 0L))
}
