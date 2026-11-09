package app.worbus.domain.manager

import app.worbus.domain.models.DomainReplayGain
import app.worbus.domain.models.settings.ReplayGainMode

expect class AudioGainManager {
	/**
	 * Everything the output gain depends on, in one call (never a flush: the playback thread ramps to it, B-366): the ReplayGain
	 * pre-amp and the plain amplifier in dB, the playing song's tags and the mode resolved by
	 * `resolveGainMode`. Called by the player only (`applyAudioGain`), the settings screen included.
	 */
	fun update(withReplayGain: Float, withoutReplayGain: Float, metadata: DomainReplayGain?, mode: ReplayGainMode)

	/**
	 * The tags of every song in the player's queue, by song id (null: none), published whenever the
	 * queue changes: the processor picks a new stream's own tags on the playback thread at the item
	 * boundary, before the player's [update] for it (B-282).
	 */
	fun setQueueTags(tags: Map<String, DomainReplayGain?>)
}
