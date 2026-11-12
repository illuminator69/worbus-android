package app.worbus.domain.manager

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import app.worbus.sourceFile
import kotlinx.coroutines.launch
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
			if (exchange.requestURI.path.endsWith("slow.view")) Thread.sleep(1_500)
			exchange.sendResponseHeaders(status, -1)
			exchange.close()
		}
		executor = java.util.concurrent.Executors.newCachedThreadPool()
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
		// The confirming probe meets the same closed door.
		reach.probe = { client.get("http://127.0.0.1:$closed/rest/ping.view") }
		call("http://127.0.0.1:$closed/rest/ping.view")
		withTimeout(5_000) { reach.reachable.first { !it } }
		assertFalse(reach.reachable.value, "connection refused, and the probe refused too")

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

	/** Every value [ServerReachability.reachable] took while [block] ran, and for [settle] after. */
	private fun valuesDuring(settle: Long = 400, block: suspend () -> Unit): List<Boolean> = runBlocking {
		val seen = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
		val watch = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined).launch { reach.reachable.collect { seen += it } }
		block()
		kotlinx.coroutines.delay(settle)
		watch.cancel()
		seen.toList()
	}

	/**
	 * Review major 2's forcing case: Navidrome is up and slow to answer one request (mid-scan behind
	 * a tunnel). The request's socket timeout fires; the server is still there, so the app is not
	 * offline.
	 */
	@Test
	fun aReadStallAgainstALiveServerLeavesItReachable() {
		val impatient = HttpClient {
			install(io.ktor.client.plugins.HttpTimeout) { socketTimeoutMillis = 300 }
			reach.observe(this)
		}
		reach.probe = { client.get("$base/rest/ping.view") }
		val seen = valuesDuring {
			val failure = runCatching { impatient.get("$base/rest/slow.view") }.exceptionOrNull()
			assertTrue(failure != null, "the stall timed out the request")
		}
		assertEquals(listOf(true), seen.distinct(), "a read stall is a slow server, not a down one: $seen")
	}

	/** A connection the server reset after it had answered: it was there, so it is not down. */
	@Test
	fun aResetMidAnswerLeavesItReachable() {
		val resetting = java.net.ServerSocket(0)
		val serve = Thread {
			resetting.accept().use { socket ->
				socket.getInputStream().read(ByteArray(4096))
				socket.getOutputStream().apply {
					write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".toByteArray())
					write(ByteArray(64))
					flush()
				}
				socket.setSoLinger(true, 0)
			}
		}.apply { isDaemon = true; start() }
		reach.probe = { client.get("$base/rest/ping.view") }
		val seen = valuesDuring {
			val failure = runCatching { client.get("http://127.0.0.1:${resetting.localPort}/rest/stream.view").body<ByteArray>() }.exceptionOrNull()
			assertTrue(failure != null, "the reset failed the read")
		}
		serve.join(2_000)
		resetting.close()
		assertEquals(listOf(true), seen.distinct(), "a reset after the connection was made is not the server gone: $seen")
	}

	/** One refused request while the server answers a ping at once: one failure is not "down" until a probe agrees. */
	@Test
	fun oneFailureIsConfirmedByAProbeBeforeItCountsAsDown() {
		val closed = java.net.ServerSocket(0).use { it.localPort }
		reach.probe = { client.get("$base/rest/ping.view") }
		val seen = valuesDuring { call("http://127.0.0.1:$closed/rest/ping.view") }
		assertEquals(listOf(true), seen.distinct(), "a probe that answered keeps it reachable: $seen")
	}

	/**
	 * The streaming client reports too (recovery does not wait for the 30 s probe while audio is
	 * fetched fine), but only for requests to Navidrome: a preview host refusing is not Navidrome down.
	 */
	@Test
	fun theStreamingClientReportsNavidromesAnswersOnly() {
		val player = sourceFile("src/androidMain/kotlin/app/worbus/shared/MediaPlayer.android.kt").readText()
			.substringAfter("streamingClient = HttpClient(OkHttp) {").substringBefore("\n\t\t}\n")
		assertTrue(Regex("""reachability\.observe\(this,\s*onlyFor""").containsMatchIn(player), "the streaming client reports Navidrome's exchanges:\n$player")
	}
}
