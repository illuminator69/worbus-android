package app.worbus.domain.manager

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * B-307: SessionManager.login wrapped EVERY failure in "Failed to connect to the instance. Please
 * check your credentials", so the login page's details (the stack trace) told someone whose server
 * was not even reachable to check their password. Here a real login against a port nothing listens on.
 */
class LoginFailureWordsTest {
	@Test
	fun nothingAnsweringIsNotCalledACredentialsProblem() = runBlocking {
		val settings = MemorySettings()
		val secrets = testSecrets(settings)
		val session = SessionManager(settings, PreferenceManager(settings, secrets), secrets, ServerReachability())
		val closed = java.net.ServerSocket(0).use { it.localPort }
		val failure = runCatching { session.login("http://127.0.0.1:$closed", "alice", "hunter2") }.exceptionOrNull()
		assertTrue(failure != null, "the login failed")
		assertTrue(loginFailureKind(failure) == LoginFailureKind.UNREACHABLE, "classified as unreachable: $failure")
		val words = causeChain(failure).joinToString(" / ") { it.message.orEmpty() }
		assertFalse("credentials" in words.lowercase(), "the failure's words blame the credentials: $words")
	}
}
