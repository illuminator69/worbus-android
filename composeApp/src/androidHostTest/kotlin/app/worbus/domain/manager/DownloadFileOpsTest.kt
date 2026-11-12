package app.worbus.domain.manager

import app.worbus.data.database.entities.DownloadEntity
import app.worbus.data.database.entities.DownloadStatus
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * The Q-165 follow-up on real files. Major 1: a re-keyed download is carried by a rename to
 * `<new id>.<ext>` (the name `AdoptDownloadsOnCreate` reads the id from) and its row re-keyed; never
 * over a file already there. Minor 4: a scratch file a killed process left is swept at start, and one a
 * transfer of this process is writing is not.
 */
class DownloadFileOpsTest {
	private val dir: File = Files.createTempDirectory("fileops").toFile()

	@AfterTest
	fun clean() { dir.deleteRecursively() }

	private val files = object : DownloadFiles {
		override suspend fun save(path: String, channel: ByteReadChannel) = error("unused")
		override fun size(path: String): Long = File(path).length()
		override fun replace(tempPath: String, path: String): Boolean = replaceAtomically(File(tempPath), File(path))
		override fun delete(path: String) { File(path).delete() }
		override fun move(from: String, to: String): Boolean = moveIfAbsent(File(from), File(to))
	}

	private val bytes = ByteArray(1234) { it.toByte() }

	@Test
	fun aMoveRenamesTheFileAndReKeysTheRow() {
		val old = File(dir, "s1.flac").apply { writeBytes(bytes) }
		val row = DownloadEntity(songId = "s1", status = DownloadStatus.DOWNLOADED, progress = 1f, filePath = old.absolutePath,
			fileSize = 1234L, maxBitRate = 320, format = "opus", sourcePolicy = "album", createdAt = 10L, updatedAt = 10L)
		val inserted = mutableListOf<DownloadEntity>()
		val deleted = mutableListOf<String>()
		val target = File(dir, "n1.flac")
		val ok = runBlocking { moveDownload(row, "n1", target.absolutePath, files, { n, o -> inserted += n; deleted += o }, now = 99L) }
		assertTrue(ok)
		assertFalse(old.exists())
		assertContentEquals(bytes, target.readBytes())
		val moved = inserted.single()
		assertEquals(row.copy(songId = "n1", filePath = target.absolutePath, updatedAt = 99L), moved, "the same download, under the new id")
		assertEquals(listOf("s1"), deleted)
		assertEquals("n1", target.name.substringBeforeLast('.'), "adoption reads this id from the name")
	}

	@Test
	fun aMoveNeverOverwrites() {
		val old = File(dir, "s1.flac").apply { writeBytes(bytes) }
		val there = File(dir, "n1.flac").apply { writeBytes(byteArrayOf(9)) }
		val row = DownloadEntity(songId = "s1", status = DownloadStatus.DOWNLOADED, filePath = old.absolutePath)
		val inserted = mutableListOf<DownloadEntity>()
		val ok = runBlocking { moveDownload(row, "n1", there.absolutePath, files, { n, _ -> inserted += n }, now = 99L) }
		assertFalse(ok)
		assertContentEquals(bytes, old.readBytes())
		assertContentEquals(byteArrayOf(9), there.readBytes())
		assertEquals(emptyList(), inserted)
	}

	@Test
	fun aMoveCutShortAfterItsRenameIsFinished() {
		// Follow-up 2, minor 4: killed after the rename, before the rows. The old row names a file that is
		// gone, `<new>.<ext>` is there with no row: the next pass's move finishes it.
		val target = File(dir, "n1.flac").apply { writeBytes(bytes) }
		val row = DownloadEntity(songId = "s1", status = DownloadStatus.DOWNLOADED, filePath = File(dir, "s1.flac").absolutePath)
		val rekeyed = mutableListOf<Pair<String, String>>()
		val ok = runBlocking { moveDownload(row, "n1", target.absolutePath, files, { n, o -> rekeyed += n.songId to o }, now = 99L) }
		assertTrue(ok)
		assertEquals(listOf("n1" to "s1"), rekeyed)
		assertContentEquals(bytes, target.readBytes())
	}

	@Test
	fun aSuccessorsUnfinishedRowDoesNotBlockTheMove() {
		// Follow-up 2, minor 5: a QUEUED or FAILED row of the new id (a playlist policy queued it after the
		// re-key) is replaced by the moved download; only a finished one blocks it.
		for (status in listOf(DownloadStatus.QUEUED, DownloadStatus.FAILED, DownloadStatus.DOWNLOADING, DownloadStatus.NOT_DOWNLOADED)) {
			assertFalse(moveBlockedBy(DownloadEntity(songId = "n1", status = status)), "$status")
		}
		assertFalse(moveBlockedBy(null))
		assertTrue(moveBlockedBy(DownloadEntity(songId = "n1", status = DownloadStatus.DOWNLOADED)))
	}

	@Test
	fun stalePartsAreSweptAndLiveOnesKept() {
		val start = System.currentTimeMillis()
		val stale = File(dir, "s1.flac.part").apply { writeBytes(bytes); setLastModified(start - 60_000L) }
		val live = File(dir, "s2.flac.part").apply { writeBytes(bytes); setLastModified(start + 1_000L) }
		val kept = File(dir, "s3.flac").apply { writeBytes(bytes); setLastModified(start - 60_000L) }
		assertEquals(1, sweepScratchFiles(dir, before = start))
		assertFalse(stale.exists(), "a scratch file older than this process is a killed transfer's")
		assertTrue(live.exists(), "one this process is writing stays")
		assertTrue(kept.exists(), "a finished download is never swept")
	}
}
