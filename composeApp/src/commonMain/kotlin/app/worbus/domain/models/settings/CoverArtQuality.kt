package app.worbus.domain.models.settings

import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.option_quality_high
import worbus.composeapp.generated.resources.option_quality_low
import worbus.composeapp.generated.resources.option_quality_medium
import org.jetbrains.compose.resources.StringResource

enum class CoverArtQuality(
	val displayName: StringResource,
	val value: Int
) {
	Low(Res.string.option_quality_low, 512),
	Medium(Res.string.option_quality_medium, 1024),
	High(Res.string.option_quality_high, 4096)
}
