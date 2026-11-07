package app.worbus.ui.screens.settings.components

import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.runtime.Composable
import app.worbus.icons.Icons
import app.worbus.icons.outlined.ChevronForward
import app.worbus.ui.components.common.SegmentedListItem

@Composable
fun SettingsNavItem(
	onClick: () -> Unit,
	enabled: Boolean = true,
	shapes: ListItemShapes,
	leadingContent: @Composable (() -> Unit)? = null,
	supportingContent: @Composable (() -> Unit)? = null,
	content: @Composable () -> Unit
) {
	SegmentedListItem(
		onClick = onClick,
		enabled = enabled,
		shapes = shapes,
		supportingContent = supportingContent,
		leadingContent = leadingContent,
		trailingContent = { Icon(Icons.Outlined.ChevronForward, null) },
		content = content
	)
}
