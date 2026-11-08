package app.worbus.domain.manager

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.worbus.domain.repositories.DbRepository
import app.worbus.ui.screens.login.pages.sentenceResource
import org.jetbrains.compose.resources.getString
import app.worbus.ui.core.LoginUiState
import app.worbus.util.Logger

/**
 * What the login page shows once Log in has checked the fields. An attempt the fields refuse says
 * why under the field; the error box of an EARLIER attempt is about another address (the N9 walk:
 * "Can't reach the server at nas" stayed above "That isn't a server address"), so it goes.
 */
fun loginStateAfterFieldCheck(current: LoginUiState, fieldsOk: Boolean): LoginUiState =
	if (!fieldsOk && current is LoginUiState.Error) LoginUiState.Idle else current

/**
 * The login page as ONE value (B-344): the login's state and the Worbus Server step it offers, if any.
 * They were two StateFlows the page collected apart, and Home followed `Success && no step`: written
 * step-then-Success here, a collector of the state already queued on the main thread (by the last
 * "Syncing" update) read Success when it ran while the step's collector was still queued behind a
 * frame, so that frame went Home and the step only showed in the login page's exit transition. One
 * value has no such frame.
 */
data class LoginPage(val state: LoginUiState = LoginUiState.Idle, val hubStep: HubSignInStep? = null) {
	/** Home follows: logged in, and no step offered or the one offered has ended. */
	val goesHome: Boolean get() = state is LoginUiState.Success && hubStep == null
}

/** Every write of the login page, each one write of [page]. */
class LoginPageHolder {
	val page: StateFlow<LoginPage>
		field = MutableStateFlow(LoginPage())

	/** The form's state; a step offered stays. */
	fun show(state: LoginUiState) = page.update { it.copy(state = state) }

	/** The login succeeded: Success and the step it offers ([step] null for none) in the same write. */
	fun loggedIn(step: HubSignInStep?) {
		page.value = LoginPage(LoginUiState.Success, step)
	}

	/** The step is over ([HubStepState.Done]): Home follows. */
	fun finishHubStep() = page.update { it.copy(hubStep = null) }

	/** Logged out: the form, empty. */
	fun reset() {
		page.value = LoginPage()
	}
}

