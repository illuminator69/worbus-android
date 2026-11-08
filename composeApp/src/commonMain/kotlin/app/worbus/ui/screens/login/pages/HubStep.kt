package app.worbus.ui.screens.login.pages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.kyant.capsule.ContinuousCapsule
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.action_continue
import worbus.composeapp.generated.resources.action_sign_in
import worbus.composeapp.generated.resources.action_skip
import worbus.composeapp.generated.resources.finder_default_name
import worbus.composeapp.generated.resources.finder_looking
import worbus.composeapp.generated.resources.finder_not_encrypted
import worbus.composeapp.generated.resources.hub_step_found_body
import worbus.composeapp.generated.resources.hub_step_found_title
import worbus.composeapp.generated.resources.hub_step_no_answer_at
import worbus.composeapp.generated.resources.hub_step_not_found_title
import worbus.composeapp.generated.resources.hub_step_older
import worbus.composeapp.generated.resources.hub_step_type_its_address
import worbus.composeapp.generated.resources.option_server_address
import app.worbus.domain.manager.HubSignInStep
import app.worbus.domain.manager.HubStepSentence
import app.worbus.domain.manager.HubStepState
import app.worbus.domain.manager.shownAddress
import app.worbus.domain.manager.statusOfSignIn
import app.worbus.ui.core.LoginUiState
import app.worbus.ui.theme.defaultFont

/*
 * Worbus P7 N2 (I-064): the Worbus Server step, shown by the login page after a Navidrome login and
 * before Home ([HubSignInStep]). It is the login page's own layout (headline, a line, the field, the
 * capsule button at the foot, the wavy bar while something is under way) with Skip beside the main
 * action in every state, at the same place. Back is Skip.
 */

/** Which layout a state draws; Signing in keeps the found server's, so nothing moves on the tap. */
private enum class HubStepLayout { Finding, Found, NotFound, Typing, Older }

private fun layoutOf(state: HubStepState): HubStepLayout? = when (state) {
	HubStepState.Finding -> HubStepLayout.Finding
	is HubStepState.Found, is HubStepState.SigningIn -> HubStepLayout.Found
	HubStepState.NotFound -> HubStepLayout.NotFound
	is HubStepState.Typing -> HubStepLayout.Typing
	is HubStepState.OlderServer -> HubStepLayout.Older
	is HubStepState.Done -> null
}

/** The strings.xml entry for each of the holder's sentences (`HubSignInStepTest` reads the same keys). */
fun sentenceResource(sentence: HubStepSentence): StringResource = when (sentence) {
	HubStepSentence.NoAnswerAt -> Res.string.hub_step_no_answer_at
	HubStepSentence.NotEncryptedAt -> Res.string.finder_not_encrypted
}

