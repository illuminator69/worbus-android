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
	private val auth = HubAuth(prefs, secrets, { NavidromeAccount("bob", "hunter2-p4ss") }, { "phone" }, transport, log = {}, navidromeUrl = { navidrome })
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

	private fun step(probe: HubInfoProbe, keptAddress: String? = null): HubSignInStep {
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
			log = {},
			keptAddress = keptAddress
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
		// Review Minor 3: Signing in keeps the address offered, so a screen drawn afresh still says it.
		assertEquals(HubStepState.SigningIn("http://192.168.1.20:4790"), s.await { it is HubStepState.SigningIn })
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
		// And not much more: the budget plus the slack for an answer in flight (review Minor 2).
		assertTrue(waited < budget + PROBE_ANSWER_SLACK_MS + 1_000, "bounded by $budget + $PROBE_ANSWER_SLACK_MS ms: $waited ms")
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
		s.await { it is HubStepState.SigningIn }
		s.skip()
		assertEquals(HubStepState.Done(paired = false), s.await { it is HubStepState.Done })
		nothingStored()
	}

	// ---- Lane N review Minor 2 and 5: the typed address ---------------------------------------- //

	@Test
	fun typedAnswerJustPastItsWindowIsFound() = runBlocking<Unit> {
		val probe = FakeProbe { InfoOutcome.Unreachable }
		val s = step(probe)
		s.await { it == HubStepState.NotFound }
		s.typeAddress()
		// The typed server's answer is on its way as its 1500 ms window closes (read 100 ms past it).
		probe.answer = { base ->
			kotlinx.coroutines.delay(PROBE_TIMEOUT_PRIVATE_MS + 100)
			if (base == "ws://10.0.0.9:4790") InfoOutcome.Match(id, "Worbus Server") else InfoOutcome.Unreachable
		}
		s.submit("10.0.0.9")
		assertEquals(HubStepState.Found("http://10.0.0.9:4790"), s.await { it is HubStepState.Found || (it is HubStepState.Typing && it.error != null) })
	}

	@Test
	fun typedPlainAddressWithoutPortIsOnTheServersPort() {
		assertEquals("ws://10.0.0.9:4790", typedHubCandidate("10.0.0.9"))
		assertEquals("ws://192.168.1.20:4790", typedHubCandidate("http://192.168.1.20"))
		assertEquals("ws://10.0.0.9:4790", typedHubCandidate("ws://10.0.0.9/"))
		assertEquals("ws://box.lan:4790/hub", typedHubCandidate("HTTP://Box.lan/hub"))
		assertEquals("ws://[fd00::5]:4790", typedHubCandidate("http://[fd00::5]"))
		assertEquals("ws://10.0.0.9:4790", typedHubCandidate("10.0.0.9?x=1"))
		// A port typed is the port asked; an encrypted address is a reverse proxy's, on 443.
		assertEquals("ws://10.0.0.9:8080", typedHubCandidate("http://10.0.0.9:8080"))
		assertEquals("wss://hub.example.com", typedHubCandidate("https://hub.example.com"))
		assertEquals("wss://hub.example.com", typedHubCandidate("wss://hub.example.com/"))
		assertEquals(null, typedHubCandidate("ftp://10.0.0.9"))
		assertEquals(null, typedHubCandidate("http://"))
	}

	/*
	 * B-409 (the owner's walk, 2026-11-13): switching to a new user never offered the Worbus Server's
	 * sign-in although one was set. A logout signs the device out and keeps the address, and the step
	 * was offered only when no address was set (LoginManager: `!hubAddressSet`).
	 *
	 * Review M1 (2026-11-13): only the kept server of the Navidrome just signed in to is offered, and only
	 * once it has answered as itself: Sign in sends this account's token pair, and another Navidrome's
	 * server must never receive it.
	 */
	@Test
	fun aLoginWithAKeptServerOfThisNavidromeOffersItsSignIn() {
		assertEquals(HubStepStart.LookBeside, hubStepAfterLogin(addressSet = false, connectOn = true, signedIn = false, keptBelongsHere = false))
		assertEquals(HubStepStart.LookBeside, hubStepAfterLogin(addressSet = false, connectOn = false, signedIn = false, keptBelongsHere = false))
		assertEquals(HubStepStart.KeptAddress, hubStepAfterLogin(addressSet = true, connectOn = true, signedIn = false, keptBelongsHere = true), "the switch of user")
		assertEquals(HubStepStart.LookBeside, hubStepAfterLogin(addressSet = true, connectOn = true, signedIn = false, keptBelongsHere = false), "another Navidrome's server: look beside this one")
		assertEquals(null, hubStepAfterLogin(addressSet = true, connectOn = true, signedIn = true, keptBelongsHere = true), "already signed in")
		assertEquals(null, hubStepAfterLogin(addressSet = true, connectOn = false, signedIn = false, keptBelongsHere = true), "Connect turned off by the user")
	}

	@Test
	fun aKeptServerBelongsToTheNavidromeItWasPairedBeside() {
		val a = "http://music-a.lan:4533"
		val b = "http://192.168.1.20:4533"
		assertFalse(keptServerBelongsTo(b, pairedNavidrome = a, keptAddress = "ws://10.0.0.5:4790"), "paired beside A, signed in to B")
		assertTrue(keptServerBelongsTo("HTTP://192.168.1.20:4533/", pairedNavidrome = b, keptAddress = "ws://10.0.0.5:4790"), "the same Navidrome")
		// Paired before the Navidrome was recorded: only a server beside this Navidrome.
		assertTrue(keptServerBelongsTo(b, pairedNavidrome = "", keptAddress = "ws://192.168.1.20:4790"))
		assertFalse(keptServerBelongsTo(b, pairedNavidrome = "", keptAddress = "ws://10.0.0.5:4790"))
		assertTrue(keptServerBelongsTo("https://music.example.com", pairedNavidrome = "", keptAddress = "wss://hub.example.com"))
		assertFalse(keptServerBelongsTo("https://music.example.com", pairedNavidrome = "", keptAddress = "wss://hub.example.org"))
	}

	/** A kept server [kept] the device was paired with beside Navidrome [pairedBeside], signed out by the logout. */
	private fun keepServer(kept: String, pairedBeside: String) {
		store.startOver(HubServerRecord(serverId = id).withAddress(kept, HubAddress.Source.Typed), hubUrl = kept)
		prefs.hubEnabled = true
		prefs.hubPairedNavidrome = pairedBeside
		// The app's HubConnector answers with the address once it has asked it; a record with an id has no
		// unchecked route (HubAuth.uncheckedRoute), so the fake stands in for that probe.
		auth.signInAddress = { normaliseHubAddress(prefs.hubUrl) }
	}

	@Test
	fun noTokenOfThisNavidromeGoesToAnotherNavidromesServer() = runBlocking<Unit> {
		// Paired with server H beside Navidrome A; logged out; logged in to Navidrome B (`navidrome`).
		keepServer("ws://10.0.0.5:4790", pairedBeside = "http://music-a.lan:4533")
		val start = hubStepAfterLogin(
			hubAddressSet(prefs), prefs.hubEnabled, auth.signedIn,
			keepServerBelongs(navidrome)
		)
		assertEquals(HubStepStart.LookBeside, start)
		// H answers whoever asks; nothing answers beside B.
		val probe = FakeProbe { base -> if (base == "ws://10.0.0.5:4790") InfoOutcome.Match(id, "H") else InfoOutcome.Unreachable }
		val s = step(probe, keptAddress = null)
		assertEquals(HubStepState.NotFound, s.await { it != HubStepState.Finding })
		s.signIn()
		s.skip()
		assertEquals(HubStepState.Done(paired = false), s.await { it is HubStepState.Done })
		assertTrue(transport.urls.isEmpty(), "no sign-in sent anywhere: ${transport.urls}")
		assertTrue(probe.asked.none { it == "ws://10.0.0.5:4790" }, "H was not even asked")
		assertEquals("ws://10.0.0.5:4790", prefs.hubUrl, "the kept server stays set")
	}

	private fun keepServerBelongs(navidromeUrl: String) =
		keptServerBelongsTo(navidromeUrl, prefs.hubPairedNavidrome, prefs.hubUrl)

	@Test
	fun theKeptServerIsOfferedOnceItAnswersAsItselfAndSignsInThere() = runBlocking<Unit> {
		keepServer("ws://192.168.1.20:4790", pairedBeside = navidrome)
		assertTrue(keepServerBelongs(navidrome))
		val probe = FakeProbe { base -> if (base == "ws://192.168.1.20:4790") InfoOutcome.Match(id, "Worbus Server") else InfoOutcome.Unreachable }
		val s = step(probe, keptAddress = prefs.hubUrl)
		assertEquals(HubStepState.Found("http://192.168.1.20:4790"), s.await { it is HubStepState.Found })
		assertEquals(listOf("ws://192.168.1.20:4790"), probe.asked.toList(), "asked once, nothing looked for")
		s.signIn()
		assertEquals(HubStepState.Done(paired = true), s.await { it is HubStepState.Done })
		assertEquals(listOf("http://192.168.1.20:4790/hub/signin"), transport.urls.toList())
		assertEquals("ws://192.168.1.20:4790", prefs.hubUrl)
		assertTrue(auth.serverSet)
		assertEquals(1, paired)
		assertEquals(navidrome, prefs.hubPairedNavidrome, "the pairing records its Navidrome")
	}

	@Test
	fun aKeptServerThatDoesNotAnswerAsItselfIsNotCalledFound() = runBlocking<Unit> {
		for ((kept, answer) in listOf(
			"ws://192.168.1.20:4790" to InfoOutcome.Unreachable,
			"ws://192.168.1.30:4790" to InfoOutcome.Match("b".repeat(32), "Another server"),
			// A public address over plain text: the finder never offers it (review I1's rule).
			"ws://203.0.113.5:4790" to InfoOutcome.Match(id, "Worbus Server")
		)) {
			transport.urls.clear()
			keepServer(kept, pairedBeside = navidrome)
			val probe = FakeProbe { base -> if (base == kept) answer else InfoOutcome.Unreachable }
			val s = step(probe, keptAddress = prefs.hubUrl)
			val shown = s.await { it != HubStepState.Finding }
			assertEquals(HubStepState.NotFound, shown, "$kept answering $answer")
			s.signIn()
			s.skip()
			s.await { it is HubStepState.Done }
			assertTrue(transport.urls.isEmpty(), "$kept: nothing sent")
			assertEquals(kept, prefs.hubUrl, "$kept stays set")
		}
	}

	@Test
	fun aKeptAddressStaysWhenItsSignInDoesNotPairOrIsSkipped() = runBlocking<Unit> {
		keepServer("ws://192.168.1.20:4790", pairedBeside = navidrome)
		transport.reply = { HubAuthReply.Answered(401, """{"code": "navidrome_refused"}""") }
		val kept = FakeProbe { base -> if (base == "ws://192.168.1.20:4790") InfoOutcome.Match(id, "Worbus Server") else InfoOutcome.Unreachable }
		val s = step(kept, keptAddress = prefs.hubUrl)
		s.await { it is HubStepState.Found }
		s.signIn()
		assertEquals(
			HubStepState.Found("http://192.168.1.20:4790", failure = SignInResult.Refused),
			s.await { it is HubStepState.Found && it.failure != null }
		)
		assertEquals("ws://192.168.1.20:4790", prefs.hubUrl, "the server stays set")
		assertTrue(prefs.hubEnabled)
		// Another address typed that does not pair puts the kept one back, not none.
		val other = FakeProbe { base ->
			when (base) {
				"ws://192.168.1.20:4790" -> InfoOutcome.Match(id, "Worbus Server")
				"ws://10.0.0.9:4790" -> InfoOutcome.Match("c".repeat(32), "Worbus Server")
				else -> InfoOutcome.Unreachable
			}
		}
		val t = step(other, keptAddress = prefs.hubUrl)
		t.await { it is HubStepState.Found }
		t.typeAddress()
		t.submit("10.0.0.9")
		t.await { it is HubStepState.Found && it.address == "http://10.0.0.9:4790" }
		t.signIn()
		t.await { it is HubStepState.Found && it.failure != null }
		assertEquals("ws://192.168.1.20:4790", prefs.hubUrl, "the kept address is back")
		assertEquals(id, store.read().serverId, "and its record")
		t.skip()
		assertEquals(HubStepState.Done(paired = false), t.await { it is HubStepState.Done })
		assertEquals("ws://192.168.1.20:4790", prefs.hubUrl)
		assertEquals(0, paired)
	}
}
