package app.worbus.exoplayer

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import app.worbus.domain.models.DomainExplicitStatus
import app.worbus.domain.models.DomainReplayGain
import app.worbus.domain.models.DomainSong
import app.worbus.domain.models.settings.ReplayGainMode
import app.worbus.shared.publishQueueGainTags
import app.worbus.sourceFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/*
 * B-282, the third review (minor): the queue's tags reached the processor only from the player's
 * applyAudioGain, i.e. at a derive. The append paths (addToQueueLocal, addToQueueSingleLocal,
 * appendToQueue, appendToQueueAndWait) replace the queue with no derive after it, so a song appended
 * as the NEXT one (Add to queue during the last song, a station refill landing there) was unknown at
 * its boundary and started at the previous song's gain. The tags follow the queue itself now.
 */
@UnstableApi
class QueueGainTagsTest {
	private fun song(id: String, gain: Float?) = DomainSong(
		id = id, title = id, artistName = "Artist", artistId = "ar", albumTitle = "Album",
		albumId = "al-$id", parentId = null, comment = null, trackNumber = 1, discNumber = 1,
		isrc = emptyList(), year = null, genre = null, genres = emptyList(), moods = emptyList(),
		duration = 200.seconds, bpm = null, contributors = emptyList(), userRating = null,
		averageRating = null, bitRate = null, bitDepth = null, sampleRate = null,
		audioChannelCount = null,
		replayGain = gain?.let { DomainReplayGain(albumGain = null, albumPeak = null, trackGain = it, trackPeak = null, baseGain = null, fallbackGain = null) },
		fileSize = 0, fileExtension = null,
		mimeType = null, filePath = null, starredAt = null, coverArtId = null, musicBrainzId = null,
		explicitStatus = DomainExplicitStatus.Unknown, artists = emptyList(), albumArtists = emptyList()
	)

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

	private fun db(gain: Float) = 10.0.pow(gain / 20.0).toFloat()

	@Test
	fun aSongAppendedDuringTheLastSongStartsAtItsOwnGain() = runBlocking {
		val last = song("last", -6f)
		val appended = song("appended", 3f)
		val queue = MutableStateFlow(listOf(last))
		val processor = AudioGainProcessor().apply {
			configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
			flush(AudioProcessor.StreamMetadata.DEFAULT)
		}
		val job = publishQueueGainTags(queue, processor::setQueueTags)
		yield()
		// the player's word for the playing song, as applyAudioGain says it
		processor.update(0f, 0f, last.replayGain, ReplayGainMode.Track)

		queue.value = listOf(last, appended) // "Add to queue": no derive follows it
		yield()
		processor.flush(
			AudioProcessor.StreamMetadata.Builder().setTimeline(QueueTimeline(listOf("last", "appended"))).setPeriodUid("period-1").build()
		)
		assertEquals(db(3f), processor.linearGain, 0.0001f, "the appended song's own gain at its boundary, not the last song's")
		job.cancel()
	}

	@Test
	fun theTagsArePublishedFromTheQueueNotFromADerive() {
		val player = sourceFile("src/androidMain/kotlin/app/worbus/shared/MediaPlayer.android.kt").readText()
		assertTrue("publishQueueGainTags(" in player, "the player publishes the tags from its queue")
		val applyGain = player.substringAfter("override fun applyAudioGain() {").substringBefore("\n\t}\n")
		assertFalse("setQueueTags" in applyGain, "and not from applyAudioGain:\n$applyGain")
	}
}
