package app.worbus.shared

import app.worbus.domain.models.DomainReplayGain
import app.worbus.domain.models.DomainSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Publishes the tags of every song in [queue] (song id → its ReplayGain, null: none) through
 * [publish] whenever the queue changes (not yet).
 */
fun CoroutineScope.publishQueueGainTags(
	queue: Flow<List<DomainSong>>,
	publish: (Map<String, DomainReplayGain?>) -> Unit
): Job = launch { }
