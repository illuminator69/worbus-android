package app.worbus.domain.manager

import app.worbus.domain.models.settings.OfflineMode
import kotlinx.coroutines.flow.StateFlow

expect class ConnectivityManager {
	val isCellular: StateFlow<Boolean>
	val isOnline: StateFlow<Boolean>

	/** Whether the device is currently charging (AC/USB/wireless). Used to gate charging-only downloads. */
	val isCharging: StateFlow<Boolean>
}

/**
 * Whether the app counts itself online (Q-165): the user's offline mode, the OS network, and
 * whether the session's Navidrome answers ([ServerReachability]). A validated Wi-Fi with Navidrome
 * down is offline: nothing that needs the server can work, and the downloaded copies should play.
 */
fun onlineFor(mode: OfflineMode, networkUp: Boolean, cellular: Boolean, serverAnswers: Boolean): Boolean = when (mode) {
	OfflineMode.Forced -> false
	OfflineMode.NoWiFi -> networkUp && !cellular && serverAnswers
	else -> networkUp && serverAnswers
}

