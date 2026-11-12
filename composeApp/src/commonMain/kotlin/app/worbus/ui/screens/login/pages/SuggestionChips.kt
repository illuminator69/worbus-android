package app.worbus.ui.screens.login.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.notice_login_suggestion
import org.jetbrains.compose.resources.stringResource
import app.worbus.domain.manager.InstanceUrlResult
import app.worbus.domain.manager.normalizeInstanceUrl

@Composable
fun LoginScreenSuggestionChips(
	instanceState: TextFieldState,
	showSuggestions: Boolean = instanceState.text.isNotEmpty() &&
		!instanceState.text.startsWith("http://") &&
		!instanceState.text.startsWith("https://") &&
		(instanceState.text.contains(".") || instanceState.text.contains(":"))
) {
	AnimatedVisibility(
		modifier = Modifier.fillMaxWidth(),
		visible = showSuggestions,
		enter = expandVertically() + fadeIn(),
		exit = shrinkVertically() + fadeOut()
	) {
		Column(
			modifier = Modifier.fillMaxWidth()
		) {
			Spacer(Modifier.height(4.dp))
			Text(
				text = stringResource(Res.string.notice_login_suggestion),
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(horizontal = 16.dp)
			)
			Spacer(Modifier.height(4.dp))
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.horizontalScroll(rememberScrollState()),
				horizontalArrangement = Arrangement.spacedBy(8.dp)
			) {
				val url = instanceState.text.toString()
				Spacer(Modifier.width(8.dp))
				for (suggestion in loginSuggestions(url)) {
					SuggestionChip(
						onClick = {
							instanceState.edit {
								replace(0, length, suggestion)
							}
						},
						label = {
							Text(
								text = suggestion,
								style = MaterialTheme.typography.labelSmall,
								maxLines = 1
							)
						}
					)
				}
				Spacer(Modifier.width(8.dp))
			}
		}
	}
}

/**
 * The "Did you mean…" addresses for what is typed, first offered first (B-307): the scheme the
 * login's own rule gives it ([normalizeInstanceUrl]: http for a loopback, private or single-label
 * host, https otherwise) first, the other second. One rule: the chips used to offer https first
 * for every address, a private one included.
 */
internal fun loginSuggestions(typed: String): List<String> {
	val bare = typed.trim().replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
	val first = (normalizeInstanceUrl(bare) as? InstanceUrlResult.Ok)?.url?.substringBefore("://") ?: "https"
	val second = if (first == "http") "https" else "http"
	return listOf("$first://$bare", "$second://$bare")
}

