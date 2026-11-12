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
		// The confirming probe meets the same proxy answer.
		reach.probe = { client.get("$base/rest/ping.view") }
		call()
		withTimeout(5_000) { reach.reachable.first { !it } }
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
		assertEquals(true, isServerDown(ServerReachability.GatewayDown(503)))
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

	/**
	 * Review (fix round) minor 3: a read stall is not a connect timeout because the word "connect" is
	 * in the URL. Ktor writes the request URL into its read-timeout message, so a search for
	 * "Disconnect" (and every request to a host named `*connect*`, the probe's included) read as
	 * Navidrome gone. Classified by type, never by the message.
	 */
	@Test
	fun aReadStallWhoseUrlSaysConnectLeavesItReachable() {
		val impatient = HttpClient {
			install(io.ktor.client.plugins.HttpTimeout) { socketTimeoutMillis = 300 }
			reach.observe(this)
		}
		// The probe stalls the same way: only its type may say "down", and it does not.
		reach.probe = { impatient.get("$base/rest/slow.view?c=connect") }
		val seen = valuesDuring(settle = 2_500) {
			val failure = runCatching { impatient.get("$base/rest/slow-search3.view?query=Disconnect") }.exceptionOrNull()
			assertTrue(failure != null, "the stall timed out the request")
			assertFalse(isServerDown(failure!!), "a read timeout naming 'Disconnect' in its URL: $failure")
		}
		assertEquals(listOf(true), seen.distinct(), "a read stall is a slow server, whatever its URL says: $seen")
	}

	@Test
	fun aHostNamedConnectIsNotDownWhenItIsSlow() {
		val connectHost = HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
			engine {
				config {
					dns(object : okhttp3.Dns {
						override fun lookup(hostname: String) = listOf(java.net.InetAddress.getLoopbackAddress())
					})
				}
			}
			install(io.ktor.client.plugins.HttpTimeout) { socketTimeoutMillis = 300 }
			reach.observe(this)
		}
		val url = "http://music.connect.lan:${server.address.port}/rest/slow.view"
		reach.probe = { connectHost.get(url) }
		val seen = valuesDuring(settle = 2_500) {
			val failure = runCatching { connectHost.get(url) }.exceptionOrNull()
			assertTrue(failure != null, "the stall timed out the request")
		}
		assertEquals(listOf(true), seen.distinct(), "a slow server on music.connect.lan is not a down one: $seen")
	}

	@Test
	fun connectFailuresAreKnownByTheirType() {
		assertTrue(isServerDown(io.ktor.client.network.sockets.ConnectTimeoutException("Connect timeout has expired [url=http://h/rest/ping.view]")))
		assertTrue(isServerDown(java.net.ConnectException("Connection refused")))
		assertTrue(isServerDown(java.net.UnknownHostException("music.example.lan")))
		assertFalse(isServerDown(java.net.SocketTimeoutException("Socket timeout has expired [url=http://music.connect.lan/rest/search3.view?query=Disconnect, socket_timeout=300] ms")))
		assertFalse(isServerDown(java.net.SocketTimeoutException("Read timed out")))
	}

	/**
	 * Review (fix round) minor 4: the confirm's verdict was whichever report came last, so a failure
	 * from a request that STARTED before the probe (a slow name lookup that began while Navidrome was
	 * away) landing after the probe's answer took the app offline although Navidrome had answered.
	 * Reports are ordered by when their request started.
	 */
	@Test
	fun aLateFailureFromAnOlderRequestDoesNotUndoTheProbesAnswer() {
		val slowLookup = HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
			engine {
				config {
					dns(object : okhttp3.Dns {
						override fun lookup(hostname: String): List<java.net.InetAddress> {
							Thread.sleep(700)
							throw java.net.UnknownHostException(hostname)
						}
					})
				}
			}
			reach.observe(this)
		}
		// The probe answers at once, then takes a while to finish (its body, here a delay): the old
		// request's failure lands in between.
		reach.probe = {
			client.get("$base/rest/ping.view")
			kotlinx.coroutines.delay(1_200)
		}
		val closed = java.net.ServerSocket(0).use { it.localPort }
		val seen = valuesDuring(settle = 1_800) {
			val old = CoroutineScope(Dispatchers.IO).launch { runCatching { slowLookup.get("http://navidrome.lan/rest/getAlbumList2.view") } }
			kotlinx.coroutines.delay(150)
			call("http://127.0.0.1:$closed/rest/ping.view")
			old.join()
		}
		assertEquals(listOf(true), seen.distinct(), "the probe answered after the old request started: $seen")
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

	/** Review nit: logged out there is no probe, so a late failure asks no server (the old one included). */
	@Test
	fun withNoSessionALateFailureAsksNothing() {
		reach.probe = null
		reach.failed(java.net.ConnectException("Connection refused"))
		assertTrue(reach.reachable.value, "no session: nothing to be offline from")
	}
}
