package app.worbus.domain.manager

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Review (fix round) minor 6: the redirect fix covered the image, streaming and Subsonic clients, not
 * the other two that talk to Navidrome. NativeApiManager set `X-ND-Authorization: Bearer <jwt>` and
 * the custom headers per request, and SessionManager's shared client set the custom headers per
 * request; Ktor strips only `Authorization` on a cross-host hop, so a 302 from an expired auth
 * proxy to its portal on another host carried Navidrome's JWT and the proxy secret there. Two local
 * servers: "Navidrome" redirects, the other records what reached it.
 */
class NavidromeRedirectHeadersTest {
	private class Seen(val path: String, val bearer: String?, val proxy: String?)

	private val elsewhere = ConcurrentLinkedQueue<Seen>()
	private val atNavidrome = ConcurrentLinkedQueue<Seen>()

	private fun HttpExchange.seen() = Seen(requestURI.path, requestHeaders.getFirst("X-ND-Authorization"), requestHeaders.getFirst("X-Proxy-Auth"))

	private fun HttpExchange.json(body: String) {
		val bytes = body.toByteArray()
		responseHeaders.add("Content-Type", "application/json")
		sendResponseHeaders(200, bytes.size.toLong())
		responseBody.use { it.write(bytes) }
	}

	private val portal: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
		createContext("/") { exchange ->
			elsewhere += exchange.seen()
			if (exchange.requestURI.path.startsWith("/rest")) {
				exchange.json("""{"subsonic-response":{"status":"ok","version":"1.16.1","artists":{"index":[]}}}""")
			} else exchange.json("[]")
		}
		executor = Executors.newCachedThreadPool()
		start()
	}
	private val navidrome: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
		createContext("/") { exchange ->
			atNavidrome += exchange.seen()
			val path = exchange.requestURI.path
			when {
				path == "/auth/login" -> exchange.json("""{"token":"jwt-secret"}""")
				path == "/api/album" || path.startsWith("/rest/") -> {
					exchange.responseHeaders.add("Location", "http://127.0.0.1:${portal.address.port}/portal$path")
					exchange.sendResponseHeaders(302, -1)
				}
				path == "/api/same-origin-hop" -> exchange.json("[]")
				else -> exchange.json("[]")
			}
			exchange.close()
		}
		executor = Executors.newCachedThreadPool()
		start()
	}

	private val settings = MemorySettings()
	private val secrets = testSecrets(settings).also {
		settings.putString("instanceUrl", "http://127.0.0.1:${navidrome.address.port}")
		settings.putString("username", "alice")
		it.navidromePassword = "hunter2"
	}
	private val preferences = PreferenceManager(settings, secrets).also { it.customHeaders = "X-Proxy-Auth: secret" }

	@AfterTest
	fun stop() {
		navidrome.stop(0)
		portal.stop(0)
	}

	@Test
	fun theNativeApisBearerAndHeadersStayOnNavidrome() = runBlocking {
		val native = NativeApiManager(settings, preferences, secrets)
		native.albumIdsByParticipation("artist-1")
		assertTrue(atNavidrome.any { it.path == "/api/album" && it.bearer == "Bearer jwt-secret" && it.proxy == "secret" }, "Navidrome got both")
		val hop = elsewhere.toList()
		assertTrue(hop.isNotEmpty(), "the redirect was followed")
		assertEquals(listOf<String?>(null), hop.map { it.bearer }.distinct(), "no JWT off Navidrome's host")
		assertEquals(listOf<String?>(null), hop.map { it.proxy }.distinct(), "no custom header off Navidrome's host")
	}

	@Test
	fun theSessionClientsHeadersStayOnNavidrome() = runBlocking {
		val session = SessionManager(settings, preferences, secrets, ServerReachability())
		runCatching { session.fetchAlbumArtists() }
		assertTrue(atNavidrome.any { it.path == "/rest/getArtists.view" && it.proxy == "secret" }, "Navidrome got the header")
		val hop = elsewhere.toList()
		assertTrue(hop.isNotEmpty(), "the redirect was followed")
		assertEquals(listOf<String?>(null), hop.map { it.proxy }.distinct(), "no custom header off Navidrome's host")
	}
}
