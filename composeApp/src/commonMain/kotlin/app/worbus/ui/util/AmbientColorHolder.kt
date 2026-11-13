package app.worbus.ui.util

import androidx.compose.ui.graphics.Color

/**
 * App-scoped memory of the last resolved cover/photo ambient seed colour.
 *
 * A freshly-opened detail screen (album / artist) reads this as its INITIAL seed
 * so its ambient starts at the colour we're navigating FROM and eases to its own
 * (via the screen's `animateColorAsState`), instead of flashing the neutral
 * adaptive default while kmpalette extracts the new artwork's colour.
 *
 * Written by each detail screen whenever its resolved seed changes; read once on
 * the next screen's first composition. Main-thread only, so a plain var is fine.
 *
 * B-404: every washed page writes it too (`BrowsingAmbient`: the playing cover's wash, or null for the
 * plain surface), so what a cover page bridges from is the page it was opened FROM, not whichever cover
 * page happened to be open last. [lastDark] is that page's brightness, so the bridge is the same
 * gradient the outgoing page wore, not that colour at the app's brightness.
 */
class AmbientColorHolder {
	var last: Color? = null
	var lastDark: Boolean? = null

	/** What a page shows now, for the next page's first frame. */
	fun publish(seed: Color?, isDark: Boolean?) {
		last = seed
		lastDark = isDark.takeIf { seed != null }
	}
}
