package app.worbus.domain.manager

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/*
 * Review nit on B-359: the direct route POSTs only, and only AudioMuse 3.7.1 was read to take a POST
 * there; no minimum AudioMuse is documented (the owner ran 3.6.3 in October), and which version
 * added POST is unknown. An AudioMuse that answers 405 to the POST is asked again by GET with the
 * count alone: AudioMuse reads its own configured account when none is given (3.7.1's
 * `app_sonic_fingerprint.py`), so the fallback never puts the password back in a URL.
 */
class FingerprintFallbackTest {
	private val seen = ConcurrentLinkedQueue<String>()
	private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
		createContext("/") { exchange ->
			seen += "${exchange.requestMethod} ${exchange.requestURI}"
			if (exchange.requestMethod == "POST") {
				exchange.sendResponseHeaders(405, -1)
			} else {
				val body = """[{"item_id":"s-1"},{"item_id":"s-2"}]""".toByteArray()
				exchange.responseHeaders.add("Content-Type", "application/json")
				exchange.sendResponseHeaders(200, body.size.toLong())
				exchange.responseBody.use { it.write(body) }
			}
			exchange.close()
		}
		start()
	}
	private val client = HttpClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

	@AfterTest
	fun stop() = server.stop(0)

	@Test
	fun anAudioMuseThatTakesNoPostIsAskedByGetWithoutTheAccount() = runBlocking {
		val url = "http://127.0.0.1:${server.address.port}/api/sonic_fingerprint/generate"
		val ids = runCatching { directFingerprint(client, url, "tok", 50, "alice", "hunter2").map { it.itemId } }.getOrDefault(emptyList())
		assertEquals(listOf("s-1", "s-2"), ids, "the GET's answer is used: ${seen.toList()}")
		assertFalse(seen.any { "hunter2" in it || "navidrome_password" in it || "alice" in it }, "no account in any URL: ${seen.toList()}")
	}
}
