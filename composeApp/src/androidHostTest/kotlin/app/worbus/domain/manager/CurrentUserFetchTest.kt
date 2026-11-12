package app.worbus.domain.manager

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * B-308: `canUserShare()` → `getCachedUser()` runs in composition (the avatar menu, the song and
 * collection sheets). With no session it launched a fetch anyway, which failed and logged an
 * error-level stack trace at every launch ("username is blank"; here: a saved name with no password,
 * which is no session either). With a session, every recomposition while the user had not loaded yet
 * launched another `getUser` (a mutex serialised them, nothing de-duplicated them). A local server
 * stands in for Navidrome and counts the requests.
 */
class CurrentUserFetchTest {
	private val getUsers = AtomicInteger()
	private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
		createContext("/") { exchange ->
			if (exchange.requestURI.path.contains("getUser")) {
				getUsers.incrementAndGet()
				Thread.sleep(400)
			}
			// A real getUser answer, so the first fetch lands: a failed one ends the session's scope in
			// a host test (Logger.e has no android.util.Log here) and would hide the repeats.
			val body = ("""{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.64.2","openSubsonic":true,""" +
				""""user":{"username":"alice","email":"","scrobblingEnabled":true,"adminRole":false,"settingsRole":true,"downloadRole":true,""" +
				""""uploadRole":false,"playlistRole":true,"coverArtRole":true,"commentRole":false,"podcastRole":false,"streamRole":true,""" +
				""""jukeboxRole":false,"shareRole":true,"videoConversionRole":false,"folder":[1]}}}""").toByteArray()
			exchange.responseHeaders.add("Content-Type", "application/json")
			exchange.sendResponseHeaders(200, body.size.toLong())
			exchange.responseBody.use { it.write(body) }
		}
		executor = Executors.newCachedThreadPool()
		start()
	}
	private val settings = MemorySettings()

	@AfterTest
	fun stop() = server.stop(0)

	private fun session(password: String): SessionManager {
		val secrets = testSecrets(settings)
		settings.putString("instanceUrl", "http://127.0.0.1:${server.address.port}")
		settings.putString("username", "alice")
		secrets.navidromePassword = password
		return SessionManager(settings, PreferenceManager(settings, secrets), secrets, ServerReachability())
	}

	@Test
	fun withNoSessionNothingIsFetched() {
		val session = session(password = "")
		repeat(5) { session.getCachedUser() }
		Thread.sleep(1_000)
		assertEquals(0, getUsers.get(), "no session: no user to fetch")
	}

	@Test
	fun recompositionsWhileTheUserLoadsAskOnce() {
		val session = session(password = "hunter2")
		repeat(5) { session.getCachedUser(); Thread.sleep(20) }
		Thread.sleep(3_000)
		assertEquals(1, getUsers.get(), "one getUser for one session, however often the sheets ask")
		kotlin.test.assertNotNull(session.getCachedUser(), "the user landed (the stand-in's answer is a real one)")
	}
}