class LoginManager(
    private val repository: DbRepository,
    private val sessionManager: SessionManager,
    // B-279 review: its cached token is this user's; a logout forgets it.
    private val nativeApi: NativeApiManager,
    // B-100 / B-109: the login's library pull is the sync manager's full pull. SyncManager does
    // not depend on this class (checked 2026-10-31), so the graph has no cycle.
    private val syncManager: SyncManager,
    // B-108: whether a failed login names Android's local network permission.
    private val localNetwork: LocalNetworkAccess,
    // Worbus P2: a logout leaves the Worbus Server too (the owner's ruling, 2026-11-02). HubManager does
    // not depend on this class, so the graph has no cycle.
    private val hubManager: HubManager,
    // Worbus P3 (design §6.4): after a login with no Worbus Server set, look beside Navidrome once.
    // Worbus P7 (I-064): that look is the Worbus Server step's, shown before Home ([page]).
    private val hubFinder: HubFinder,
    private val hubAuth: HubAuth,
    private val preferenceManager: PreferenceManager
) {
	val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	private val pageHolder = LoginPageHolder()

	/**
	 * The login page: its state and, Worbus P7 (I-064), the Worbus Server step after a login that left
	 * no server set. The page shows the step instead of going Home while it is here; [finishHubStep]
	 * lets Home follow. One value, so the page never sees one without the other (B-344).
	 */
	val page: StateFlow<LoginPage> = pageHolder.page

	val instanceState = TextFieldState()
	val usernameState = TextFieldState()
	val passwordState = TextFieldState()

	var instanceError by mutableStateOf(false)
		private set
	/** Why the address field is red, for the line under it; null while it is not. */
	var instanceProblem by mutableStateOf<InstanceUrlProblem?>(null)
		private set
	var usernameError by mutableStateOf(false)
		private set
	var passwordError by mutableStateOf(false)
		private set

	/**
	 * A stored password that could not be read on this launch (B-279): the screen says why it
	 * asks again. A session whose password is missing for any other reason (a device transfer)
	 * only has its server and name filled in.
	 */
	var signInAgain by mutableStateOf(false)
		private set

	fun validateInstance() {
		val problem = (normalizeInstanceUrl(instanceState.text.toString()) as? InstanceUrlResult.Invalid)?.reason
		instanceProblem = problem
		instanceError = problem != null
	}

	fun validateUsername() {
		usernameError = usernameState.text.isBlank()
	}

	fun validatePassword() {
		passwordError = passwordState.text.isBlank()
	}

	fun validateStuff(): Boolean {
		validateInstance()
		validateUsername()
		validatePassword()
		val ok = !instanceError && !usernameError && !passwordError
		pageHolder.show(loginStateAfterFieldCheck(page.value.state, ok))
		return ok
	}

	/**
	 * Is the typed server on this network (B-108)? Total: it reads the text and nothing else,
	 * resolves no name, and an address that does not parse is not local (B-283, B-296: this used
	 * to be `InetAddress.getByName`, which threw out of the Log in tap for a typo).
	 */
	fun isLocalNetworkInstance(): Boolean = try {
		(normalizeInstanceUrl(instanceState.text.toString()) as? InstanceUrlResult.Ok)
			?.host?.let(::isPrivateHost) ?: false
	} catch (e: Exception) {
		Logger.w("LoginManager", "isLocalNetworkInstance: ${e::class.simpleName}")
		false
	}

	init {
		loadUser()
	}

	fun loadUser() {
		sessionManager.signInAgainAs()?.let { (instanceUrl, username) ->
			signInAgain = sessionManager.passwordWasUnreadable()
			if (instanceState.text.isEmpty()) instanceState.setTextAndPlaceCursorAtEnd(instanceUrl)
			if (usernameState.text.isEmpty()) usernameState.setTextAndPlaceCursorAtEnd(username)
		}
		scope.launch {
			if (sessionManager.isLoggedIn.value) {
				pageHolder.show(LoginUiState.Success)
			} else {
				pageHolder.show(LoginUiState.Idle)
			}
		}
	}

	fun login(): Boolean {
		if (!validateStuff()) return false
		val address = normalizeInstanceUrl(instanceState.text.toString()) as? InstanceUrlResult.Ok
			?: return false

		scope.launch {
			pageHolder.show(LoginUiState.Loading)

			try {
				sessionManager.login(
					address.url,
					usernameState.text.toString(),
					passwordState.text.toString()
				)

				syncManager.firstPull { progress, message ->
					pageHolder.show(LoginUiState.Syncing(progress, message))
				}.onSuccess {
					// Success and the step in one write (B-344): the page never heads Home ahead of it.
					pageHolder.loggedIn(if (!hubAddressSet(preferenceManager)) newHubStep() else null)
					syncManager.startPeriodicSync()
				}.onFailure { e ->
					pageHolder.show(failure(e as? Exception ?: Exception(e), address.host))
				}

			} catch (e: kotlin.coroutines.cancellation.CancellationException) {
				throw e
			} catch (e: Exception) {
				pageHolder.show(failure(e, address.host))
			}
		}

		return true
	}

	/**
	 * Show [e] on the login page as the login's error (B-302): the Log in tap's own catch sends
	 * what escapes it here, so nothing a login throws reaches the crash screen.
	 */
	fun reportLoginFailure(e: Exception) {
		val host = (normalizeInstanceUrl(instanceState.text.toString()) as? InstanceUrlResult.Ok)?.host
		pageHolder.show(failure(e, host))
	}

	private fun failure(e: Exception, host: String?): LoginUiState.Error {
		val kind = loginFailureKind(e)
		val blocked = kind == LoginFailureKind.UNREACHABLE &&
			failureLooksLocalNetworkBlocked(e, localNetwork.sdk, localNetwork.granted(), host)
		Logger.w("LoginManager", "login failed: $kind${if (blocked) " (local network not granted)" else ""}")
		return LoginUiState.Error(e, kind, host, blocked)
	}

	/** The Worbus Server step is over ([HubStepState.Done]): Home follows. */
	fun finishHubStep() = pageHolder.finishHubStep()

	private fun newHubStep() = HubSignInStep(
		finder = hubFinder,
		probe = KtorHubInfoProbe(),
		auth = hubAuth,
		preferenceManager = preferenceManager,
		store = preferenceManager.serverRecordStore,
		scope = scope,
		onPaired = { hubManager.restart() },
		words = { sentence, arg -> getString(sentenceResource(sentence), arg) }
	)

	fun logout() {
		pageHolder.reset()
		signInAgain = false
		// B-279: the login form outlives the session (a singleton), and kept the password typed.
		passwordState.clearText()
		// Worbus P2: sign out of the Worbus Server, stop the socket, drop the saved-queue cache.
		hubManager.leaveForLogout()
		sessionManager.logout()
		nativeApi.forget()
		scope.launch {
			repository.removeEverything()
		}
	}
}
