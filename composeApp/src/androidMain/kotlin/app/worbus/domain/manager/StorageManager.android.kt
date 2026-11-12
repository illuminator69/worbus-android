package app.worbus.domain.manager

import android.content.Context
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

actual class StorageManager(
	private val context: Context
) {
	private val dispatcher = Dispatchers.IO

	actual fun getDownloadPath(songId: String, extension: String): String {
		val dir = File(context.filesDir, "downloads")
		if (!dir.exists()) dir.mkdirs()
		return File(dir, "$songId.$extension").absolutePath
	}

	actual fun getTempDownloadPath(songId: String, extension: String): String {
		val dir = File(context.filesDir, "downloads")
		if (!dir.exists()) dir.mkdirs()
		return File(dir, "$songId.$extension.part").absolutePath
	}

	actual fun finalizeFile(tempPath: String, path: String): Boolean {
		val temp = File(tempPath)
		if (!temp.exists()) return false
		val target = File(path)
		// renameTo is atomic within a filesystem, and both paths are in the same directory —
		// so the real path only ever exists as a COMPLETE file.
		if (target.exists()) target.delete()
		if (temp.renameTo(target)) return true
		// Rename can still fail (rare, e.g. an FS that refuses it); fall back to a copy, then make
		// sure the partial never lingers.
		return try {
			temp.copyTo(target, overwrite = true)
			temp.delete()
			true
		} catch (_: Exception) {
			temp.delete()
			false
		}
	}

	actual fun replaceFile(tempPath: String, path: String): Boolean = replaceAtomically(File(tempPath), File(path))

	actual fun deleteFile(path: String): Boolean {
		return File(path).delete()
	}

	actual fun getFileSize(path: String): Long {
		return try {
			File(path).length()
		} catch (_: Exception) {
			0L
		}
	}

	actual suspend fun saveFile(path: String, channel: ByteReadChannel) {
		withContext(dispatcher) {
			FileOutputStream(path).use { outputStream ->
				channel.copyTo(outputStream)
			}
		}
	}

	actual fun clearDownloads() {
		val dir = File(context.filesDir, "downloads")
		if (dir.exists()) {
			dir.listFiles()?.forEach { it.deleteRecursively() }
		}
	}
}

/**
 * Q-165: [temp] over [target] by one `rename(2)`, which on Android's filesystems replaces an existing
 * target atomically: the target is the old file or the new one, never neither and never half. (Not
 * `java.nio.file.Files.move`: minSdk is 24.) A failed rename removes [temp] and leaves [target] alone.
 */
internal fun replaceAtomically(temp: File, target: File): Boolean {
	if (!temp.exists()) return false
	if (temp.renameTo(target)) return true
	temp.delete()
	return false
}

/**
 * Minor 4: scratch files (`*.part`) in [dir] last written before [before] (a process killed
 * mid-transfer leaves one, and nothing else removes it). Seam: removes nothing yet.
 */
internal fun sweepScratchFiles(dir: File, before: Long): Int = 0

/** Major 1: rename [from] to [to] only when [to] does not exist. Seam: moves nothing yet. */
internal fun moveIfAbsent(from: File, to: File): Boolean = false
