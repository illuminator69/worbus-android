package app.worbus.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * B-359: the direct AudioMuse route (no hub) put the Navidrome password in the fingerprint call's
 * query string, where AudioMuse's access log, any proxy's log and the URL itself keep it. AudioMuse
 * 3.7.1 reads the same fields from a JSON body on POST (`app_sonic_fingerprint.py`,
 * `generate_sonic_fingerprint_endpoint`, methods GET and POST), so nothing secret has to be in a URL.
 */
class FingerprintCredentialsTest {
	@Test
	fun theDirectRouteKeepsTheAccountOutOfTheQuery() {
		val query = fingerprintQuery(viaHub = false, count = 50, user = "alice", password = "hunter2")
		assertTrue(query.none { (_, value) -> value == "hunter2" }, "no password in the query: $query")
		assertTrue(query.none { (name, _) -> name.startsWith("navidrome_") }, "no account in the query: $query")
	}

	@Test
	fun theHubRouteSendsOnlyTheCount() {
		assertEquals(listOf("n" to "50"), fingerprintQuery(viaHub = true, count = 50, user = "alice", password = "hunter2"))
	}
}
