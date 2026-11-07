package app.worbus.domain.models.settings

import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.theme_mode_dark
import worbus.composeapp.generated.resources.theme_mode_light
import worbus.composeapp.generated.resources.theme_mode_system
import org.jetbrains.compose.resources.StringResource

enum class ThemeMode(val title: StringResource) {
	System(Res.string.theme_mode_system),
	Dark(Res.string.theme_mode_dark),
	Light(Res.string.theme_mode_light)
}
