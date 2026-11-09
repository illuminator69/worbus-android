package app.worbus.exoplayer

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import app.worbus.domain.models.DomainReplayGain
import app.worbus.domain.models.settings.ReplayGainMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * B-278: the processor driven as media3 drives it, on the JVM. Unity leaves the samples alone
 * (it used to add 1 dB and clip), a song without tags drops the gain the song before had from the
 * update on, the ReplayGain pre-amp applies only with ReplayGain and the plain amp otherwise; one
 * update flushes at most once, and samples are rounded.
 */
@UnstableApi
class AudioGainProcessorTest {
	private val format = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)

	private fun processor() = AudioGainProcessor().apply {
		configure(format)
		flush(AudioProcessor.StreamMetadata.DEFAULT)
	}

	private fun rg(track: Float? = null, album: Float? = null) =
		DomainReplayGain(albumGain = album, albumPeak = null, trackGain = track, trackPeak = null, baseGain = null, fallbackGain = null)

	/** Every 16-bit value from full-scale negative to full-scale positive, in steps. */
	private val samples: ShortArray = (Short.MIN_VALUE..Short.MAX_VALUE step 7).map { it.toShort() }.toShortArray() +
		shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 0, 1, -1)

	private fun run(p: AudioGainProcessor, input: ShortArray): ShortArray {
		val bytes = ByteBuffer.allocateDirect(input.size * 2).order(ByteOrder.LITTLE_ENDIAN)
		bytes.asShortBuffer().put(input)
		p.queueInput(bytes)
		assertFalse(bytes.hasRemaining(), "the whole input is consumed")
		val out = p.output.order(ByteOrder.LITTLE_ENDIAN)
		val result = ShortArray(out.remaining() / 2)
		out.asShortBuffer().get(result)
		return result
	}

	private fun bytesOf(s: ShortArray): ByteArray {
		val b = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
		b.asShortBuffer().put(s)
		return b.array()
	}

	/** What the player does at each song and on a settings change: one call with everything. */
	private fun AudioGainProcessor.set(
		meta: DomainReplayGain?,
		mode: ReplayGainMode,
		rgAmp: Float = 0f,
		amp: Float = 0f
	) = update(rgAmp, amp, meta, mode)

	@Test
	fun replayGainOffAndNoAmpIsUnityAndOutOfTheChain() {
		val p = processor()
		p.set(null, ReplayGainMode.Off)
		assertEquals(1f, p.linearGain)
		assertFalse(p.isActive)
	}

	@Test
	fun theDefaultIsUnityAndOutOfTheChain() {
		val p = processor()
		assertEquals(1f, p.linearGain)
		assertFalse(p.isActive, "a fresh processor added 1 dB before B-278")
	}

	@Test
	fun unityPassesEverySampleBitForBit() {
		val p = processor()
		p.set(null, ReplayGainMode.Track) // on, but this song has no tags
		assertEquals(1f, p.linearGain)
		val out = run(p, samples)
		assertContentEquals(bytesOf(samples), bytesOf(out))
	}

	@Test
	fun replayGainOnKeepsTheProcessorInTheChainEvenAtUnity() {
		// media3 reads isActive only when it sets the chain up, so a tagged song after an
		// untagged one must still find the processor there.
		val p = processor()
		p.set(null, ReplayGainMode.Track)
		assertTrue(p.isActive)
		assertEquals(1f, p.linearGain)
	}

	@Test
	fun aSongWithoutTagsDoesNotKeepThePreviousSongsGain() {
		// From the update on. What reached the processor before it (up to the AudioTrack's buffer
		// after a transition) still went through at the old gain: B-282.
		val p = processor()
		p.set(rg(track = -6f), ReplayGainMode.Track)
		assertEquals(0.501f, p.linearGain, 0.001f)
		p.set(null, ReplayGainMode.Track)
		assertEquals(1f, p.linearGain)
	}

	@Test
	fun theReplayGainPreAmpAppliesOnlyWithReplayGain() {
		val p = processor()
		p.set(null, ReplayGainMode.Track, rgAmp = 6f, amp = 0f)
		assertEquals(1f, p.linearGain, "no tags: the plain amp (0 dB), not the pre-amp")
		p.set(rg(track = -6f), ReplayGainMode.Track, rgAmp = 6f, amp = 0f)
		assertEquals(1f, p.linearGain, 0.0001f) // -6 + 6
	}

	@Test
	fun thePlainAmpAppliesWithoutReplayGain() {
		val p = processor()
		p.set(rg(track = 3f), ReplayGainMode.Off, rgAmp = 6f, amp = -6f)
		assertEquals(0.501f, p.linearGain, 0.001f) // -6 dB, the song's tags ignored
		assertTrue(p.isActive)
	}

	@Test
	fun offThenOnAppliesThePlayingSongsTags() {
		val p = processor()
		val tags = rg(track = -6f, album = -3f)
		p.set(tags, ReplayGainMode.Off)
		assertEquals(1f, p.linearGain)
		p.set(tags, ReplayGainMode.Album)
		assertEquals(0.708f, p.linearGain, 0.001f)
	}

	@Test
	fun aChangeMidStreamRampsAcrossTheNextBufferThenHolds() {
		// B-366: no flush on an update; the playback thread steps to the new gain over one buffer
		// (no click), and the buffer after it is at the new gain throughout.
		val p = processor()
		p.set(null, ReplayGainMode.Off, amp = 0f)
		run(p, ShortArray(64) { 1000 }) // audio has gone through at unity
		p.set(null, ReplayGainMode.Off, amp = -6.0206f) // x0.5
		val ramp = run(p, ShortArray(64) { 1000 })
		assertTrue(ramp.first() > 500 && ramp.first() < 1000, "the ramp starts near the old gain: ${ramp.first()}")
		assertEquals(500, ramp.last().toInt(), "and ends at the new one")
		assertTrue(ramp.toList().zipWithNext().all { (a, b) -> b <= a }, "monotonic")
		assertTrue(run(p, ShortArray(64) { 1000 }).all { it.toInt() == 500 })
	}

	@Test
	fun samplesAreRoundedNotTruncated() {
		// B-278 review: toInt() truncated toward zero, a bias of up to one step on every sample.
		val p = processor()
		p.set(null, ReplayGainMode.Off, amp = -6f) // x0.501
		// 1.504, -1.504, 50.62, -50.62: truncated these were 1, -1, 50, -50.
		assertContentEquals(shortArrayOf(2, -2, 51, -51, 0), run(p, shortArrayOf(3, -3, 101, -101, 0)))
	}

	@Test
	fun aGainScalesAndClipsAtFullScale() {
		val p = processor()
		p.set(null, ReplayGainMode.Off, amp = 6.0206f) // x2
		assertTrue(p.isActive)
		val out = run(p, shortArrayOf(0, 100, -100, 20_000, -20_000, Short.MAX_VALUE, Short.MIN_VALUE))
		val expected = shortArrayOf(0, 200, -200, Short.MAX_VALUE, Short.MIN_VALUE, Short.MAX_VALUE, Short.MIN_VALUE)
		assertContentEquals(expected, out)
	}

	@Test
	fun onlySixteenBitIsTaken() {
		val p = AudioGainProcessor()
		val float = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_FLOAT)
		var refused = false
		try {
			p.configure(float)
		} catch (_: AudioProcessor.UnhandledAudioFormatException) {
			refused = true
		}
		assertTrue(refused)
	}

	// --- B-282 / B-366 ------------------------------------------------------------------------

	@Test
	fun aPositiveGainNeverPushesTheSongsPeakPastFullScale() {
		// B-282: +6 dB on a song whose loudest sample is 0.99 of full scale clipped it; the
		// song's own peak says how much gain it can take (ReplayGain's clipping prevention).
		val p = processor()
		val tags = DomainReplayGain(albumGain = null, albumPeak = null, trackGain = 6f, trackPeak = 0.99f, baseGain = null, fallbackGain = null)
		p.set(tags, ReplayGainMode.Track)
		assertTrue(p.linearGain * 0.99f <= 1.0001f, "gain ${p.linearGain} pushes the 0.99 peak past full scale")
		val loudest = (0.99f * Short.MAX_VALUE).toInt().toShort()
		val out = run(p, shortArrayOf(loudest, (-loudest).toShort()))
		assertTrue(out[0] < Short.MAX_VALUE || out[0] == Short.MAX_VALUE && loudest * p.linearGain <= Short.MAX_VALUE + 0.5f, "no clipped sample")
	}

	@Test
	fun thePlainAmplifierIsNotCappedByTheSongsPeak() {
		// Review finding 5 (the controller's ruling): the peak cap is ReplayGain's clipping
		// prevention. With ReplayGain Off the plain amplifier behaves as before B-282: +6 dB is +6 dB
		// on every song, a tagged modern master (peak about 1.0) as much as an untagged one.
		val sixDb = 6f.let { 10.0.pow(it / 20.0).toFloat() }
		val tagged = DomainReplayGain(albumGain = -8f, albumPeak = 1.0f, trackGain = -9f, trackPeak = 0.99f, baseGain = null, fallbackGain = null)
		val p = processor()
		p.set(tagged, ReplayGainMode.Off, amp = 6f)
		assertEquals(sixDb, p.linearGain, 0.0001f, "ReplayGain Off: the tagged song gets the whole amplifier")
		p.set(null, ReplayGainMode.Off, amp = 6f)
		assertEquals(sixDb, p.linearGain, 0.0001f, "and the untagged one the same")
		// ReplayGain on, a song without a gain tag but with a peak: the plain amplifier again, as before.
		val peakOnly = DomainReplayGain(albumGain = null, albumPeak = null, trackGain = null, trackPeak = 0.99f, baseGain = null, fallbackGain = null)
		p.set(peakOnly, ReplayGainMode.Track, amp = 6f)
		assertEquals(sixDb, p.linearGain, 0.0001f, "no ReplayGain in force: the amplifier is not capped")
	}

	@Test
	fun aGainChangeDoesNotDiscardWhatThePlaybackThreadAlreadyProduced() {
		// B-366: update() is called on the main thread (the player's applyAudioGain) and used to
		// flush() the processor there, while the playback thread was between queueInput and
		// getOutput: the buffer it had just produced was thrown away (and the two threads wrote
		// the processor's buffers at once).
		val p = processor()
		p.set(null, ReplayGainMode.Off, amp = -6.0206f) // x0.5
		val input = ShortArray(256) { 1000 }
		val bytes = ByteBuffer.allocateDirect(input.size * 2).order(ByteOrder.LITTLE_ENDIAN)
		bytes.asShortBuffer().put(input)
		p.queueInput(bytes) // the playback thread's step; its output not read yet

		p.set(null, ReplayGainMode.Off, amp = 0f) // the main thread's update at a transition

		val out = p.output.order(ByteOrder.LITTLE_ENDIAN)
		assertEquals(input.size * 2, out.remaining(), "the produced buffer survives the update")
		assertEquals(500, out.asShortBuffer().get(0).toInt())
	}

	// --- B-282, the start of a song (the open half) ----------------------------------------------

	/** The player's playlist as media3 hands it to the processor: one window and period per song. */
	private class QueueTimeline(private val ids: List<String>) : Timeline() {
		override fun getWindowCount() = ids.size
		override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
			window.also { it.mediaItem = MediaItem.Builder().setMediaId(ids[windowIndex]).build() }
		override fun getPeriodCount() = ids.size
		override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
			period.also { it.windowIndex = periodIndex; it.uid = "period-$periodIndex" }
		override fun getIndexOfPeriod(uid: Any): Int = (uid as? String)?.removePrefix("period-")?.toIntOrNull() ?: C.INDEX_UNSET
		override fun getUidOfPeriod(periodIndex: Int): Any = "period-$periodIndex"
	}

	private fun boundaryTo(ids: List<String>, index: Int) = AudioProcessor.StreamMetadata.Builder()
		.setTimeline(QueueTimeline(ids))
		.setPeriodUid("period-$index")
		.build()

	private fun db(gain: Float) = 10.0.pow(gain / 20.0).toFloat()

	@Test
	fun theNewSongsGainIsInForceFromItsFirstBufferAtAGaplessBoundary() {
		// B-282 (the VM walk, 2026-11-09): at a same-format boundary media3 1.11 calls onConfigure and
		// onFlush(StreamMetadata) on the playback thread, the period being the NEW song's, 0.5-0.6 s
		// before the player's update() on main. Until then the new song's first buffers went
		// through at the previous song's gain.
		val p = processor()
		val queue = listOf("a", "b", "c")
		p.setQueueTags(mapOf("a" to rg(track = -6f), "b" to rg(track = 3f), "c" to null))
		p.update(0f, 0f, rg(track = -6f), ReplayGainMode.Track)
		assertEquals(db(-6f), p.linearGain, 0.0001f)

		p.flush(boundaryTo(queue, 1))
		assertEquals(db(3f), p.linearGain, 0.0001f, "b's gain from b's first buffer, before main says so")
		val half = (0.5f * Short.MAX_VALUE).toInt().toShort()
		assertEquals((half * db(3f)).roundToInt().toShort(), run(p, shortArrayOf(half, half))[0], "no ramp from a's gain")

		// main's update for b, half a second later, changes nothing; nor does a late one still naming a
		p.update(0f, 0f, rg(track = 3f), ReplayGainMode.Track)
		assertEquals(db(3f), p.linearGain, 0.0001f)
		p.update(0f, 0f, rg(track = -6f), ReplayGainMode.Track)
		assertEquals(db(3f), p.linearGain, 0.0001f, "an update for a song that is not the stream's does not take its gain back")

		// c has no tags: the plain amplifier (0 dB here), not b's gain
		p.flush(boundaryTo(queue, 2))
		assertEquals(1f, p.linearGain, 0.0001f)
	}

	@Test
	fun aSettingsChangeStillAppliesToTheStreamsSong() {
		val p = processor()
		p.setQueueTags(mapOf("a" to rg(track = -6f), "b" to rg(track = 3f)))
		p.flush(boundaryTo(listOf("a", "b"), 1))
		p.update(2f, 0f, rg(track = 3f), ReplayGainMode.Track)
		assertEquals(db(5f), p.linearGain, 0.0001f, "the pre-amp moves")
		p.update(2f, 4f, rg(track = 3f), ReplayGainMode.Off)
		assertEquals(db(4f), p.linearGain, 0.0001f, "ReplayGain off: the plain amplifier")
	}

	@Test
	fun aStreamTheQueueDoesNotNameKeepsTheMainThreadsTags() {
		// An item added by a path that published no tags (or the timeline media3 sends before a
		// queue): the player's update() is the only word, as before.
		val p = processor()
		p.setQueueTags(mapOf("a" to rg(track = -6f)))
		p.flush(boundaryTo(listOf("a", "x"), 1))
		p.update(0f, 0f, rg(track = 2f), ReplayGainMode.Track)
		assertEquals(db(2f), p.linearGain, 0.0001f)
	}
}
