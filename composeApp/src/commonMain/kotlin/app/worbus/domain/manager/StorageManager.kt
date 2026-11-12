package app.worbus.domain.manager


import io.ktor.utils.io.ByteReadChannel

expect class StorageManager {
	fun getDownloadPath(songId: String, extension: String): String

	/**
	 * Scratch path for an in-flight download. Audio is streamed HERE and only moved onto the real
	 * path once the transfer finishes (see [finalizeFile]). A download killed mid-flight — cancel,
	 * process death, network drop — used to leave a TRUNCATED file sitting at the real path, which
	 * the app then treated as a complete offline copy and tried to play.
	 */
	fun getTempDownloadPath(songId: String, extension: String): String

	/** Atomically move a finished temp file onto its real path. False if the move failed. */
	fun finalizeFile(tempPath: String, path: String): Boolean

	/**
	 * Q-165: put a finished temp file over [path] in ONE rename, which replaces a file already there
	 * atomically (a reader holding the old one keeps reading it). False, with [tempPath] removed, when
	 * the rename fails: never a copy over the old file, which could leave half a file there.
	 */
	fun replaceFile(tempPath: String, path: String): Boolean

	fun deleteFile(path: String): Boolean
	fun getFileSize(path: String): Long
	suspend fun saveFile(path: String, channel: ByteReadChannel)
	fun clearDownloads()
}
