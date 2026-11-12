package app.worbus.di

import app.worbus.sourceFile
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

	/**
	 * Review minor 4: an auth proxy redirects a cover request to its portal on another host, or a
	 * proxy sends covers to a CDN. Ktor's redirect follower copies the request, headers and all, and
	 * strips only Authorization; the proxy credential must not go with it.
	 */
	@Test
	fun aRedirectToAnotherHostCarriesNoCustomHeader() = runBlocking {
		val elsewhere = ConcurrentLinkedQueue<Map<String, List<String>>>()
		val other = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
			createContext("/") { exchange ->
				elsewhere += exchange.requestHeaders.mapKeys { it.key.lowercase() }
				exchange.sendResponseHeaders(404, -1)
				exchange.close()
			}
			start()
		}
		server.createContext("/redirect") { exchange ->
			seen += exchange.requestHeaders.mapKeys { it.key.lowercase() }
			val to = exchange.requestURI.query.substringAfter("to=")
			exchange.responseHeaders.add("Location", to)
			exchange.sendResponseHeaders(302, -1)
			exchange.close()
		}
		try {
			val client = imageHttpClient { url -> if (url.startsWith(base)) mapOf("X-Proxy-Auth" to "secret") else emptyMap() }
			client.get("$base/redirect?to=http://127.0.0.1:${other.address.port}/portal")
			assertEquals(listOf("secret"), seen.first()["x-proxy-auth"], "Navidrome's own request carries it")
			assertEquals(null, elsewhere.single()["x-proxy-auth"], "the hop to another host does not")

			// A redirect that stays on Navidrome keeps it.
			seen.clear()
			client.get("$base/redirect?to=$base/rest/getCoverArt.view")
			assertEquals(listOf("secret"), seen.last()["x-proxy-auth"])
		} finally {
			other.stop(0)
		}
	}

	/**
	 * The other two clients that send the custom headers set them with `defaultRequest`, i.e. to every
	 * host and every hop: the streaming client to an `ext:` preview's host too (the lane's noticed
	 * item), the Subsonic client to a redirect. One plugin decides per hop for all three.
	 */
	@Test
	fun noClientSendsTheCustomHeadersToEveryHost() {
		val offenders = listOf("src/commonMain", "src/androidMain").map(::sourceFile)
			.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
			.filter { Regex("""defaultRequest\s*\{[^}]*customHeaders""").containsMatchIn(it.readText()) }
			.map { it.name }
		assertEquals(emptyList(), offenders, "custom headers set on every request of a client")
	}
}
