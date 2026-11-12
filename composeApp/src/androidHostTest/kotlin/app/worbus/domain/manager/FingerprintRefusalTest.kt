package app.worbus.domain.manager

import app.worbus.ui.screens.mixes.viewmodels.MixPageState
import app.worbus.ui.screens.mixes.viewmodels.mixPageState
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/*
 * Review (fix round, 2026-11-12) major 2, coordinator's ruling: match the hub (PROTOCOL §14).
 * An AudioMuse that refuses the POST (405, or a 400/415 whose body is not a JSON object: the hub's
 * `_audiomuse_refused_post`) is too old for the listening fingerprint. It is never asked again by
 * GET: a count-only GET answers for AudioMuse's own configured account, which is someone else's
 * listening shown as this user's mix. The hub's `503 audiomuse_too_old` is the same state, and its
 * `400 bad_request` is a refusal, not an empty mix. No account in any URL.
 */
class FingerprintRefusalTest {
	private class Reply(val status: Int, val body: String, val type: String = "application/json")

	private val seen = ConcurrentLinkedQueue<String>()
	@Volatile private var reply: (String) -> Reply = { Reply(200, "[]") }
	private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
		createContext("/") { exchange ->
			seen += "${exchange.requestMethod} ${exchange.requestURI}"
			val r = reply(exchange.requestMethod)
			val bytes = r.body.toByteArray()
			exchange.responseHeaders.add("Content-Type", r.type)
			exchange.sendResponseHeaders(r.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
			if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
			exchange.close()
		}
		start()
	}
	// As the app's client: the POST's body is JSON through ContentNegotiation.
	private val client = HttpClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
	private val base get() = "http://127.0.0.1:${server.address.port}"

	@AfterTest
	fun stop() = server.stop(0)

	private fun direct(status: Int, body: String, type: String = "application/json"): FingerprintAnswer = runBlocking {
		seen.clear()
		reply = { method -> if (method == "POST") Reply(status, body, type) else Reply(200, """[{"item_id":"someone-elses"}]""") }
		directFingerprint(client, "$base/api/sonic_fingerprint/generate", "tok", 50, "alice", "hunter2")
	}

	private fun hub(status: Int, body: String): FingerprintAnswer = runBlocking {
		seen.clear()
		reply = { Reply(status, body) }
		hubFingerprint(client, "$base/sonic/fingerprint", "bearer", 50)
	}

	private fun assertNoAccountInAnyUrl() =
		assertFalse(seen.any { "hunter2" in it || "alice" in it || "navidrome_" in it }, "no account in any URL: ${seen.toList()}")

	@Test
	fun aRefusedPostIsAudioMuseTooOldAndNoGetIsMade() {
		val refusals = listOf(
			Triple(405, "", "text/html"),
			Triple(400, "<!doctype html><title>400 Bad Request</title>", "text/html"),
			Triple(415, "<p>Unsupported Media Type</p>", "text/html"),
			Triple(400, "", "text/plain")
		)
		for ((status, body, type) in refusals) {
			assertEquals(FingerprintAnswer.AudioMuseTooOld("3.7.1"), direct(status, body, type), "$status $body")
			assertEquals(listOf("POST /api/sonic_fingerprint/generate"), seen.toList(), "only the POST is made on $status")
			assertNoAccountInAnyUrl()
		}
	}

	@Test
	fun aJsonRefusalIsAudioMuseItselfNotTooOld() {
		assertEquals(FingerprintAnswer.Refused(400, "bad_n"), direct(400, """{"code":"bad_n","error":"n must be a number"}"""))
		assertEquals(FingerprintAnswer.Refused(500, ""), direct(500, """{"error":"boom"}"""))
		assertEquals(listOf("POST /api/sonic_fingerprint/generate"), seen.toList())
	}

	@Test
	fun aListIsTheFingerprint() {
		assertEquals(FingerprintAnswer.Ids(listOf("s-1", "s-2")), direct(200, """[{"item_id":"s-1"},{"item_id":"s-2"}]"""))
		assertNoAccountInAnyUrl()
		assertEquals(FingerprintAnswer.Ids(listOf("s-3")), hub(200, """[{"item_id":"s-3"}]"""))
		assertEquals(listOf("GET /sonic/fingerprint?n=50"), seen.toList())
	}

	@Test
	fun theHubsRefusalsAreNamed() {
		val tooOld = """{"code":"audiomuse_too_old","need":"3.7.1","error":"This Worbus Server's AudioMuse is too old for the listening fingerprint: it needs AudioMuse 3.7.1 or later."}"""
		assertEquals(FingerprintAnswer.AudioMuseTooOld("3.7.1"), hub(503, tooOld))
		assertEquals(FingerprintAnswer.AudioMuseTooOld("3.8.0"), hub(503, """{"code":"audiomuse_too_old","need":"3.8.0"}"""))
		assertEquals(FingerprintAnswer.Refused(400, "bad_request"), hub(400, """{"code":"bad_request"}"""))
		assertEquals(FingerprintAnswer.Refused(403, "primary_only"), hub(403, """{"code":"primary_only"}"""))
		assertEquals(FingerprintAnswer.Refused(503, "busy"), hub(503, """{"code":"busy"}"""))
	}

	@Test
	fun theMixPageSaysIt() {
		assertEquals(MixPageState.AudioMuseTooOld("3.7.1"), mixPageState(fingerprintFailure(FingerprintAnswer.AudioMuseTooOld("3.7.1"))!!))
		assertEquals(MixPageState.Refused("bad_request"), mixPageState(fingerprintFailure(FingerprintAnswer.Refused(400, "bad_request"))!!))
		assertEquals(null, fingerprintFailure(FingerprintAnswer.Ids(emptyList())))
	}

	/**
	 * Review of fix round 2, minor 1: a user who is not the server's primary user gets `403
	 * primary_only` for a fingerprint mix; "the server refused it. Pull to try again." can never come
	 * true for them. It is the server owner's listening, and the page says so.
	 */
	@Test
	fun aNonPrimaryUsersFingerprintMixIsNotATryAgain() {
		val owners = mixPageState(fingerprintFailure(FingerprintAnswer.Refused(403, "primary_only"))!!)
		kotlin.test.assertNotEquals<MixPageState>(MixPageState.Refused("primary_only"), owners, "not the retry sentence")
		kotlin.test.assertIs<MixPageState.Refused>(mixPageState(fingerprintFailure(FingerprintAnswer.Refused(503, "busy"))!!))
	}
}
