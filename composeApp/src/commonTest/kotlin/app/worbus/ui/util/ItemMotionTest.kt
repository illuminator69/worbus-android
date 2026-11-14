package app.worbus.ui.util

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.ui.unit.IntOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/*
 * Follow-up 2026-11-14 (the owner: list item placement follows the animation-speed slider): the lazy lists' item
 * motion is Compose's `animateItem` defaults at the speed, scaled as the motion scheme's springs are.
 */
class ItemMotionTest {
	@Test
	fun oneTimesIsComposesDefaults() {
		val m = itemMotionOf(1f)
		assertSame(m, itemMotionOf(1f))
		val p = assertIs<SpringSpec<IntOffset>>(m.placement)
		assertEquals(Spring.StiffnessMediumLow, p.stiffness)
		assertEquals(Spring.DampingRatioNoBouncy, p.dampingRatio)
		assertEquals(IntOffset(1, 1), p.visibilityThreshold)
		assertEquals(Spring.StiffnessMediumLow, assertIs<SpringSpec<Float>>(m.fadeIn).stiffness)
		assertEquals(Spring.StiffnessMediumLow, assertIs<SpringSpec<Float>>(m.fadeOut).stiffness)
	}

	@Test
	fun placementAndFadesFollowTheSpeed() {
		for ((speed, factor) in listOf(0.5f to 0.25f, 2f to 4f, 1.5f to 2.25f)) {
			val m = itemMotionOf(speed)
			val p = assertIs<SpringSpec<IntOffset>>(m.placement)
			assertEquals(Spring.StiffnessMediumLow * factor, p.stiffness, "placement at $speed")
			assertEquals(Spring.DampingRatioNoBouncy, p.dampingRatio, "same shape at $speed")
			assertEquals(IntOffset(1, 1), p.visibilityThreshold)
			assertEquals(Spring.StiffnessMediumLow * factor, assertIs<SpringSpec<Float>>(m.fadeIn).stiffness)
			assertEquals(Spring.StiffnessMediumLow * factor, assertIs<SpringSpec<Float>>(m.fadeOut).stiffness)
		}
		// Off the slider's stops it is clamped, as every other speed reader.
		assertEquals(
			assertIs<SpringSpec<IntOffset>>(itemMotionOf(2f).placement).stiffness,
			assertIs<SpringSpec<IntOffset>>(itemMotionOf(9f).placement).stiffness
		)
	}
}
