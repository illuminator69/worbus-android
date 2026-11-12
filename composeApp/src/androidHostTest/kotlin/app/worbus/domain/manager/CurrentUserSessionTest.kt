package app.worbus.domain.manager

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/*
 * Review (fix round) nit 8: `getCachedUser`'s fetch was not tied to the session. One in flight at
 * logout landed after `currentUser = null` (the old account's user, share permission and all, read
 * while logged out and by the next login until its own fetch replaced it), or after the next login's
 * own fetch. A user fetched for a session is kept only while that session is the current one. The
 * stand-in answers `getUser` for whichever user is asked, slowly.
 */
class CurrentUserSessionTest {
	private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
		createContext("/") { exchange ->
			val name = Regex("""(?:^|&)username=([^&]*)""").find(exchange.requestURI.rawQuery.orEmpty())?.groupValues?.get(1) ?: "alice"
			if (exchange.requestURI.path.contains("getUser")) Thread.sleep(if (name == "alice") 600 else 50)
			val body = ("""{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.64.2","openSubsonic":true,""" +
				""""user":{"username":"$name","email":"","scrobblingEnabled":true,"adminRole":false,"settingsRole":true,"downloadRole":true,""" +
				""""uploadRole":false,"playlistRole":true,"coverArtRole":true,"commentRole":false,"podcastRole":false,"streamRole":true,""" +
				""""jukeboxRole":false,"shareRole":${name == "alice"},"videoConversionRole":false,"folder":[1]}}}""").toByteArray()
			exchange.responseHeaders.add("Content-Type", "application/json")
			exchange.sendResponseHeaders(200, body.size.toLong())
			exchange.responseBody.use { it.write(body) }
		}
		executor = Executors.newCachedThreadPool()
		start()
	}
	private val settings = MemorySettings()
	private val url = "http://127.0.0.1:${server.address.port}"

	@AfterTest
	fun stop() = server.stop(0)

	@Test
	fun aFetchThatOutlivesALogoutDoesNotSetTheOldUser() = runBlocking {
		val secrets = testSecrets(settings)
		settings.putString("instanceUrl", url)
		settings.putString("username", "alice")
		secrets.navidromePassword = "hunter2"
		val session = SessionManager(settings, PreferenceManager(settings, secrets), secrets, ServerReachability())
		// The init's fetch for alice is in flight (600 ms).
		Thread.sleep(100)
		session.logout()
		Thread.sleep(1_000)
		assertNull(session.currentUserState.value, "logged out: alice's late answer is not the user")
		assertNull(session.getCachedUser(), "nor is it handed to a sheet")

		session.login(url, "bob", "pw")
		Thread.sleep(300)
		assertEquals("bob", session.currentUserState.value?.username)
	}
}
