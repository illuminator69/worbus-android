package app.worbus.ui.util

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/*
 * I-075 review L4 (second re-walk): the animation speed reaches the fixed tweens (tile arrival, washes, cover
 * fades) and the motion scheme's springs, so a fold follows the slider the same way under every preset.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class MotionSpeedTest {

	@Test
	fun aFixedTimeIsDividedByTheSpeed() {
		assertEquals(ARRIVAL_MS, msAtSpeed(ARRIVAL_MS, 1f))
		assertEquals(900, msAtSpeed(WASH_MS, 0.5f))
		assertEquals(225, msAtSpeed(WASH_MS, 2f))
		assertEquals(140, msAtSpeed(COVER_FADE_MS, 2f))
		assertEquals(560, msAtSpeed(COVER_FADE_MS, 0.5f))
		assertEquals(0, msAtSpeed(0, 2f))
		assertEquals(msAtSpeed(WASH_MS, 2f), msAtSpeed(WASH_MS, 7f), "clamped to the slider's stops")
	}

	@Test
	fun aSpringKeepsItsShapeAndTakesOneOverTheSpeedOfTheTime() {
		val base = spring<Float>(dampingRatio = 0.6f, stiffness = 380f, visibilityThreshold = 0.01f)
		val fast = assertIs<SpringSpec<Float>>(base.atSpeed(2f))
		assertEquals(0.6f, fast.dampingRatio)
		assertEquals(380f * 4, fast.stiffness)
		assertEquals(0.01f, fast.visibilityThreshold)
		val slow = assertIs<SpringSpec<Float>>(base.atSpeed(0.5f))
		assertEquals(380f / 4, slow.stiffness)
		assertSame(base, base.atSpeed(1f))
	}

	@Test
	fun aTweenScalesItsTimeAndDelayNotItsCurve() {
		val t = tween<Float>(durationMillis = 300, delayMillis = 100, easing = FoldEasing)
		val fast = assertIs<TweenSpec<Float>>(t.atSpeed(2f))
		assertEquals(150, fast.durationMillis)
		assertEquals(50, fast.delay)
		assertSame(FoldEasing, fast.easing)
	}

	@Test
	fun theMotionSchemesSpringsFollowTheSpeedUnderEveryPreset() {
		for (scheme in listOf(MotionScheme.expressive(), MotionScheme.standard())) {
			assertSame(scheme, scheme.atSpeed(1f))
			val base = assertIs<SpringSpec<Float>>(scheme.defaultSpatialSpec<Float>())
			val fast = assertIs<SpringSpec<Float>>(scheme.atSpeed(2f).defaultSpatialSpec<Float>())
			assertEquals(base.dampingRatio, fast.dampingRatio)
			assertEquals(base.stiffness * 4, fast.stiffness)
			val effects = assertIs<SpringSpec<Float>>(scheme.defaultEffectsSpec<Float>())
			assertEquals(effects.stiffness / 4, assertIs<SpringSpec<Float>>(scheme.atSpeed(0.5f).defaultEffectsSpec<Float>()).stiffness)
		}
		// Quick's fold is a tween of its own, halved at 2x as the springs are.
		assertEquals(pageMotionOf(app.worbus.domain.models.settings.AnimationStyle.Quick).foldMs!! / 2,
			pageMotionOf(app.worbus.domain.models.settings.AnimationStyle.Quick, 2f).foldMs)
	}
}
