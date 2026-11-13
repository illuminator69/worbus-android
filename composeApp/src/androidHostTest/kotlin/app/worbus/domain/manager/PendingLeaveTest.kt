package app.worbus.domain.manager

import app.worbus.sourceFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * B-409 review m7: a logout's leave runs on after it (the sign-out, then the saved-queue cache and the
 * fill watches are forgotten). The login's Worbus Server step can now pair and restart the socket in the
 * same session, so that first connect waits for the leave: otherwise the new account's connect could push
 * the last account's saved queues into its own space.
 */
class PendingLeaveTest {
	@Test
	fun theNextConnectWaitsForTheLastAccountsLeave() = runBlocking<Unit> {
		val events = Collections.synchronizedList(mutableListOf<String>())
		val signOutAnswered = CompletableDeferred<Unit>()
		val leave = PendingLeave()
		leave.track(launch(Dispatchers.Default) {
			finishLeavingWorbusServer(
				pending = PendingSignOut("", null),
				auth = HubAuth(PreferenceManager(MemorySettings(), testSecrets(MemorySettings())), testSecrets(MemorySettings()), { null }, { null }, log = {}),
				awaitSocketGone = { signOutAnswered.await() },
				forgetSavedQueues = { events += "forgot saved queues" },
				forgetFillWatches = { events += "forgot fill watches" }
			)
		})
		val connect = launch(Dispatchers.Default) {
			leave.awaitLeft()
			events += "connect"
		}
		repeat(20) { kotlinx.coroutines.yield() }
		assertTrue("connect" !in events, "the connect waits while the sign-out is out: $events")
		signOutAnswered.complete(Unit)
		withTimeout(5_000) { connect.join() }
		assertEquals(listOf("forgot saved queues", "forgot fill watches", "connect"), events.toList())
	}

	@Test
	fun withNoLeaveTheConnectGoesAtOnce() = runBlocking<Unit> {
		withTimeout(1_000) { PendingLeave().awaitLeft() }
	}

	@Test
	fun theLoginsPairingRestartsOnlyAfterTheLeave() {
		// The wiring (HubManager needs Koin and a socket): the step's onPaired goes through the wait.
		val login = sourceFile("src/commonMain/kotlin/app/worbus/domain/manager/LoginManager.kt").readText()
		assertTrue("onPaired = { hubManager.restartAfterLeaving() }" in login)
		val hub = sourceFile("src/commonMain/kotlin/app/worbus/domain/manager/HubManager.kt").readText()
		assertTrue("pendingLeave.track(scope.launch {" in hub && "pendingLeave.awaitLeft()" in hub)
	}
}
