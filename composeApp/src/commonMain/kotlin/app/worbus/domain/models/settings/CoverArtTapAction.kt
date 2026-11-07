package app.worbus.domain.models.settings

import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.option_cover_art_action_disabled
import worbus.composeapp.generated.resources.option_cover_art_action_show_lyrics
import org.jetbrains.compose.resources.StringResource

enum class CoverArtTapAction(val displayName: StringResource) {
	Disabled(Res.string.option_cover_art_action_disabled),
	ShowLyrics(Res.string.option_cover_art_action_show_lyrics)
}
