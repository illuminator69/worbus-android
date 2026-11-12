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
 * clients), not by guessing: a response, any status but a gateway's 502–504, says it answers;
 * nothing answering ([writeFailure]'s STOP kinds: refused, timed out, not resolved, a gateway error)
 * says it does not. While it does not, [probe] is asked every [probeEvery], and at once when the
 * network changes, so recovery does not wait for a request nobody makes while "offline".
 *
 * Starts reachable: an unknown server is not a down one, and the first request will tell.
 */
class ServerReachability(
	private val probeEvery: Duration = 30.seconds,
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
	private val _reachable = MutableStateFlow(true)
	val reachable: StateFlow<Boolean> = _reachable.asStateFlow()

	/** One small request to Navidrome (SessionManager's `ping`). Its outcome is reported by [observe]. */
	var probe: (suspend () -> Unit)? = null

	private var probing: Job? = null

	fun answered() {
		_reachable.value = true
		probing?.cancel()
		probing = null
	}

	fun failed(t: Throwable) {
		if (!isNothingAnswering(t)) return
		if (_reachable.value) _reachable.value = false
		startProbing()
	}

	/** A new session, server or network: what was learned about the old one no longer holds. */
	fun reset() = answered()

	/** The OS network changed: if Navidrome was down, ask now instead of at the next tick. */
	fun networkChanged() {
		if (_reachable.value) return
		probing?.cancel()
		probing = null
		startProbing(immediately = true)
	}

	private fun startProbing(immediately: Boolean = false) {
		if (probing?.isActive == true) return
		val ask = probe ?: return
		probing = scope.launch {
			if (!immediately) delay(probeEvery)
			while (isActive && !_reachable.value) {
				try {
					ask()
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
				val call = try {
					proceed(request)
				} catch (e: Throwable) {
					if (e !is CancellationException) failed(e)
					throw e
				}
				if (call.response.status.value in GATEWAY_DOWN) failed(GatewayDown(call.response.status.value))
				else answered()
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

/** Nothing answered: refused, timed out, a name that did not resolve, a TLS handshake met plain http, a gateway down. */
internal fun isNothingAnswering(t: Throwable): Boolean {
	if (causeChain(t).any { it is ServerReachability.GatewayDown }) return true
	return when (loginFailureKind(t)) {
		LoginFailureKind.UNREACHABLE, LoginFailureKind.TLS_TO_PLAIN_HTTP -> true
		else -> false
	}
}
