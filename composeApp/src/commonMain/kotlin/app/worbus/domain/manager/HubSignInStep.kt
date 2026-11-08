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
 */

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

	/** Sign in was tapped and `POST /hub/signin` is out. Skip is still offered. */
	data object SigningIn : HubStepState

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
	private val log: (String) -> Unit = { Logger.i("HubSignInStep", it) }
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

	/** The step's Sign in wrote an address and a record that a pairing has not yet made good. */
	private var stored = false

	@kotlin.concurrent.Volatile
	private var ended = false

	private val lock = Any()
	private var job: Job? = null

	init {
		replaceJob {
			val found = try {
				finder.lookBesideWithinBudget()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				log("look failed: ${e::class.simpleName}")
				null
			}.orEmpty()
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

	/** Sign in to the server offered ([HubStepState.Found]). */
	fun signIn() {
		if (ended) return
		val shown = (_state.value as? HubStepState.Found)?.address ?: return
		val (server, address) = offered ?: return
		_state.value = HubStepState.SigningIn
		replaceJob {
			signInToFound(preferenceManager, store, server, address) {}
			stored = true
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
		val outcome = withTimeoutOrNull(timeout) { probe.probe(candidate, timeout) } ?: InfoOutcome.Unreachable
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
		store.startOver(HubServerRecord(promptDismissed = store.read().promptDismissed), hubUrl = "")
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
 * A typed address as the step asks it: one with no scheme and no port is on the Worbus Server's port
 * ("10.0.0.9" is `ws://10.0.0.9:4790`); anything else as [normaliseHubAddress] reads it (a reverse
 * proxy's `https://hub.example.com` stays on 443). Null when it does not normalise.
 */
fun typedHubCandidate(raw: String): String? {
	val text = raw.trim()
	if ("://" in text) return normaliseHubAddress(text)
	val slash = text.indexOf('/').let { if (it < 0) text.length else it }
	val authority = text.substring(0, slash)
	val hostPort = authority.substringAfterLast('@')
	val hasPort = if (hostPort.startsWith("[")) "]:" in hostPort else ':' in hostPort
	return normaliseHubAddress(if (hasPort) text else "$authority:$HUB_PORT${text.substring(slash)}")
}
