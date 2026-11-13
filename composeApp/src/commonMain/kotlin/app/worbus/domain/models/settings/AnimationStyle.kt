package app.worbus.domain.models.settings

import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.option_animation_style_expressive
import worbus.composeapp.generated.resources.option_animation_style_quick
import worbus.composeapp.generated.resources.option_animation_style_standard
import org.jetbrains.compose.resources.StringResource

// Stored by ordinal (BasePreferenceManager.getEnum): append only, never reorder. Expressive is the
// default (PreferenceManager) and what an unreadable ordinal reads as.
// I-075 (the owner, 2026-11-13): Standard shares Expressive's page, tab, cover and sheet motion
// (PageMotion.kt) and is kept for what only it has: Material's standard motion scheme (Theme.kt), the
// calmer, quicker component springs every fold, chip, mini-player and list motion reads.
enum class AnimationStyle(val displayName: StringResource) {
	Expressive(Res.string.option_animation_style_expressive),
	Standard(Res.string.option_animation_style_standard),
	// navi-connect I-042: shorter, lower-travel page, cover-flight and sheet motion (PageMotion.kt).
	Quick(Res.string.option_animation_style_quick)
}
