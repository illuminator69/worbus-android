package app.worbus.shared

import app.worbus.domain.models.DomainReplayGain
import app.worbus.domain.models.DomainSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Publishes the tags of every song in [queue] (song id → its ReplayGain, null: none) through
 * [publish] whenever the queue changes, whichever path changed it (B-282, third review).
 *
 * The gain processor picks a new stream's own tags out of this map at its item boundary, on the
 * playback thread. Published from a derive (`applyAudioGain`) it missed every path that replaces
 * the queue without one: the appends (`addToQueueLocal`, `addToQueueSingleLocal`, `appendToQueue`,
 * `appendToQueueAndWait`), so a song appended as the next one started at the previous song's gain.
 * A new list is a new queue (compared by identity: the map is rebuilt, never diffed).
 */
fun CoroutineScope.publishQueueGainTags(
	queue: Flow<List<DomainSong>>,
	publish: (Map<String, DomainReplayGain?>) -> Unit
): Job = launch {
	queue.distinctUntilChanged { old, new -> old === new }.collect { songs ->
		publish(songs.associate { it.id to it.replayGain })
	}
}
