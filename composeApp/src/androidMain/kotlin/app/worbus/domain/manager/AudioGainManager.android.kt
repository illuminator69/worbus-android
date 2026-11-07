package app.worbus.domain.manager

import androidx.media3.common.util.UnstableApi
import app.worbus.domain.models.DomainReplayGain
import app.worbus.domain.models.settings.ReplayGainMode
import app.worbus.exoplayer.AudioGainProcessor

@UnstableApi
actual class AudioGainManager(
	private val audioGainProcessor: AudioGainProcessor
) {
	actual fun update(withReplayGain: Float, withoutReplayGain: Float, metadata: DomainReplayGain?, mode: ReplayGainMode) {
		audioGainProcessor.update(withReplayGain, withoutReplayGain, metadata, mode)
	}
}
