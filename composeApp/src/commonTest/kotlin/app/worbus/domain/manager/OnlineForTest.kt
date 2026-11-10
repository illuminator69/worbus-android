package app.worbus.domain.manager

import app.worbus.domain.models.settings.OfflineMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/* Q-165: a validated Wi-Fi with Navidrome down is offline. */
class OnlineForTest {
	@Test
	fun wifiUpWithNavidromeDownIsOffline() {
		assertFalse(onlineFor(OfflineMode.Auto, networkUp = true, cellular = false, serverAnswers = false))
		assertFalse(onlineFor(OfflineMode.NoWiFi, networkUp = true, cellular = false, serverAnswers = false))
	}

	@Test
	fun theModesStillDecideWhenNavidromeAnswers() {
		assertTrue(onlineFor(OfflineMode.Auto, networkUp = true, cellular = true, serverAnswers = true))
		assertFalse(onlineFor(OfflineMode.NoWiFi, networkUp = true, cellular = true, serverAnswers = true))
		assertFalse(onlineFor(OfflineMode.Forced, networkUp = true, cellular = false, serverAnswers = true))
		assertFalse(onlineFor(OfflineMode.Auto, networkUp = false, cellular = false, serverAnswers = true))
	}
}
