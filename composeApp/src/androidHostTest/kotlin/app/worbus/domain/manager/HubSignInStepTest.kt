package app.worbus.domain.manager

import app.worbus.sourceFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * Worbus P7 N2 (I-064): the Worbus Server step after the Navidrome login. The finder is the real one
 * with a fake probe, the sign-in the real HubAuth with a fake transport; the step's sentences are read
 * from strings.xml, so the fixed copy is what is checked.
 */
class HubSignInStepTest {
	private val id = "a".repeat(32)

	private class FakeProbe(@Volatile var answer: suspend (String) -> InfoOutcome) : HubInfoProbe {
		val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
		@Volatile var cancelled = false
		override suspend fun probe(base: String, timeoutMs: Long): InfoOutcome {
			asked += base
			try {
				return answer(base)
			} catch (e: kotlinx.coroutines.CancellationException) {
				cancelled = true
				throw e
			}
		}
	}

	private class FakeTransport(@Volatile var reply: suspend () -> HubAuthReply) : HubAuthTransport {
		val urls: MutableList<String> = Collections.synchronizedList(mutableListOf())
		override suspend fun post(url: String, jsonBody: String?, bearer: String?): HubAuthReply {
			urls += url
			return reply()
		}
	}

	private val granted = HubAuthReply.Answered(
		200,
		"""{"deviceId": "a1b2c3d4e5f60718", "deviceSecret": "S3cr3t-0f-32-bytes_urlsafe-abcdefghijklmn", "user": {"name": "bob", "admin": false, "primary": true}}"""
	)

	private val settings = MemorySettings()
	private val secrets = testSecrets(settings)
	private val prefs = PreferenceManager(settings, secrets)
	private val store = prefs.serverRecordStore
	private val navidrome = "http://192.168.1.20:4533"
	private val transport = FakeTransport { granted }
	private val auth = HubAuth(prefs, secrets, { NavidromeAccount("bob", "hunter2-p4ss") }, { "phone" }, transport, log = {})
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private var paired = 0

	@AfterTest
	fun tearDown() = scope.cancel()

	private val strings: Map<String, String> by lazy {
		val xml = sourceFile("src/commonMain/composeResources/values/strings.xml").readText()
		Regex("""<string name="([^"]+)">(.*?)</string>""").findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
	}

	/** The strings.xml entry the screen reads for each sentence (`HubStep.kt`'s `sentenceResource`). */
	private fun keyOf(sentence: HubStepSentence) = when (sentence) {
		HubStepSentence.NoAnswerAt -> "hub_step_no_answer_at"
		HubStepSentence.NotEncryptedAt -> "finder_not_encrypted"
	}

	private fun step(probe: HubInfoProbe): HubSignInStep {
		val finder = HubFinder(
			probe = probe,
			localSubnet = { null },
			localNetworkGranted = { true },
			navidromeUrl = { navidrome },
			serverSet = { hubAddressSet(prefs) },
			store = store,
			log = {}
		)
		return HubSignInStep(
			finder = finder,
			probe = probe,
			auth = auth,
			preferenceManager = prefs,
			store = store,
			scope = scope,
			onPaired = { paired++ },
			words = { sentence, arg -> strings.getValue(keyOf(sentence)).replace("%1\$s", arg) },
			log = {}
		)
	}

	private suspend fun HubSignInStep.await(timeoutMs: Long = 5_000, what: (HubStepState) -> Boolean): HubStepState =
		withTimeout(timeoutMs) { state.first(what) }

	private fun nothingStored() {
		assertEquals("", prefs.hubUrl, "no address kept")
		assertTrue(store.read().addresses.isEmpty(), "the record holds no address")
		assertFalse(auth.hasCredential, "no credential")
		assertEquals(0, paired, "nothing paired")
	}

	@Test
	fun foundOffersSignInThenPairs() = runBlocking<Unit> {
		val probe = FakeProbe { base -> if (base == "ws://192.168.1.20:4790") InfoOutcome.Match(id, "Worbus Server") else InfoOutcome.Unreachable }
		val gate = CompletableDeferred<Unit>()
		transport.reply = { gate.await(); granted }
		val s = step(probe)
		assertEquals(HubStepState.Found("http://192.168.1.20:4790"), s.await { it is HubStepState.Found })
		// Offered, not yet kept: Skip here would leave Settings as it is.
		assertEquals("", prefs.hubUrl)
		s.signIn()
		s.await { it == HubStepState.SigningIn }
		gate.complete(Unit)
		assertEquals(HubStepState.Done(paired = true), s.await { it is HubStepState.Done })
		assertEquals(listOf("http://192.168.1.20:4790/hub/signin"), transport.urls.toList(), "one POST /hub/signin")
		assertEquals("ws://192.168.1.20:4790", prefs.hubUrl)
		assertTrue(prefs.hubEnabled)
		assertTrue(auth.serverSet, "signed in: on, an address, a credential")
		assertEquals(1, paired, "the socket starts over once")
	}

