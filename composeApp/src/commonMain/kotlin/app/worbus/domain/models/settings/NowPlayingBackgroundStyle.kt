package app.worbus.domain.models.settings

import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.option_now_playing_background_style_dynamic
import worbus.composeapp.generated.resources.option_now_playing_background_style_static
import org.jetbrains.compose.resources.StringResource

enum class NowPlayingBackgroundStyle(val displayName: StringResource) {
	Static(Res.string.option_now_playing_background_style_static),
	Dynamic(Res.string.option_now_playing_background_style_dynamic)
}
