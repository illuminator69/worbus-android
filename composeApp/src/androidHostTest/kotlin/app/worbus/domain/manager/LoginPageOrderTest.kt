package app.worbus.domain.manager

import app.worbus.sourceFile
import app.worbus.ui.core.LoginUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.finder_looking
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * B-344 (Worbus P7, lane W's walk): in 2 of 15 fresh logins the Worbus Server step showed for ~0.4 s
 * and Home replaced it. The login page went Home on `Success && hubStep == null`, with the login's
 * state and its step in two StateFlows that the page collected apart. LoginManager wrote the step,
 * then Success, but a collector already queued on the main thread (from the last "Syncing" update)
 * reads the newest value when it runs, so the page can hold Success while the step's own collector is
 * still queued behind a frame: that frame goes Home, and the step is drawn only in the login page's
 * exit transition (the ~0.4 s W saw). The fix is one value for both ([LoginPage]).
 *
 * The page's collection is reproduced on a hand-run main thread ([MainQueue]): each collector is a
 * coroutine on it, as `collectAsStateWithLifecycle` is, and a frame may come between any two tasks.
 */
class LoginPageOrderTest {
	/** The main thread, one task at a time; the test says when each runs. */
	private class MainQueue : CoroutineDispatcher() {
		private val tasks = ArrayDeque<Runnable>()
		override fun dispatch(context: CoroutineContext, block: Runnable) {
			synchronized(tasks) { tasks.addLast(block) }
		}

		fun runOne(): Boolean {
			val task = synchronized(tasks) { tasks.removeFirstOrNull() } ?: return false
			task.run()
			return true
		}

		fun drain() {
			while (runOne()) continue
		}
	}

	private val main = MainQueue()
	private val page = CoroutineScope(SupervisorJob() + main)
	private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	@AfterTest
	fun tearDown() {
		page.cancel()
		background.cancel()
	}

	/** A step as the login offers it, looking for a server that never answers. */
	private fun offeredStep(): HubSignInStep {
		val settings = MemorySettings()
		val secrets = testSecrets(settings)
		val prefs = PreferenceManager(settings, secrets)
		val probe = HubInfoProbe { _, _ -> awaitCancellation() }
		val finder = HubFinder(probe, { null }, { true }, { "http://192.168.1.20:4533" }, { false }, prefs.serverRecordStore, log = {})
		val auth = HubAuth(prefs, secrets, { NavidromeAccount("bob", "pw") }, { "phone" }, { _, _, _ -> HubAuthReply.NoAnswer }, log = {})
		return HubSignInStep(finder, probe, auth, prefs, prefs.serverRecordStore, background, {}, { _, arg -> arg }, log = {})
	}

	private val syncing = LoginUiState.Syncing(0.9f, Res.string.finder_looking)

	@Test
	fun twoFlowsCollectedApartShowSuccessWithoutTheStep() {
		// The shape the page had on 94e8b2f3: the state and the step in two flows, collected apart.
		val loginState = MutableStateFlow<LoginUiState>(LoginUiState.Loading)
		val hubStep = MutableStateFlow<HubSignInStep?>(null)
		var shownState: LoginUiState = loginState.value
		var shownStep: HubSignInStep? = hubStep.value
		page.launch { loginState.collect { shownState = it } }
		page.launch { hubStep.collect { shownStep = it } }
		main.drain()
		// The login's last pull update, then (LoginManager's order) the step, then Success.
		loginState.value = syncing
		hubStep.value = offeredStep()
		loginState.value = LoginUiState.Success
		val homeFrames = mutableListOf<Int>()
		var task = 0
		while (main.runOne()) {
			task++
			// A frame here: the page's Home rule on what it holds.
			if (shownState is LoginUiState.Success && shownStep == null) homeFrames += task
		}
		assertEquals(listOf(1), homeFrames, "a frame after the state's collector and before the step's goes Home")
		assertTrue(shownStep != null, "the step arrives one task later, too late")
	}

	@Test
	fun onePageValueNeverShowsSuccessWithoutTheStep() {
		val holder = LoginPageHolder()
		var shown = holder.page.value
		page.launch { holder.page.collect { shown = it } }
		main.drain()
		holder.show(LoginUiState.Loading)
		main.drain()
		holder.show(syncing)
		val step = offeredStep()
		holder.loggedIn(step)
		val frames = mutableListOf<LoginPage>()
		while (main.runOne()) frames += shown
		frames += shown
		assertTrue(frames.none { it.goesHome }, "no frame goes Home while the step is offered: $frames")
		assertEquals(LoginPage(LoginUiState.Success, step), shown)
		// Only the step's end lets Home follow.
		holder.finishHubStep()
		main.drain()
		assertTrue(shown.goesHome)
		// A login with no step to offer goes Home at once; a logout never does.
		holder.loggedIn(null)
		main.drain()
		assertTrue(shown.goesHome)
		holder.reset()
		main.drain()
		assertFalse(shown.goesHome)
		assertEquals(LoginPage(), shown)
	}

	@Test
	fun theLoginPageGoesHomeOnOneValue() {
		val content = sourceFile("src/commonMain/kotlin/app/worbus/ui/screens/login/pages/Content.kt").readText()
		val manager = sourceFile("src/commonMain/kotlin/app/worbus/domain/manager/LoginManager.kt").readText()
		assertFalse("viewModel.hubStep.collect" in content, "the step must not be collected apart from the login's state")
		assertFalse("viewModel.loginState.collect" in content, "the login's state must not be collected apart from its step")
		assertTrue("viewModel.page.collectAsStateWithLifecycle()" in content, "the page collects one value")
		assertTrue(".goesHome" in content, "Home follows the one value's rule")
		val success = manager.substringAfter(".onSuccess {").substringBefore(".onFailure")
		assertTrue("loggedIn(" in success, "the login's Success and its step are written as one value")
	}
}
