package app.worbus.domain.manager

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * Q-165: a download is only judged gone on Navidrome's own word, Subsonic error 70 for `getSong`.
 * A 404 or a 5xx from whatever sits in front of it, another Subsonic error, or nothing at all is no
 * answer. Run through the app's real session client against a local stand-in.
 */
class SongAnswerTest {
	@Volatile private var reply: Pair<Int, String> = 200 to ""
	private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
		createContext("/") { exchange ->
			val (status, body) = reply
			val bytes = body.toByteArray()
			exchange.responseHeaders.add("Content-Type", "application/json")
			exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
			if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
		}
		start()
	}

	@AfterTest
	fun stop() = server.stop(0)

	private fun failed(code: Int, message: String) =
		"""{"subsonic-response":{"status":"failed","version":"1.16.1","type":"navidrome","serverVersion":"0.64.2","openSubsonic":true,"error":{"code":$code,"message":"$message"}}}"""

	private fun answer(): SongAnswer {
		val settings = MemorySettings()
		val secrets = testSecrets(settings)
		settings.putString("instanceUrl", "http://127.0.0.1:${server.address.port}")
		settings.putString("username", "alice")
		secrets.navidromePassword = "hunter2"
		val session = SessionManager(settings, PreferenceManager(settings, secrets), secrets, ServerReachability())
		val error = runBlocking { runCatching { session.api.getSong("s-1") }.exceptionOrNull() }
		return songAnswerOf(error)
	}

	@Test
	fun navidromesNotFoundIsGone() {
		reply = 200 to failed(70, "Song not found")
		assertEquals(SongAnswer.GONE, answer())
	}

	@Test
	fun anHttp404IsNoAnswer() {
		reply = 404 to "<html>not here</html>"
		assertEquals(SongAnswer.NO_ANSWER, answer())
	}

	@Test
	fun a503IsNoAnswer() {
		reply = 503 to ""
		assertEquals(SongAnswer.NO_ANSWER, answer())
	}

	@Test
	fun anotherSubsonicErrorIsNoAnswer() {
		reply = 200 to failed(40, "Wrong username or password")
		assertEquals(SongAnswer.NO_ANSWER, answer())
		reply = 200 to failed(0, "Generic error")
		assertEquals(SongAnswer.NO_ANSWER, answer())
	}

	@Test
	fun nothingListeningIsNoAnswer() {
		server.stop(0)
		assertEquals(SongAnswer.NO_ANSWER, answer())
	}

	@Test
	fun noErrorIsPresent() {
		assertEquals(SongAnswer.PRESENT, songAnswerOf(null))
	}
}
