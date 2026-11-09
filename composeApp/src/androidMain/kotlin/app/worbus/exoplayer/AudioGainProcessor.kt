package app.worbus.exoplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import app.worbus.domain.models.DomainReplayGain
import app.worbus.domain.models.settings.ReplayGainMode
import app.worbus.util.decibelsToLinear
import app.worbus.util.effectiveGain
import app.worbus.util.isUnityGain
import app.worbus.util.outputGainDb
import app.worbus.util.peak
import app.worbus.util.peakLimited
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * ReplayGain and the two amplifiers, on 16-bit PCM only (B-278, adapted from upstream d8380643).
 *
 * Gains are in dB and 0 dB is unity. With no ReplayGain in force (mode Off, or a song without
 * tags) the plain amplifier applies; with ReplayGain the song's gain plus the ReplayGain
 * pre-amp. At unity the samples pass through bit for bit. The default used to be 1 read as
 * dB, so every song played 1 dB hot and was clipped.
 *
 * A song without tags no longer keeps the previous song's gain for the whole song. **The first
 * fraction of a second after a transition still can**: the player calls [update] on the main
 * thread from its transition callback, while this processor runs ahead of what is heard by the
 * AudioTrack's buffer (about 0.25 to 0.75 s), so the new song's first samples went through at the
 * old gain. Pre-existing, inherited from upstream; resolving the gain on the playback thread at
 * the stream boundary is still open (B-282).
 *
 * Everything the output gain depends on is one immutable [GainState] in one volatile field, so the
 * playback thread never reads half of an update. [update] never flushes (B-366): it ran on the
 * main thread while the playback thread was inside [queueInput], so both wrote the processor's
 * buffers at once, and the buffer just produced was thrown away. The playback thread picks the
 * new gain up at its next buffer and ramps to it across that buffer, which is what keeps a change
 * from clicking. A song's peak caps the gain (B-282): a positive gain never pushes it past full
 * scale.
 */
@UnstableApi
class AudioGainProcessor : BaseAudioProcessor() {
	private class GainState(
		/** ReplayGain is switched on, whatever the song carries (see [isActive]). */
		val replayGainEnabled: Boolean,
		rgAmpDb: Float,
		ampDb: Float,
		/** The ReplayGain in force, in dB; null when none applies. */
		replayGainDb: Float?,
		/** The song's peak for the mode (linear); null when not known. */
		peak: Float?
	) {
		val linear: Float = peakLimited(outputGainDb(replayGainDb, rgAmpDb, ampDb).decibelsToLinear(), peak)
	}

	@Volatile
	private var state = GainState(replayGainEnabled = false, rgAmpDb = 0f, ampDb = 0f, replayGainDb = null, peak = null)

	// Playback thread only: the gain the last buffer ended at, and whether any audio has gone
	// through since the last flush (before that there is nothing a step could click against).
	private var appliedLinear = 1f
	private var processedSinceFlush = false

	/** The output gain as a linear factor. */
	val linearGain: Float
		get() = state.linear

	/**
	 * The whole input to the output gain, at once: the ReplayGain pre-amp and the plain amplifier
	 * (dB), the playing song's tags (null: none) and the mode, already resolved
	 * (`resolveGainMode`: Dynamic is never passed). Any thread; it only swaps the state, and the
	 * playback thread ramps to it at its next buffer (B-366).
	 */
	fun update(rgAmpDb: Float, ampDb: Float, metadata: DomainReplayGain?, mode: ReplayGainMode) {
		state = GainState(
			replayGainEnabled = mode != ReplayGainMode.Off,
			rgAmpDb = rgAmpDb,
			ampDb = ampDb,
			replayGainDb = metadata?.effectiveGain(mode),
			peak = metadata?.peak(mode)
		)
	}

	/**
	 * Out of the chain only when nothing can apply a gain: ReplayGain off and the plain
	 * amplifier at 0 dB. media3 reads this only when it sets the chain up (a new format, a seek),
	 * not per song: with ReplayGain on, a song without tags (unity) followed gaplessly by one with
	 * tags would otherwise play the second without its gain. In the chain at unity the samples
	 * are copied unchanged.
	 */
	override fun isActive(): Boolean {
		val state = state
		return super.isActive() && (state.replayGainEnabled || !isUnityGain(state.linear))
	}

	override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
		if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
			return inputAudioFormat
		}
		throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
	}

	override fun onFlush(streamMetadata: StreamMetadata) {
		// The playback thread's own flush (a seek, a new configuration): nothing is in flight, so
		// the next buffer starts at the gain in force, with no ramp.
		appliedLinear = state.linear
		processedSinceFlush = false
	}

	override fun queueInput(inputBuffer: ByteBuffer) {
		val pos = inputBuffer.position()
		val limit = inputBuffer.limit()
		val outputBuffer = replaceOutputBuffer(limit - pos)

		inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
		outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

		val target = linearGain
		// A change since the last buffer ramps across this one; before any audio has gone through
		// since a flush there is nothing to ramp from.
		val from = if (processedSinceFlush) appliedLinear else target
		appliedLinear = target
		processedSinceFlush = true

		if (isUnityGain(from) && isUnityGain(target)) {
			outputBuffer.put(inputBuffer)
		} else {
			val shortBufferInput = inputBuffer.asShortBuffer()
			val shortBufferOutput = outputBuffer.asShortBuffer()
			val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
			val frames = (shortBufferInput.remaining() / channels).coerceAtLeast(1)
			val step = (target - from) / frames
			var i = 0

			while (shortBufferInput.hasRemaining()) {
				val gain = if (from == target) target else from + step * (i / channels + 1)
				val sample = shortBufferInput.get()
				val scaledSample = (sample * gain)
					.roundToInt()
					.coerceAtLeast(Short.MIN_VALUE.toInt())
					.coerceAtMost(Short.MAX_VALUE.toInt())
					.toShort()

				shortBufferOutput.put(scaledSample)
				i++
			}
			inputBuffer.position(inputBuffer.position() + shortBufferInput.position() * 2)
			outputBuffer.position(outputBuffer.position() + shortBufferOutput.position() * 2)
		}
		outputBuffer.flip()
	}
}
