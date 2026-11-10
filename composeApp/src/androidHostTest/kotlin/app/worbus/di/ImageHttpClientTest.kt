package app.worbus.di

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * B-363: the image client adds the headers its source answers for each request, once, without
 * doubling a header the request already set; with no Koin (before the app starts, or in a test)
 * the shared client's source answers nothing and the request still goes out.
 */
class ImageHttpClientTest {
	private val seen = ConcurrentLinkedQueue<Map<String, List<String>>>()
	private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
		createContext("/") { exchange ->
			seen += exchange.requestHeaders.mapKeys { it.key.lowercase() }
			exchange.sendResponseHeaders(404, -1)
			exchange.close()
		}
		start()
	}
	private val base = "http://127.0.0.1:${server.address.port}"

	@AfterTest
	fun stop() = server.stop(0)

	@Test
	fun theSourcesHeadersAndTheUserAgentGoOutOnce() = runBlocking {
		val client = imageHttpClient { url -> if (url.startsWith(base)) mapOf("X-Auth" to "secret") else emptyMap() }
		client.get("$base/rest/getCoverArt.view?id=al-1")
		client.get("$base/rest/getCoverArt.view?id=al-2") { header("X-Auth", "already") }
		val (first, second) = seen.toList()
		assertEquals(listOf("secret"), first["x-auth"])
		assertTrue(first["user-agent"]!!.single().startsWith("Worbus-Android/"), "${first["user-agent"]}")
		assertEquals(listOf("already"), second["x-auth"], "a request's own header is not doubled")
	}

	@Test
	fun withoutASessionTheSharedClientSendsNoCustomHeader() = runBlocking {
		sharedImageHttpClient.get("$base/rest/getCoverArt.view?id=al-1")
		val headers = seen.single()
		assertEquals(null, headers["x-auth"])
		assertTrue(headers["user-agent"]!!.single().startsWith("Worbus-Android/"))
	}
}
