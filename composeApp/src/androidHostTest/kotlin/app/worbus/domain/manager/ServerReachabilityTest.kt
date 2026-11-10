package app.worbus.domain.manager

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/*
 * Q-165: ServerReachability is fed by what a real Ktor client (the app's OkHttp engine) meets against
 * a local server standing in for Navidrome: answering, a reverse proxy's 502 for a Navidrome behind
 * it, and nothing listening at all. A probe brings it back once the server answers again.
 */
class ServerReachabilityTest {
	@Volatile private var status = 200
	private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
		createContext("/") { exchange ->
			exchange.sendResponseHeaders(status, -1)
			exchange.close()
		}
		start()
	}
	private val base = "http://127.0.0.1:${server.address.port}"
	private val reach = ServerReachability(probeEvery = 50.milliseconds, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
	private val client = HttpClient { reach.observe(this) }

	@AfterTest
	fun stop() = server.stop(0)

	private suspend fun call(url: String = "$base/rest/ping.view") = runCatching { client.get(url) }

	@Test
	fun anAnswerOfAnyStatusIsReachable() = runBlocking {
		status = 404
		call()
		assertTrue(reach.reachable.value)
	}

	@Test
	fun aGatewayErrorIsNavidromeDown() = runBlocking {
		status = 502
		call()
		assertFalse(reach.reachable.value)
	}

	@Test
	fun nothingListeningIsDownAndAProbeBringsItBack() = runBlocking {
		val closed = java.net.ServerSocket(0).use { it.localPort }
		call("http://127.0.0.1:$closed/rest/ping.view")
		assertFalse(reach.reachable.value, "connection refused")

		// Navidrome is back (here: the live stand-in); the probe asks and its answer is observed.
		reach.probe = { client.get("$base/rest/ping.view") }
		reach.networkChanged()
		withTimeout(5_000) { reach.reachable.first { it } }
		assertTrue(reach.reachable.value)
	}

	@Test
	fun aFailureThatIsNotTheServerGoneDoesNotMarkItDown() {
		reach.failed(IllegalStateException("could not parse the body"))
		assertTrue(reach.reachable.value)
		assertEquals(true, isNothingAnswering(ServerReachability.GatewayDown(503)))
	}
}
