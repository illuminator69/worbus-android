package app.worbus.domain.manager

import app.worbus.data.database.entities.DownloadEntity
import app.worbus.data.database.entities.DownloadStatus
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Q-165: a re-fetch of a changed song goes to a scratch file, is checked whole, and only then takes
 * the old copy's place in one rename; an interrupted one leaves the old copy, its row and no scratch
 * file. Real files, the real transfer (`fetchVerified`) and the real rename (`replaceAtomically`),
 * against a local stand-in for Navidrome's stream.
 */
class RefetchOverTest {
	private val newBytes = ByteArray(4096) { (it * 7).toByte() }
	private val oldBytes = ByteArray(3000) { 1 }
	@Volatile private var mode = "whole"
	private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
		createContext("/") { exchange ->
			when (mode) {
				"whole" -> {
					exchange.responseHeaders.add("Content-Type", "audio/flac")
					exchange.sendResponseHeaders(200, newBytes.size.toLong())
					exchange.responseBody.use { it.write(newBytes) }
				}
				"cut" -> {
					// Promises the whole file, sends half, and the connection ends.
					exchange.responseHeaders.add("Content-Type", "audio/flac")
					exchange.sendResponseHeaders(200, newBytes.size.toLong())
					exchange.responseBody.write(newBytes, 0, newBytes.size / 2)
					exchange.responseBody.flush()
					exchange.close()
				}
				"500" -> { exchange.sendResponseHeaders(500, -1); exchange.close() }
				else -> {
					val body = """{"subsonic-response":{"status":"failed","error":{"code":70}}}""".toByteArray()
					exchange.responseHeaders.add("Content-Type", "application/json")
					exchange.sendResponseHeaders(200, body.size.toLong())
					exchange.responseBody.use { it.write(body) }
				}
			}
		}
		start()
	}
	private val client = HttpClient(OkHttp) { install(HttpTimeout) }
	private val dir: File = Files.createTempDirectory("refetch").toFile()

	@AfterTest
	fun stop() {
		server.stop(0)
		client.close()
		dir.deleteRecursively()
	}

	private val files = object : DownloadFiles {
		override suspend fun save(path: String, channel: ByteReadChannel) {
			File(path).outputStream().use { channel.copyTo(it) }
		}
		override fun size(path: String): Long = File(path).length()
		override fun replace(tempPath: String, path: String): Boolean = replaceAtomically(File(tempPath), File(path))
		override fun delete(path: String) { File(path).delete() }
		override fun move(from: String, to: String): Boolean = moveIfAbsent(File(from), File(to))
	}

	private val url get() = "http://127.0.0.1:${server.address.port}/rest/stream.view?id=s1"

	private inner class Case(newName: String = "s1.flac") {
		val old = File(dir, "s1.flac").apply { writeBytes(oldBytes) }
		val target = File(dir, newName)
		val temp = File(dir, "$newName.part")
		var row: DownloadEntity? = DownloadEntity(
			songId = "s1", status = DownloadStatus.DOWNLOADED, progress = 1f, filePath = old.absolutePath,
			fileSize = oldBytes.size.toLong(), createdAt = 10L, updatedAt = 10L
		)
		val written = mutableListOf<DownloadEntity>()
		fun run(): Boolean = runBlocking {
			refetchOver(
				row = row!!,
				newPath = target.absolutePath,
				tempPath = temp.absolutePath,
				files = files,
				transfer = { tempPath -> fetchVerified(client, url, tempPath, files) },
				currentRow = { row },
				writeRow = { written += it },
				now = 99L
			)
		}
	}

	@Test
	fun aWholeNewCopyTakesTheOldOnesPlace() {
		val case = Case()
		assertTrue(case.run())
		assertContentEquals(newBytes, case.target.readBytes(), "the new copy is in place")
		assertFalse(case.temp.exists(), "no scratch file left")
		val row = case.written.single()
		assertEquals(case.target.absolutePath, row.filePath)
		assertEquals(newBytes.size.toLong(), row.fileSize)
		assertEquals(DownloadStatus.DOWNLOADED, row.status)
		assertEquals(10L, row.createdAt, "the download keeps its history")
		assertEquals(99L, row.updatedAt)
	}

	@Test
	fun aNewSuffixMovesThePathAndDropsTheOldFile() {
		val case = Case(newName = "s1.opus")
		assertTrue(case.run())
		assertContentEquals(newBytes, case.target.readBytes())
		assertFalse(case.old.exists(), "the old copy goes once the row names the new one")
		assertEquals(case.target.absolutePath, case.written.single().filePath)
	}

	@Test
	fun anInterruptedRefetchLeavesTheOldCopy() {
		for (m in listOf("cut", "500", "json")) {
			mode = m
			val case = Case()
			assertFalse(case.run(), m)
			assertContentEquals(oldBytes, case.old.readBytes(), "$m: the old copy is untouched")
			assertFalse(case.temp.exists(), "$m: no scratch file left")
			assertEquals(emptyList(), case.written, "$m: the row still names the old copy")
		}
	}

	@Test
	fun aDownloadDeletedMeanwhileIsNotBroughtBack() {
		val case = Case()
		// The user deleted it while the new copy was on its way: the file and the row are gone.
		case.old.delete()
		val kept = case.row!!
		val result = runBlocking {
			refetchOver(
				row = kept, newPath = case.target.absolutePath, tempPath = case.temp.absolutePath, files = files,
				transfer = { tempPath -> fetchVerified(client, url, tempPath, files) },
				currentRow = { null }, writeRow = { case.written += it }, now = 99L
			)
		}
		assertFalse(result)
		assertFalse(case.target.exists(), "no file comes back for a download the user deleted")
		assertFalse(case.temp.exists())
		assertEquals(emptyList(), case.written)
	}

	@Test
	fun aFailedRenameLeavesTheTargetAlone() {
		val target = File(dir, "kept.flac").apply { writeBytes(oldBytes) }
		assertFalse(replaceAtomically(File(dir, "absent.part"), target))
		assertContentEquals(oldBytes, target.readBytes())
		val temp = File(dir, "new.part").apply { writeBytes(newBytes) }
		assertTrue(replaceAtomically(temp, target), "over an existing file, in one rename")
		assertContentEquals(newBytes, target.readBytes())
		assertNull(dir.listFiles()!!.firstOrNull { it.name == "new.part" })
	}
}
