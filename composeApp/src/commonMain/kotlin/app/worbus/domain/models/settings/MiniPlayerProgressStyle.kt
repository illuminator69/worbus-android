package app.worbus.domain.models.settings

import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.option_mini_player_progress_style_hidden
import worbus.composeapp.generated.resources.option_mini_player_progress_style_seekable
import worbus.composeapp.generated.resources.option_mini_player_progress_style_visible
import org.jetbrains.compose.resources.StringResource

enum class MiniPlayerProgressStyle(val displayName: StringResource) {
	Hidden(Res.string.option_mini_player_progress_style_hidden),
	Visible(Res.string.option_mini_player_progress_style_visible),
	Seekable(Res.string.option_mini_player_progress_style_seekable)
}
