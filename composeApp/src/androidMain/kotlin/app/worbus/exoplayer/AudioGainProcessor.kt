package app.worbus.exoplayer

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
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
 * **The gain follows the stream, not the main thread (B-282).** At every item boundary, gapless
 * ones included, media3 calls [onFlush] on the playback thread with the NEW item's period in its
 * `StreamMetadata` (watched on the VM, media3 1.11: 0.5 to 0.6 s before the player's transition
 * callback on main). The item's media id picks its tags out of the queue's ([setQueueTags]), so the
 * new song's first buffer is already at its own gain. The player's [update] (main thread, at the
 * transition) carries the settings and the playing song's tags; those tags are used only for a
 * stream the queue's tags do not name (no flush with a known item yet, or an item a path added
 * without publishing its tags), as before.
 *
 * Each input is one immutable value in a volatile field (the settings with main's tags, the queue's
 * tags, the stream's id), so the playback thread never reads half of an update. [update] never flushes (B-366): it ran on the
 * main thread while the playback thread was inside [queueInput], so both wrote the processor's
 * buffers at once, and the buffer just produced was thrown away. The playback thread picks the
 * new gain up at its next buffer and ramps to it across that buffer, which is what keeps a change
 * from clicking. While ReplayGain applies, a song's peak caps the gain (B-282): a positive gain
 * never pushes it past full scale. The plain amplifier is never capped.
 */
@UnstableApi
class AudioGainProcessor : BaseAudioProcessor() {
	/** What the player says, in one call ([update]): the settings and the playing song's tags. */
	private class Settings(
		val rgAmpDb: Float,
		val ampDb: Float,
		/** Already resolved (`resolveGainMode`: Dynamic is never passed). */
		val mode: ReplayGainMode,
		/** The playing song's tags as the player names it; null: none. */
		val tags: DomainReplayGain?
	)

	private class GainState(settings: Settings, tags: DomainReplayGain?) {
		/** ReplayGain is switched on, whatever the song carries (see [isActive]). */
		val replayGainEnabled: Boolean = settings.mode != ReplayGainMode.Off
		private val replayGainDb: Float? = tags?.effectiveGain(settings.mode)

		// The cap is ReplayGain's clipping prevention, applied only while a ReplayGain gain is in
		// force (the controller's ruling on the B-282 review): the plain amplifier, with ReplayGain
		// Off or for a song without a gain tag, is the same gain on every song, as before.
		val linear: Float = outputGainDb(replayGainDb, settings.rgAmpDb, settings.ampDb).decibelsToLinear()
			.let { if (replayGainDb != null) peakLimited(it, tags?.peak(settings.mode)) else it }
	}

	private class Cached(val settings: Settings, val queueTags: Map<String, DomainReplayGain?>, val streamId: String?, val state: GainState)

	@Volatile
	private var settings = Settings(rgAmpDb = 0f, ampDb = 0f, mode = ReplayGainMode.Off, tags = null)

	@Volatile
	private var queueTags: Map<String, DomainReplayGain?> = emptyMap()

	/** The media id of the stream going through now, from the last [onFlush]; written on the playback thread. */
	@Volatile
	private var streamId: String? = null

	@Volatile
	private var cached: Cached? = null

	// Playback thread only: the gain the last buffer ended at, and whether any audio has gone
	// through since the last flush (before that there is nothing a step could click against).
	private var appliedLinear = 1f
	private var processedSinceFlush = false

	/** The gain state for the inputs as they are now; recomputed only when one of them changed. */
	private fun state(): GainState {
		val settings = settings
		val queueTags = queueTags
		val streamId = streamId
		cached?.let { if (it.settings === settings && it.queueTags === queueTags && it.streamId == streamId) return it.state }
		// The stream's own tags when the queue names it (a null value is "no tags", an answer);
		// otherwise the player's word.
		val tags = if (streamId != null && queueTags.containsKey(streamId)) queueTags[streamId] else settings.tags
		return GainState(settings, tags).also { cached = Cached(settings, queueTags, streamId, it) }
	}

	/** The output gain as a linear factor. */
	val linearGain: Float
		get() = state().linear

	/**
	 * The ReplayGain pre-amp and the plain amplifier (dB), the playing song's tags (null: none) and
	 * the mode, already resolved (`resolveGainMode`: Dynamic is never passed). Any thread; it only
	 * swaps the settings, and the playback thread ramps to the result at its next buffer (B-366).
	 */
	fun update(rgAmpDb: Float, ampDb: Float, metadata: DomainReplayGain?, mode: ReplayGainMode) {
		settings = Settings(rgAmpDb = rgAmpDb, ampDb = ampDb, mode = mode, tags = metadata)
	}

	/**
	 * The tags of every song in the player's queue, by media id (a null value: that song has none).
	 * Published by the player whenever its queue changes, so that [onFlush] can give a new stream its
	 * own gain on the playback thread (B-282). Any thread.
	 */
	fun setQueueTags(tags: Map<String, DomainReplayGain?>) {
		queueTags = tags
	}

	/**
	 * Out of the chain only when nothing can apply a gain: ReplayGain off and the plain
	 * amplifier at 0 dB. media3 reads this only when it sets the chain up (a new format, a seek),
	 * not per song: with ReplayGain on, a song without tags (unity) followed gaplessly by one with
	 * tags would otherwise play the second without its gain. In the chain at unity the samples
	 * are copied unchanged.
	 */
	override fun isActive(): Boolean {
		val state = state()
		return super.isActive() && (state.replayGainEnabled || !isUnityGain(state.linear))
	}

	override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
		if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
			return inputAudioFormat
		}
		throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
	}

	override fun onFlush(streamMetadata: StreamMetadata) {
		// The playback thread's own flush (an item boundary, gapless ones too; a seek; a new
		// configuration). The metadata names the stream about to go through: its song's gain is in
		// force from its first buffer (B-282). Nothing is in flight, so no ramp.
		streamId = streamMediaId(streamMetadata)
		appliedLinear = state().linear
		processedSinceFlush = false
	}

	override fun queueInput(inputBuffer: ByteBuffer) {
		val pos = inputBuffer.position()
		val limit = inputBuffer.limit()
		val outputBuffer = replaceOutputBuffer(limit - pos)

		inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
		outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

		val target = state().linear
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

/**
 * The media id of the item [metadata]'s period belongs to, or null when it names none (media3's
 * `StreamMetadata.DEFAULT`, an empty timeline, an item with no id).
 */
@UnstableApi
internal fun streamMediaId(metadata: StreamMetadata): String? {
	val timeline = metadata.timeline
	val periodUid = metadata.periodUid ?: return null
	if (timeline.isEmpty) return null
	return try {
		val periodIndex = timeline.getIndexOfPeriod(periodUid)
		if (periodIndex == C.INDEX_UNSET) return null
		val windowIndex = timeline.getPeriod(periodIndex, Timeline.Period()).windowIndex
		timeline.getWindow(windowIndex, Timeline.Window()).mediaItem.mediaId.takeIf { it != MediaItem.DEFAULT_MEDIA_ID && it.isNotEmpty() }
	} catch (_: IndexOutOfBoundsException) {
		null
	}
}
