package app.worbus.domain.models.settings

import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.option_marquee_fast
import worbus.composeapp.generated.resources.option_marquee_medium
import worbus.composeapp.generated.resources.option_marquee_off
import worbus.composeapp.generated.resources.option_marquee_slow
import worbus.composeapp.generated.resources.option_shape_circle
import worbus.composeapp.generated.resources.option_shape_curved
import worbus.composeapp.generated.resources.option_shape_soft
import worbus.composeapp.generated.resources.option_shape_square
import worbus.composeapp.generated.resources.option_app_icon_default
import worbus.composeapp.generated.resources.option_app_icon_inverted
import org.jetbrains.compose.resources.StringResource

/*
 * What the settings print for the choices that used to print their Kotlin names ("Soft", "Disabled",
 * "Inverted" were only right by luck). The `when`s are exhaustive: a new entry cannot compile without a label.
 */

val CoverArtShape.label: StringResource
	get() = when (this) {
		CoverArtShape.Square -> Res.string.option_shape_square
		CoverArtShape.Soft -> Res.string.option_shape_soft
		CoverArtShape.Curved -> Res.string.option_shape_curved
		CoverArtShape.Circle -> Res.string.option_shape_circle
	}

val MarqueeSpeed.label: StringResource
	get() = when (this) {
		MarqueeSpeed.Disabled -> Res.string.option_marquee_off
		MarqueeSpeed.Slow -> Res.string.option_marquee_slow
		MarqueeSpeed.Medium -> Res.string.option_marquee_medium
		MarqueeSpeed.Fast -> Res.string.option_marquee_fast
	}

val AppIconVariant.label: StringResource
	get() = when (this) {
		AppIconVariant.Default -> Res.string.option_app_icon_default
		AppIconVariant.Inverted -> Res.string.option_app_icon_inverted
	}
