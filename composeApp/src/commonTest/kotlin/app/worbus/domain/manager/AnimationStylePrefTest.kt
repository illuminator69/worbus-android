package app.worbus.domain.manager

import app.worbus.domain.models.settings.AnimationStyle
import app.worbus.ui.util.pageMotion
import app.worbus.ui.util.pageMotionOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * I-075 (the owner, 2026-11-13 evening): "expressive should be the default animation preset, remove
 * standard if it is redundant". Expressive is the default (a new install, and any stored value this build
 * cannot read); Standard is kept, since it is the one style on Material's standard motion scheme (`Theme.kt`):
 * its component springs (folds, chips, the mini player, lists) differ from Expressive's and Quick's. The
 * stored ordinals (0 Expressive, 1 Standard, 2 Quick) are read as they were; the speed starts at 1×.
 */
class AnimationStylePrefTest {
	private fun prefs(store: MemorySettings = MemorySettings()) = PreferenceManager(store, testSecrets(store))

	@Test
	fun aNewInstallIsExpressiveAt1x() {
		val p = prefs()
		assertEquals(AnimationStyle.Expressive, p.animationStyle)
		assertEquals(1f, p.motionSpeed)
		assertEquals(pageMotionOf(AnimationStyle.Expressive), p.pageMotion())
	}

	@Test
	fun storedStylesReadAsTheyWereAndAnUnknownOneIsExpressive() {
		val expected = listOf(AnimationStyle.Expressive, AnimationStyle.Standard, AnimationStyle.Quick)
		for ((ordinal, style) in expected.withIndex()) {
			val p = prefs(MemorySettings().apply { putInt("animationStyle", ordinal) })
			assertEquals(style, p.animationStyle, "ordinal $ordinal")
		}
		for (ordinal in listOf(3, 9, -1)) {
			val p = prefs(MemorySettings().apply { putInt("animationStyle", ordinal) })
			assertEquals(AnimationStyle.Expressive, p.animationStyle, "ordinal $ordinal: never a crash")
		}
	}

	@Test
	fun theSpeedIsPersistedAndRead() {
		val store = MemorySettings()
		prefs(store).apply {
			animationStyle = AnimationStyle.Quick
			motionSpeed = 1.5f
		}
		val again = prefs(store)
		assertEquals(1.5f, again.motionSpeed)
		assertEquals(pageMotionOf(AnimationStyle.Quick, 1.5f), again.pageMotion())
		assertEquals(200, again.pageMotion().slideMs)
	}
}
