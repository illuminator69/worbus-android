package app.worbus.domain.manager.cast

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * B-115 (Android half): discovery keeps the port a Cast device advertises (`DiscoveredCast.port`, the
 * SRV record's), but the bridge took the host alone, so the channel dialled CastProtocol.CAST_PORT
 * (8009) and the reachability probe did too. A speaker group advertises on a port of its own, so a
 * group row drove its leader. Source-level: the bridge's construction, its address updates, its
 * dial and its probe all carry the advertised port (the desktop's same fix: `receiver-info.ts`).
 */
class CastPortTest {
	private val bridge = sourceFile("src/androidMain/kotlin/app/worbus/domain/manager/cast/CastDeviceBridge.kt").readText()
	private val manager = sourceFile("src/androidMain/kotlin/app/worbus/domain/manager/cast/CastBridgeManager.kt").readText()

	@Test
	fun theBridgeIsGivenAndKeptTheAdvertisedPort() {
		assertTrue(Regex("""CastDeviceBridge\((?s:.*?)port = device\.port""").containsMatchIn(manager), "the bridge is built with the advertised port")
		assertTrue(Regex("""existing\.updateAddress\(device\.host,\s*device\.port\)""").containsMatchIn(manager), "a reconcile keeps host and port current")
	}

	@Test
	fun theBridgeDialsAndProbesThatPort() {
		assertTrue(Regex("""CastChannel\(host,\s*port\)""").containsMatchIn(bridge), "the channel dials the advertised port")
		assertTrue(Regex("""InetSocketAddress\(host,\s*port\)""").containsMatchIn(bridge), "the probe asks that port")
		assertEquals(emptyList(), bridge.lines().filter { "CAST_PORT" in it.substringBefore("//") }, "no fixed 8009 left in the bridge")
	}
}
