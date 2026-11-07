package app.worbus.domain.models.settings

import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.option_bottom_bar_visibility_mode_all_screens
import worbus.composeapp.generated.resources.option_bottom_bar_visibility_mode_default
import org.jetbrains.compose.resources.StringResource

enum class BottomBarVisibilityMode(val displayName: StringResource) {
	Default(Res.string.option_bottom_bar_visibility_mode_default),
	AllScreens(Res.string.option_bottom_bar_visibility_mode_all_screens)
}