@Composable
fun HubStepContent(step: HubSignInStep, innerPadding: PaddingValues) {
	val state by step.state.collectAsStateWithLifecycle()
	val checking by step.checking.collectAsStateWithLifecycle()
	// The server offered, from the state itself (Found, or SigningIn, which keeps its address: review
	// Minor 3, a rotation while signing in redraws the "At …" line). Done keeps the last one on screen
	// until Home replaces the page (a holder: the state is what recomposes).
	val lastAddressRef = remember { arrayOfNulls<String>(1) }
	when (val s = state) {
		is HubStepState.Found -> lastAddressRef[0] = s.address
		is HubStepState.SigningIn -> lastAddressRef[0] = s.address
		else -> Unit
	}
	val offeredAddress = lastAddressRef[0]
	val failure = (state as? HubStepState.Found)?.failure
	// Done: the last layout stays, inert, until Home replaces the page.
	val lastLayoutRef = remember { arrayOfNulls<HubStepLayout>(1) }
	layoutOf(state)?.let { lastLayoutRef[0] = it }
	val layout = lastLayoutRef[0] ?: HubStepLayout.Finding
	val busy = state == HubStepState.Finding || state is HubStepState.SigningIn || checking
	// Saveable (`rememberTextFieldState` is `rememberSaveable` with TextFieldState's Saver): what was
	// typed survives a rotation, which recreates the activity (review Minor 3).
	val addressState = rememberTextFieldState()
	val addressFocus = remember { FocusRequester() }

	NavigationBackHandler(
		state = rememberNavigationEventState(NavigationEventInfo.None),
		isBackEnabled = true,
		onBackCompleted = { step.skip() }
	)

	Box {
		LoginScreenProgress(
			modifier = Modifier
				.align(Alignment.TopCenter)
				.padding(top = innerPadding.calculateTopPadding()),
			isBusy = busy,
			loginUiState = LoginUiState.Loading
		)

		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(innerPadding)
				.consumeWindowInsets(innerPadding)
				.imePadding(),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(6.dp)
		) {
			val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
			AnimatedContent(
				targetState = layout,
				modifier = Modifier
					.weight(1f)
					.widthIn(max = 600.dp),
				transitionSpec = { fadeIn(effectsSpec) togetherWith fadeOut(effectsSpec) },
				label = "hubStep"
			) { shown ->
				Column(
					modifier = Modifier
						.fillMaxSize()
						.verticalScroll(rememberScrollState())
				) {
					Spacer(Modifier.weight(1f))
					Text(
						text = stringResource(
							when (shown) {
								HubStepLayout.Finding -> Res.string.finder_default_name
								HubStepLayout.Found, HubStepLayout.Older -> Res.string.hub_step_found_title
								HubStepLayout.NotFound, HubStepLayout.Typing -> Res.string.hub_step_not_found_title
							}
						),
						style = MaterialTheme.typography.headlineMedium,
						fontFamily = defaultFont(round = 100f),
						modifier = Modifier.padding(horizontal = 16.dp)
					)
					when (shown) {
						HubStepLayout.Finding -> Text(
							text = stringResource(Res.string.finder_looking),
							modifier = Modifier.padding(horizontal = 16.dp)
						)

						HubStepLayout.Found -> offeredAddress?.let { address ->
							Text(
								text = stringResource(Res.string.hub_step_found_body, shownAddress(address)),
								modifier = Modifier.padding(horizontal = 16.dp)
							)
							failure?.let(::statusOfSignIn)?.let { status ->
								Spacer(Modifier.height(8.dp))
								Text(
									text = stringResource(status.line, *status.args.toTypedArray()),
									color = MaterialTheme.colorScheme.error,
									modifier = Modifier.padding(horizontal = 16.dp)
								)
							}
						}

						HubStepLayout.Older -> Text(
							text = stringResource(Res.string.hub_step_older),
							modifier = Modifier.padding(horizontal = 16.dp)
						)

						HubStepLayout.NotFound -> Unit

						HubStepLayout.Typing -> {
							Spacer(Modifier.height(8.dp))
							val error = (state as? HubStepState.Typing)?.error
							OutlinedTextField(
								modifier = Modifier
									.heightIn(min = 60.dp)
									.padding(horizontal = 16.dp)
									.fillMaxWidth()
									.focusRequester(addressFocus),
								state = addressState,
								isError = error != null,
								label = { Text(stringResource(Res.string.option_server_address)) },
								supportingText = error?.let { { Text(it) } },
								lineLimits = TextFieldLineLimits.SingleLine,
								enabled = !checking,
								keyboardOptions = KeyboardOptions(
									autoCorrectEnabled = false,
									keyboardType = KeyboardType.Uri,
									imeAction = ImeAction.Go,
									showKeyboardOnFocus = true
								),
								onKeyboardAction = KeyboardActionHandler { step.submit(addressState.text.toString()) }
							)
							LaunchedEffect(Unit) { addressFocus.requestFocus() }
						}
					}
					Spacer(Modifier.weight(2.25f))
				}
			}

			Column(
				modifier = Modifier
					.widthIn(max = 600.dp)
					.padding(horizontal = 16.dp)
					.padding(bottom = 8.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp)
			) {
				val main: Pair<StringResource, () -> Unit>? = when (layout) {
					HubStepLayout.Finding -> null
					HubStepLayout.Found -> Res.string.action_sign_in to { step.signIn() }
					HubStepLayout.NotFound -> Res.string.hub_step_type_its_address to { step.typeAddress() }
					HubStepLayout.Typing -> Res.string.action_continue to { step.submit(addressState.text.toString()) }
					HubStepLayout.Older -> Res.string.action_continue to { step.skip() }
				}
				main?.let { (label, onClick) ->
					Button(
						modifier = Modifier.fillMaxWidth(),
						onClick = onClick,
						enabled = state !is HubStepState.SigningIn && state !is HubStepState.Done && !checking &&
							(layout != HubStepLayout.Typing || addressState.text.isNotBlank()),
						shape = ContinuousCapsule
					) {
						Text(text = stringResource(label), fontFamily = defaultFont(100))
					}
				}
				// Skip, from the first frame: an older server's Continue is already the way on.
				if (layout != HubStepLayout.Older) {
					FilledTonalButton(
						modifier = Modifier.fillMaxWidth(),
						onClick = { step.skip() },
						shape = ContinuousCapsule
					) {
						Text(text = stringResource(Res.string.action_skip), fontFamily = defaultFont(100))
					}
				}
			}
		}
	}
}
