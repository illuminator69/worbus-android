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
}
