package app.worbus.domain.manager

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.worbus.di.synchronized
import kotlin.concurrent.Volatile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Whether the session's Navidrome answers (Q-165). "Online" used to be the OS's word only: Wi-Fi up
 * with Navidrome down (stopped, the NAS asleep, the tunnel gone) counted as online, so every list
 * offered songs that could not play, the write queue tried and failed, and nothing switched to the
 * downloaded copies. [ConnectivityManager.isOnline] is now the network AND this.
 *
 * Fed by what the app's own Navidrome requests meet ([observe], a client plugin on the Subsonic
 * clients and the streaming client), not by guessing:
 * - a response, any status but a gateway's 502–504, says it answers;
 * - only a failure to reach it at all says it may be down ([isServerDown]: refused, no route, a name
 *   that did not resolve, a connect timeout, a gateway's 502–504, a TLS handshake met plain http).
 *   A read that stalls or a connection reset after it was made is a slow or flaky link to a server
 *   that is there (review major 2: one stalled `getAlbumList2` page used to take the whole app
 *   offline for 30 s, and the player then skipped streamable songs);
 * - and even that is not believed from one request: [probe] is asked at once, and only when it
 *   fails the same way does the app count Navidrome down.
 * While it is down, [probe] is asked every [probeEvery], and at once when the network changes, so
 * recovery does not wait for a request nobody makes while "offline".
 *
 * Starts reachable: an unknown server is not a down one, and the first request will tell. With no
 * [probe] (no session), a failure is not acted on: there is nothing to ask and nothing to be offline from.
 */
class ServerReachability(
	private val probeEvery: Duration = 30.seconds,
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
	private val _reachable = MutableStateFlow(true)
	val reachable: StateFlow<Boolean> = _reachable.asStateFlow()

	/** One small request to Navidrome (SessionManager's `ping`), null without a session. Its outcome is reported by [observe]. */
	@Volatile
	var probe: (suspend () -> Unit)? = null

	/** Guards [probing], [confirming], [verdict] and the tickets: reports arrive on OkHttp's threads. */
	private val lock = Any()
	private var probing: Job? = null
	private var confirming: Job? = null

	/**
	 * Review (fix round) minor 4: reports are ordered by when their REQUEST STARTED, not by when they
	 * arrive. Each exchange takes a ticket as it is sent ([startTicket]); a report without one is
	 * taken as starting now. A failure from a request that started before something answered is
	 * older evidence than that answer and is not acted on, and during a confirm the newest-started
	 * report decides: a slow name lookup begun before Navidrome came back, failing after the probe
	 * answered, used to take the app offline for 30 s.
	 */
	private var tickets = 0L

	/** The start ticket of the newest-started request that answered. */
	private var newestAnswer = 0L

	/** While [confirming]: what the exchanges reported meanwhile said (true answered, false down), and the ticket of the request that said it. */
	private var verdict: Boolean? = null
	private var verdictTicket = 0L

	/** A ticket for a request starting now. */
	fun startTicket(): Long = synchronized(lock) { ++tickets }

	fun answered(started: Long = startTicket()) {
		synchronized(lock) {
			if (started > newestAnswer) newestAnswer = started
			if (confirming != null && started > verdictTicket) {
				verdict = true
				verdictTicket = started
			}
			probing?.cancel()
			probing = null
		}
		_reachable.value = true
	}

	fun failed(t: Throwable, started: Long = startTicket()) {
		if (!isServerDown(t)) return
		synchronized(lock) {
			// Something that started later has answered since: this is older news.
			if (started < newestAnswer) return@synchronized
			if (confirming != null) {
				if (started > verdictTicket) {
					verdict = false
					verdictTicket = started
				}
				return@synchronized
			}
			if (!_reachable.value) {
				startProbingLocked()
				return@synchronized
			}
			val ask = probe ?: return@synchronized
			verdict = null
			verdictTicket = 0L
			confirming = scope.launch { confirm(ask) }
		}
	}

	/** One failure seen while Navidrome counted as up: ask it once, now, and believe that. */
	private suspend fun confirm(ask: suspend () -> Unit) {
		var thrown: Throwable? = null
		try {
			ask()
		} catch (e: Exception) {
			if (e is CancellationException) {
				synchronized(lock) { confirming = null; verdict = null; verdictTicket = 0L }
				throw e
			}
			thrown = e
		}
		synchronized(lock) {
			val down = verdict == false || (verdict == null && thrown != null && isServerDown(thrown))
			confirming = null
			verdict = null
			verdictTicket = 0L
			if (down) {
				_reachable.value = false
				startProbingLocked()
			}
		}
	}

	/** A new session, server or network: what was learned about the old one no longer holds. */
	fun reset() {
		synchronized(lock) {
			confirming?.cancel()
			confirming = null
			verdict = null
			verdictTicket = 0L
			// Tickets keep counting: a straggler from before the reset stays older than what follows.
			probing?.cancel()
			probing = null
		}
		_reachable.value = true
	}

	/** The OS network changed: if Navidrome was down, ask now instead of at the next tick. */
	fun networkChanged() {
		if (_reachable.value) return
		synchronized(lock) {
			probing?.cancel()
			probing = null
			startProbingLocked(immediately = true)
		}
	}

	/** The caller holds [lock]. */
	private fun startProbingLocked(immediately: Boolean = false) {
		if (probing?.isActive == true || probe == null) return
		probing = scope.launch {
			if (!immediately) delay(probeEvery)
			while (isActive && !_reachable.value) {
				try {
					// Logged out meanwhile: the probe is gone, and so is the server it asked.
					(probe ?: break)()
				} catch (e: Exception) {
					if (e is CancellationException) throw e
					// Reported by the plugin already; wait for the next tick.
				}
				if (_reachable.value) break
				delay(probeEvery)
			}
		}
	}

	/**
	 * Install on a client: every exchange it has with Navidrome reports to this. [onlyFor] picks the
	 * requests that are Navidrome's, for a client that also talks to other hosts.
	 */
	fun observe(config: HttpClientConfig<*>, onlyFor: (String) -> Boolean = { true }) {
		config.install(createClientPlugin("NavidromeReachability") {
			on(Send) { request ->
				if (!onlyFor(request.url.buildString())) return@on proceed(request)
				val started = startTicket()
				val call = try {
					proceed(request)
				} catch (e: Throwable) {
					if (e !is CancellationException) failed(e, started)
					throw e
				}
				if (call.response.status.value in GATEWAY_DOWN) failed(GatewayDown(call.response.status.value), started)
				else answered(started)
				call
			}
		})
	}

	/** A reverse proxy answering for a Navidrome behind it that is down. */
	class GatewayDown(val status: Int) : Exception("Response status `$status` from the proxy")

	private companion object {
		val GATEWAY_DOWN = 502..504
	}
}

/**
 * Navidrome could not be reached at all (review major 2): the connection was refused, had no route,
 * timed out connecting, the name did not resolve, an https handshake met plain http, or a reverse
 * proxy answered 502–504 for it. Not a read timeout or a reset after the connection was made (a
 * slow or flaky link to a server that is there), and not a request outliving its own time cap.
 */
internal fun isServerDown(t: Throwable): Boolean {
	val chain = causeChain(t)
	if (chain.any { it is ServerReachability.GatewayDown || it.isGatewayFailure() }) return true
	if (loginFailureKind(t) == LoginFailureKind.TLS_TO_PLAIN_HTTP) return true
	return chain.any { it::class.simpleName in CONNECT_FAILURES }
}

/**
 * Known by TYPE only, never by a message (review of the fix round, minor 3): Ktor writes the request
 * URL into its read-timeout message, so "connect" in a message matched a search for "Disconnect" and
 * every request to a host named `*connect*`. A connect timeout is `ConnectTimeoutException`, both
 * Ktor's own (HttpTimeout's connect phase) and OkHttp's, which Ktor's OkHttp engine maps to it
 * (`mapOkHttpException`) before anything here sees it.
 */
private val CONNECT_FAILURES = setOf(
	"UnknownHostException",
	"UnresolvedAddressException",
	"ConnectException",
	"NoRouteToHostException",
	"PortUnreachableException",
	"ConnectTimeoutException"
)
