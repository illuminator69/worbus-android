package app.worbus.domain.manager

import app.worbus.di.synchronized
import app.worbus.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Worbus P7 N2 (I-064, design §5.2): right after the Navidrome login, before Home, the app looks for
 * the Worbus Server as it already does (P3, beside Navidrome) and offers it as one step: Found, Sign in
 * or Skip; Not found, Type its address or Skip. Skip is there from the first frame and ends the step
 * whatever is still waiting on the network, and the look waits at most the finder's own budget
 * ([HubFinder.lookBesideWithinBudget]), so nobody is left on a spinner. Nothing new on the wire: the
 * look and a typed address are `GET /hub/info` ([HubInfoProbe]), Sign in is the Server screen's own
 * `signInToFound` then `POST /hub/signin` ([HubAuth.signIn]).
 *
 * Only a sign-in that pairs leaves anything behind. A found or typed server is kept for the step's
 * Sign in; one that is refused, or a step that ends without pairing, leaves Settings › Server as it
 * was before the login (no address, Connect as it was), so it can pair later.
 *
 * B-409 (the owner's walk, 2026-11-13): after a logout and another user's login the Worbus Server's
 * address is still set (a logout signs the device out of it and keeps it), and the step was offered
 * only when no address was set, so the new user went straight Home, never offered the sign-in a first
 * login gets. [hubStepAfterLogin] offers it then too, started from the kept address ([keptAddress]),
 * and nothing the step does unpaired takes the address away (one found or typed that does not pair puts
 * the kept one back).
 *
 * B-409 review M1: only a kept server that belongs to the Navidrome just signed in to
 * ([keptServerBelongsTo]): the Navidrome it was paired beside, recorded at pairing
 * (`PreferenceManager.hubPairedNavidrome`), or, paired before that was recorded, a server whose host is
 * one the look beside this Navidrome asks. Sign in sends this account's token pair, so another
 * Navidrome's server is never offered: the step looks beside the new Navidrome instead. And the kept
 * address is asked (`GET /hub/info`) before it is called found: it must answer as the server the record
 * names, and never over plain text to a public host (the finder's rule, [groupFound]); otherwise the
 * step looks beside Navidrome as a first login does.
 */

/** Which Worbus Server step a login that just succeeded offers, or null for none (B-409). */
enum class HubStepStart {
	/** No address set: look beside Navidrome (P7, I-064). */
	LookBeside,

	/** An address set, Connect on, and this device not signed in to it: offer that server's sign-in. */
	KeptAddress
}

/**
 * B-409: the step after a Navidrome login. No address: look beside Navidrome. An address with Connect
 * on and no device secret (the logout before this login signed the device out, or it never paired):
 * offer the kept server's sign-in. Signed in already, or Connect turned off by the user: none.
 */
fun hubStepAfterLogin(addressSet: Boolean, connectOn: Boolean, signedIn: Boolean, keptBelongsHere: Boolean): HubStepStart? = when {
	!addressSet -> HubStepStart.LookBeside
	signedIn || !connectOn -> null
	keptBelongsHere -> HubStepStart.KeptAddress
	// Review M1: another Navidrome's server is never offered; look beside this one, the kept address kept.
	else -> HubStepStart.LookBeside
}

/**
 * Review M1: whether the kept Worbus Server at [keptAddress] belongs to the Navidrome at [navidromeUrl]:
 * the one it was paired beside ([pairedNavidrome], recorded at each pairing), or, with none recorded (a
 * pairing made before it was), a server whose host is one the look beside [navidromeUrl] asks
 * ([candidatesFor]).
 */
fun keptServerBelongsTo(navidromeUrl: String, pairedNavidrome: String, keptAddress: String): Boolean {
	if (pairedNavidrome.isNotBlank()) return sameNavidrome(pairedNavidrome, navidromeUrl)
	val host = normaliseHubAddress(keptAddress)?.let(::hubAddressHost)?.lowercase() ?: return false
	return candidatesFor(navidromeUrl).any { candidate ->
		normaliseHubAddress(candidate)?.let(::hubAddressHost)?.lowercase() == host
	}
}

/** Two Navidrome addresses are one server: the same text, case and a trailing slash aside. */
fun sameNavidrome(a: String, b: String): Boolean {
	fun key(url: String) = url.trim().trimEnd('/').lowercase()
	return key(a).isNotEmpty() && key(a) == key(b)
}

/** Where the step stands; the screen draws one layout per state (`HubStep.kt`). */
sealed interface HubStepState {
	/** Looking beside Navidrome. Skip is offered. */
	data object Finding : HubStepState

	/**
	 * A Worbus Server answered at [address] (its HTTP base, `http://192.168.1.20:4790`): Sign in and Skip.
	 * [failure]: the last Sign in's answer when it did not pair, said under the address.
	 */
	data class Found(val address: String, val failure: SignInResult? = null) : HubStepState

	/** Nothing answered beside Navidrome within the budget: Type its address and Skip. */
	data object NotFound : HubStepState

	/** The address field; [error] is what the last address typed came to, said under the field. */
	data class Typing(val error: String?) : HubStepState

	/** A Worbus Server older than the app (the finder's 426, or a sign-in route it lacks) at [address]: Continue. */
	data class OlderServer(val address: String) : HubStepState

	/**
	 * Sign in was tapped and `POST /hub/signin` is out, to the server offered at [address] (as [Found]
	 * said it, so a screen drawn afresh, after a rotation, still says where). Skip is still offered.
	 */
	data class SigningIn(val address: String) : HubStepState

	/** The step is over; Home follows. [paired]: this device signed in to the Worbus Server. */
	data class Done(val paired: Boolean) : HubStepState
}

/** The step's sentences with one argument, read from strings.xml by the screen's [HubSignInStep.words]. */
enum class HubStepSentence {
	/** "No Worbus Server answers at {address}." */
	NoAnswerAt,

	/** The finder's own "Found a Worbus Server at {address}, but not encrypted." */
	NotEncryptedAt
}

class HubSignInStep(
	private val finder: HubFinder,
	private val probe: HubInfoProbe,
	private val auth: HubAuth,
	private val preferenceManager: PreferenceManager,
	private val store: HubServerRecordStore,
	private val scope: CoroutineScope,
	/** A sign-in that paired: the socket starts over with the new credential (the app: [HubManager.restart]). */
	private val onPaired: () -> Unit,
	/** A sentence with its one argument (the app: compose resources' `getString`). */
	private val words: suspend (HubStepSentence, String) -> String,
	private val log: (String) -> Unit = { Logger.i("HubSignInStep", it) },
	/** B-409: the address already set ([HubStepStart.KeptAddress]); null looks beside Navidrome. */
	private val keptAddress: String? = null
) {
	private val _state = MutableStateFlow<HubStepState>(HubStepState.Finding)
	val state: StateFlow<HubStepState> = _state.asStateFlow()

	private val _checking = MutableStateFlow(false)

	/** A typed address is being asked; the screen holds its button meanwhile. */
	val checking: StateFlow<Boolean> = _checking.asStateFlow()

	/** The server Sign in pairs with, and the normalised address it answered at. */
	private var offered: Pair<Found.Server, String>? = null

	/** Connect as it was before the step wrote anything: put back when a sign-in does not pair. */
	private val enabledBefore = preferenceManager.hubEnabled

	/**
	 * B-409: the address and record set before the step, put back when one the step wrote does not pair
	 * (a kept server's, or, review M1, another Navidrome's that the step looked past). None set: none.
	 */
	private val urlBefore = preferenceManager.hubUrl
	private val recordBefore = if (urlBefore.isNotBlank()) store.read() else null

	/** Review M1: the kept address answered as the server its record names, so Sign in may go there. */
	@kotlin.concurrent.Volatile
	private var keptVerified = false

	/** The step's Sign in wrote an address and a record that a pairing has not yet made good. */
	private var stored = false

	@kotlin.concurrent.Volatile
	private var ended = false

	private val lock = Any()
	private var job: Job? = null

	init {
		replaceJob {
			// B-409, review M1: the kept server is asked first, and offered only as the server its record names.
			if (keptAddress != null && keptAnswers(keptAddress)) {
				keptVerified = true
				_state.value = HubStepState.Found(httpBaseOfAddress(keptAddress) ?: keptAddress)
				log("offering the kept address's sign-in")
				return@replaceJob
			}
			val found = try {
				finder.lookBesideWithinBudget()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				log("look failed: ${e::class.simpleName}")
				emptyList()
			}
			val server = found.filterIsInstance<Found.Server>().firstOrNull()
			val older = found.filterIsInstance<Found.OlderAt>().firstOrNull()
			_state.value = when {
				server != null -> offer(server, server.addresses.first())
				older != null -> HubStepState.OlderServer(httpBaseOfAddress(older.address) ?: older.address)
				else -> HubStepState.NotFound
			}
			log("looked beside Navidrome: ${_state.value::class.simpleName}")
		}
	}

	/**
	 * Review M1: the kept address answers `/hub/info` as the server the record names (any server when the
	 * record has no id yet), over an address the finder would offer: encrypted, or private.
	 */
	private suspend fun keptAnswers(kept: String): Boolean {
		val candidate = normaliseHubAddress(kept) ?: return false
		val timeout = probeTimeoutMs(candidate)
		val outcome = try {
			withTimeoutOrNull(timeout + PROBE_ANSWER_SLACK_MS) { probe.probe(candidate, timeout) } ?: InfoOutcome.Unreachable
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			InfoOutcome.Unreachable
		}
		val server = groupFound(listOf(candidate to outcome), olderCounts = true).firstOrNull() as? Found.Server
		val recordId = recordBefore?.serverId
		val ok = server != null && (recordId == null || recordId == server.serverId)
		log("kept address asked: ${outcome::class.simpleName}${if (server != null && !ok) " (another server)" else ""}")
		return ok
	}

	/** Sign in to the server offered ([HubStepState.Found]). */
	fun signIn() {
		if (ended) return
		val shown = (_state.value as? HubStepState.Found)?.address ?: return
		// B-409: the kept address is already Settings' own; only a found or typed one is written first.
		val offer = offered
		if (offer == null && !keptVerified) return
		_state.value = HubStepState.SigningIn(shown)
		replaceJob {
			if (offer != null) {
				val (server, address) = offer
				// Review Minor 4: kept only as far as signInToFound wrote it (it writes nothing for an address
				// it cannot keep); with nothing written there is nothing to sign in with, nor to take back.
				stored = signInToFound(preferenceManager, store, server, address) {}
				if (!stored) {
					_state.value = HubStepState.Found(shown, failure = SignInResult.NoAccount)
					log("sign-in not asked: the address was not kept")
					return@replaceJob
				}
			}
			val result = try {
				auth.signIn()
			} catch (e: CancellationException) {
				forgetStored()
				throw e
			} catch (e: Exception) {
				log("sign-in failed: ${e::class.simpleName}")
				SignInResult.Unreachable(navidrome = false)
			}
			if (result is SignInResult.SignedIn) {
				stored = false
				ended = true
				// Review M3 (P3): the login's look is done with; the Server screen does not show it as a home look.
				finder.forgetResults()
				onPaired()
				_state.value = HubStepState.Done(paired = true)
				log("paired")
				return@replaceJob
			}
			forgetStored()
			_state.value = if (result == SignInResult.OldServer) HubStepState.OlderServer(shown)
			else HubStepState.Found(shown, failure = result)
			log("sign-in did not pair: $result")
		}
	}

	/** Skip (and Continue): ends the step now, whatever is still waiting on the network. */
	fun skip() {
		if (ended) return
		ended = true
		val running = synchronized(lock) { job }
		scope.launch {
			running?.cancelAndJoin()
			_checking.value = false
			// A sign-in that paired just before the tap stands: the step says what is true.
			_state.value = HubStepState.Done(paired = auth.serverSet)
			log("ended by Skip")
		}
	}

	/** Type its address: the field, empty. */
	fun typeAddress() {
		if (ended) return
		when (_state.value) {
			HubStepState.NotFound, is HubStepState.Found, is HubStepState.OlderServer -> _state.value = HubStepState.Typing(null)
			else -> Unit
		}
	}

	/** Ask [address] (as typed) for `GET /hub/info`; it is kept for Sign in only when a Worbus Server answers. */
	fun submit(address: String) {
		if (ended || _state.value !is HubStepState.Typing || _checking.value) return
		val typed = address.trim()
		if (typed.isEmpty()) return
		_checking.value = true
		replaceJob {
			try {
				_state.value = checkTyped(typed)
			} finally {
				_checking.value = false
			}
		}
	}

	private suspend fun checkTyped(typed: String): HubStepState {
		val candidate = typedHubCandidate(typed) ?: return HubStepState.Typing(words(HubStepSentence.NoAnswerAt, typed))
		val timeout = probeTimeoutMs(candidate)
		// The probe's own timeout plus the slack the look has (review Minor 2): an answer on its way as
		// the window closes is a server found, never "No Worbus Server answers".
		val outcome = withTimeoutOrNull(timeout + PROBE_ANSWER_SLACK_MS) { probe.probe(candidate, timeout) } ?: InfoOutcome.Unreachable
		log("typed address asked: ${outcome::class.simpleName}")
		return when (val found = groupFound(listOf(candidate to outcome), olderCounts = true).firstOrNull()) {
			is Found.Server -> offer(found, candidate)
			is Found.OlderAt -> HubStepState.OlderServer(httpBaseOfAddress(found.address) ?: found.address)
			is Found.NotEncrypted -> HubStepState.Typing(words(HubStepSentence.NotEncryptedAt, shownAddress(found.address)))
			null -> HubStepState.Typing(words(HubStepSentence.NoAnswerAt, typed))
		}
	}

	private fun offer(server: Found.Server, address: String): HubStepState {
		offered = server to address
		return HubStepState.Found(httpBaseOfAddress(address) ?: address)
	}

	/** What the step's Sign in wrote goes: the address, the record (what was answered Not now kept), Connect. */
	private fun forgetStored() {
		if (!stored) return
		stored = false
		// B-409: an address set before the step is put back, not none.
		store.startOver(recordBefore ?: HubServerRecord(promptDismissed = store.read().promptDismissed), hubUrl = urlBefore)
		preferenceManager.hubEnabled = enabledBefore
	}

	/** One job at a time: the next starts once the one before it is gone, so its writes come last. */
	private fun replaceJob(body: suspend CoroutineScope.() -> Unit) {
		synchronized(lock) {
			val previous = job
			job = scope.launch {
				previous?.cancelAndJoin()
				body()
			}
		}
	}
}

/**
 * A typed address as the step asks it: one with no port is on the Worbus Server's port when it is
 * plain (no scheme, `http://` or `ws://`: "10.0.0.9" and "http://10.0.0.9" are both
 * `ws://10.0.0.9:4790`, lane N review Minor 5); an encrypted one (`https://`, `wss://`) is a reverse
 * proxy's and stays on 443; one with a port is taken as typed. Then as [normaliseHubAddress] reads it.
 * Null when it does not normalise.
 */
fun typedHubCandidate(raw: String): String? {
	val text = raw.trim()
	val schemeEnd = text.indexOf("://")
	val start = if (schemeEnd < 0) 0 else schemeEnd + 3
	val scheme = if (schemeEnd < 0) null else text.substring(0, schemeEnd).lowercase()
	if (scheme != null && scheme != "http" && scheme != "ws") return normaliseHubAddress(text)
	val rest = text.substring(start)
	val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
	val authority = rest.substring(0, end)
	val hostPort = authority.substringAfterLast('@')
	val hasPort = if (hostPort.startsWith("[")) "]:" in hostPort else ':' in hostPort
	return normaliseHubAddress(if (hasPort) text else text.substring(0, start) + "$authority:$HUB_PORT" + rest.substring(end))
}
