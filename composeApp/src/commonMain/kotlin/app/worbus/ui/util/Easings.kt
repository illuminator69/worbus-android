package app.worbus.ui.util

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/*
 * Material's emphasized accelerate and decelerate curves. They lived beside `EmphasizedEasing` in
 * Material3Transitions.kt, whose file class builds an `android.graphics.Path` when it loads; a host test of
 * anything that read these two (`swapItemMotionOf`, motion lane 2026-11-14) then failed on the Path, which the JVM
 * has no body for. Here they load on their own.
 */
val EmphasizedAccelerateEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
val EmphasizedDecelerateEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/*
 * Material's emphasized curve. It was a `PathEasing` over an `android.graphics.Path` of two cubic segments, built when
 * Material3Transitions.kt's file class loaded, so no host test could reach `Material3Transitions.tab`, `inPageAxis`
 * or `contentSwapTransform` (motion lane review L8, 2026-11-14). The two segments are exactly the two curves above,
 * scaled: (0, 0)-(1/6, 0.4) with controls (0.05, 0), (0.133333, 0.06) is [EmphasizedAccelerateEasing] in that box,
 * and (1/6, 0.4)-(1, 1) with controls (0.208333, 0.82), (0.25, 1) is [EmphasizedDecelerateEasing] in its box (an
 * affine map of a Bézier is the Bézier of the mapped controls). The same curve, no Path.
 */
private const val EMPHASIZED_SPLIT_X = 0.166666f
private const val EMPHASIZED_SPLIT_Y = 0.4f

val EmphasizedEasing: Easing = Easing { x ->
	if (x <= EMPHASIZED_SPLIT_X) EMPHASIZED_SPLIT_Y * EmphasizedAccelerateEasing.transform(x / EMPHASIZED_SPLIT_X)
	else EMPHASIZED_SPLIT_Y + (1f - EMPHASIZED_SPLIT_Y) *
		EmphasizedDecelerateEasing.transform((x - EMPHASIZED_SPLIT_X) / (1f - EMPHASIZED_SPLIT_X))
}
