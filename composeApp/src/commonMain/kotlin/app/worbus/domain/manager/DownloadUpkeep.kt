package app.worbus.domain.manager

import app.worbus.data.database.entities.DownloadEntity
import app.worbus.data.database.entities.DownloadOriginEntity
import app.worbus.data.database.entities.SongEntity
import io.ktor.utils.io.ByteReadChannel
import kotlin.time.Clock

/*
 * Q-165, the owner's ruling (2026-11-12): a downloaded song that CHANGED on the server (re-tagged, or
 * its file replaced) is fetched again in the background while online, the old copy playing until the
 * new one is whole; a song GONE from the server has its copy removed and its record dropped, and a
 * Navidrome saying "not found" for many songs at once wipes nothing. Seam: the shapes, no behaviour.
 */

/** What Navidrome answered `getSong` for one id. */
enum class SongAnswer {
	/** It has the song. */
	PRESENT,

	/** Subsonic error 70, "the requested data was not found": Navidrome's own word that it is gone. */
	GONE,

	/** Anything else: no answer, a 404 or a 5xx from something in front of it, a refusal of the session. */
	NO_ANSWER
}

/** What Navidrome's answer (or its failure) for one `getSong` means. Seam. */
fun songAnswerOf(error: Throwable?): SongAnswer = SongAnswer.NO_ANSWER

/** A library pull as the upkeep reads it: [listed] is the song ids of a COMPLETE and stable full pull (B-037), else null. */
data class PullSeen(val listed: Set<String>?)

/** What one upkeep pass did, for its log line and the tests. */
data class UpkeepReport(
	val baselined: Int = 0,
	val refetched: List<String> = emptyList(),
	val markedGone: List<String> = emptyList(),
	val removed: List<String> = emptyList(),
	/** The gone half held back: too many at once, or Navidrome did not answer. */
	val goneHeld: String = "",
	/** The pass did nothing at all, and why ("offline"). */
	val skipped: String = ""
)

/** This device's downloads, as the upkeep needs them. [DownloadManager] in the app. */
interface UpkeepDownloads {
	/** The DOWNLOADED rows that have a file. */
	suspend fun downloaded(): List<DownloadEntity>

	/** Fetch [song] again over [row]'s file, in the background (an atomic swap; the old copy stays until then). */
	fun refetch(song: SongEntity, row: DownloadEntity)

	/** Delete the file and the row. */
	suspend fun remove(songId: String)
}

/** The `DownloadOrigin` table ([DownloadOriginEntity]). */
interface DownloadOriginStore {
	suspend fun all(): List<DownloadOriginEntity>
	suspend fun put(rows: List<DownloadOriginEntity>)
	suspend fun remove(songIds: List<String>)
}

/** What a downloaded file was made from: the song as Navidrome describes it. Seam. */
fun songOrigin(song: SongEntity): String = ""

class DownloadUpkeep(
	private val store: DownloadOriginStore,
	private val downloads: UpkeepDownloads,
	private val songsById: suspend (List<String>) -> List<SongEntity>,
	private val askSong: suspend (String) -> SongAnswer,
	private val isOnline: () -> Boolean,
	private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) {
	/** One pass after a pull. Seam. */
	suspend fun afterPull(pull: PullSeen): UpkeepReport = UpkeepReport(skipped = "seam")
}

/** The file operations a download needs, so the swap can be run against real files in a host test. */
interface DownloadFiles {
	suspend fun save(path: String, channel: ByteReadChannel)
	fun size(path: String): Long

	/** Put [tempPath] over [path] in one rename; false, and [tempPath] gone, when it cannot. Never a copy. */
	fun replace(tempPath: String, path: String): Boolean
	fun delete(path: String)
}

/**
 * Fetch a changed song again over its download ([row]): into [tempPath] by [transfer] (which writes
 * and checks the file, and throws when it is not whole), then over [newPath] in one rename, then the
 * row. True when the new copy is in place. Seam.
 */
internal suspend fun refetchOver(
	row: DownloadEntity,
	newPath: String,
	tempPath: String,
	files: DownloadFiles,
	transfer: suspend (tempPath: String) -> Unit,
	currentRow: suspend () -> DownloadEntity?,
	writeRow: suspend (DownloadEntity) -> Unit,
	now: Long
): Boolean = false
