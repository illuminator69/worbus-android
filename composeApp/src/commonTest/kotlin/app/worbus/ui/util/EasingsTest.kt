package app.worbus.ui.util

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Motion lane review L8 (2026-11-14): Material's emphasized curve without an android.graphics.Path. It was a
 * PathEasing over two cubic segments, built when Material3Transitions.kt's class loaded, which no host test could
 * reach. It is now the accelerate and decelerate curves in the two segments' boxes; this holds that to the path.
 */
class EasingsTest {
	private data class P(val x: Double, val y: Double)

	private fun cubic(a: P, b: P, c: P, d: P, t: Double): P {
		val u = 1 - t
		fun f(p0: Double, p1: Double, p2: Double, p3: Double) = u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3
		return P(f(a.x, b.x, c.x, d.x), f(a.y, b.y, c.y, d.y))
	}

	/** y at [x] on the path the curve was: moveTo(0,0), cubicTo(.05,0, .133333,.06, .166666,.4), cubicTo(.208333,.82, .25,1, 1,1). */
	private fun pathY(x: Double): Double {
		val (a, b, c, d) = if (x <= 0.166666) listOf(P(0.0, 0.0), P(0.05, 0.0), P(0.133333, 0.06), P(0.166666, 0.4))
		else listOf(P(0.166666, 0.4), P(0.208333, 0.82), P(0.25, 1.0), P(1.0, 1.0))
		var lo = 0.0
		var hi = 1.0
		repeat(60) {
			val mid = (lo + hi) / 2
			if (cubic(a, b, c, d, mid).x < x) lo = mid else hi = mid
		}
		return cubic(a, b, c, d, (lo + hi) / 2).y
	}

	@Test
	fun theEmphasizedCurveIsThePathItWas() {
		var worst = 0.0
		for (i in 0..1000) {
			val x = i / 1000.0
			worst = maxOf(worst, abs(EmphasizedEasing.transform(x.toFloat()) - pathY(x)))
		}
		assertTrue(worst < 1e-3, "off the path by $worst")
		assertEquals(0f, EmphasizedEasing.transform(0f))
		assertEquals(1f, EmphasizedEasing.transform(1f), 1e-6f)
		assertEquals(0.4f, EmphasizedEasing.transform(0.166666f), 1e-4f)
	}
}
