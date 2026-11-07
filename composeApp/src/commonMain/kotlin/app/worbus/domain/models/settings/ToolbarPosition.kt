package app.worbus.domain.models.settings

import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.option_position_bottom
import worbus.composeapp.generated.resources.option_position_top
import org.jetbrains.compose.resources.StringResource

enum class ToolbarPosition(val displayName: StringResource) {
	Top(Res.string.option_position_top),
	Bottom(Res.string.option_position_bottom)
}
