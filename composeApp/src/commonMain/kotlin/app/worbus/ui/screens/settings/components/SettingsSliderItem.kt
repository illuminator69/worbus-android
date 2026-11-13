package app.worbus.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.worbus.ui.components.common.SegmentedListItem

@Composable
fun SettingsSliderItem(
	value: Float,
	valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
	onValueChange: (Float) -> Unit,
	steps: Int = 0,
	enabled: Boolean = true,
	shapes: ListItemShapes,
	contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
	trailingContent: @Composable () -> Unit,
	/**
	 * I-075 review L5: what a screen reader says for the slider's value ("1×"). Without it Material's slider
	 * says its position as a share of the range ("33 percent" for 1× on 0.5× to 2×).
	 */
	stateDescription: String? = null,
	/** Review L5: words under the slider's two ends ("Slower", "Faster"); both or neither. */
	startLabel: String? = null,
	endLabel: String? = null,
	content: @Composable () -> Unit
) {
	SegmentedListItem(
		onClick = {},
		enabled = enabled,
		shapes = shapes,
		contentPadding = contentPadding,
		content = content,
		supportingContent = {
			Column {
				Slider(
					value = value,
					valueRange = valueRange,
					onValueChange = onValueChange,
					steps = steps,
					modifier = if (stateDescription != null) Modifier.semantics { this.stateDescription = stateDescription }
					else Modifier
				)
				if (startLabel != null && endLabel != null) {
					Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
						Text(startLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
						Text(endLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
					}
				}
			}
		},
		trailingContent = {
			CompositionLocalProvider(
				LocalTextStyle provides TextStyle(
					fontFamily = FontFamily.Monospace,
					fontWeight = FontWeight(400),
					fontSize = 13.sp
				)
			) {
				trailingContent()
			}
		}
	)
}
