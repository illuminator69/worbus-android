package app.worbus.domain.models.settings

import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.option_now_playing_slider_style_flat
import worbus.composeapp.generated.resources.option_now_playing_slider_style_slim
import worbus.composeapp.generated.resources.option_now_playing_slider_style_squiggly
import worbus.composeapp.generated.resources.option_now_playing_slider_style_yoyo
import org.jetbrains.compose.resources.StringResource

enum class NowPlayingSliderStyle(val displayName: StringResource) {
	Flat(Res.string.option_now_playing_slider_style_flat),
	Squiggly(Res.string.option_now_playing_slider_style_squiggly),
	Slim(Res.string.option_now_playing_slider_style_slim),
	Yoyo(Res.string.option_now_playing_slider_style_yoyo)
}