	@Test
	fun signInStepSkipsWhileFinding() = runBlocking<Unit> {
		val probe = FakeProbe { awaitCancellation() }
		val s = step(probe)
		assertEquals(HubStepState.Finding, s.state.value)
		withTimeout(5_000) { while (probe.asked.isEmpty()) kotlinx.coroutines.delay(10) }
		// The finder is still waiting on the network; Skip is there and ends the step at once.
		s.skip()
		assertEquals(HubStepState.Done(paired = false), s.await(timeoutMs = 1_000) { it is HubStepState.Done })
		assertTrue(probe.cancelled, "the look was stopped")
		assertTrue(transport.urls.isEmpty(), "no sign-in was sent")
		nothingStored()
	}

	@Test
	fun signInStepShowsNotFoundAfterBudget() = runBlocking<Unit> {
		val probe = FakeProbe { awaitCancellation() }
		val budget = candidatesFor(navidrome).sumOf { probeTimeoutMs(normaliseHubAddress(it)!!) }
		val started = System.nanoTime()
		val s = step(probe)
		assertEquals(HubStepState.NotFound, s.await(timeoutMs = budget + 3_000) { it != HubStepState.Finding })
		val waited = (System.nanoTime() - started) / 1_000_000
		assertTrue(waited >= budget - 50, "waited the finder's own budget ($budget ms), not less: $waited ms")
		// Skip and Type its address are offered there; Skip ends the step with nothing paired.
		s.skip()
		assertEquals(HubStepState.Done(paired = false), s.await { it is HubStepState.Done })
		nothingStored()
	}

	@Test
	fun typedAddressKeptOnlyWhenItAnswers() = runBlocking<Unit> {
		val probe = FakeProbe { InfoOutcome.Unreachable }
		val s = step(probe)
		s.await { it == HubStepState.NotFound }
		s.typeAddress()
		assertEquals(HubStepState.Typing(null), s.await { it is HubStepState.Typing })
		s.submit("10.0.0.9")
		assertEquals(HubStepState.Typing("No Worbus Server answers at 10.0.0.9."), s.await { it == HubStepState.Typing("No Worbus Server answers at 10.0.0.9.") })
		assertTrue("ws://10.0.0.9:4790" in probe.asked, "a bare host is asked on the server's port: ${probe.asked}")
		nothingStored()
		probe.answer = { base -> if (base == "ws://10.0.0.9:4790") InfoOutcome.Match(id, "Worbus Server") else InfoOutcome.Unreachable }
		s.submit("10.0.0.9")
		assertEquals(HubStepState.Found("http://10.0.0.9:4790"), s.await { it is HubStepState.Found })
		// Kept for the step's Sign in; written only by a sign-in that pairs.
		assertEquals("", prefs.hubUrl)
		s.signIn()
		assertEquals(HubStepState.Done(paired = true), s.await { it is HubStepState.Done })
		assertEquals(listOf("http://10.0.0.9:4790/hub/signin"), transport.urls.toList())
		assertEquals("ws://10.0.0.9:4790", prefs.hubUrl)
	}

	@Test
	fun olderServerSaysUpdate() = runBlocking<Unit> {
		val probe = FakeProbe { base -> if (base == "ws://192.168.1.20:4790") InfoOutcome.Older else InfoOutcome.Unreachable }
		val s = step(probe)
		assertEquals(HubStepState.OlderServer("http://192.168.1.20:4790"), s.await { it != HubStepState.Finding })
		// Continue: the step ends, nothing paired, sign in later from Settings.
		s.skip()
		assertEquals(HubStepState.Done(paired = false), s.await { it is HubStepState.Done })
		assertTrue(transport.urls.isEmpty())
		nothingStored()
		// A typed address answering 426 says the same.
		val typed = step(FakeProbe { base -> if (base == "ws://10.0.0.9:4790") InfoOutcome.Older else InfoOutcome.Unreachable })
		typed.await { it == HubStepState.NotFound }
		typed.typeAddress()
		typed.submit("10.0.0.9")
		assertEquals(HubStepState.OlderServer("http://10.0.0.9:4790"), typed.await { it is HubStepState.OlderServer })
	}

	@Test
	fun signInFailureFallsBackToSkip() = runBlocking<Unit> {
		val probe = FakeProbe { base -> if (base == "ws://192.168.1.20:4790") InfoOutcome.Match(id, "Worbus Server") else InfoOutcome.Unreachable }
		transport.reply = { HubAuthReply.Answered(401, """{"code": "navidrome_refused"}""") }
		val s = step(probe)
		s.await { it is HubStepState.Found }
		s.signIn()
		assertEquals(
			HubStepState.Found("http://192.168.1.20:4790", failure = SignInResult.Refused),
			s.await { it is HubStepState.Found && it.failure != null }
		)
		// What the refused sign-in wrote for itself is taken back: Skip leaves Settings as it was.
		nothingStored()
		// A transport that throws is an answer too, never a step stuck in SigningIn.
		transport.reply = { throw java.io.IOException("reset") }
		s.signIn()
		assertEquals(
			HubStepState.Found("http://192.168.1.20:4790", failure = SignInResult.Unreachable(navidrome = false)),
			s.await { it is HubStepState.Found && it.failure is SignInResult.Unreachable }
		)
		nothingStored()
		// A sign-in that never answers: Skip still ends the step.
		transport.reply = { awaitCancellation() }
		s.signIn()
		s.await { it == HubStepState.SigningIn }
		s.skip()
		assertEquals(HubStepState.Done(paired = false), s.await { it is HubStepState.Done })
		nothingStored()
	}
}
