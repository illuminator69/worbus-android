package app.worbus.ui.util

import androidx.compose.animation.core.CubicBezierEasing

/*
 * Material's emphasized accelerate and decelerate curves. They lived beside `EmphasizedEasing` in
 * Material3Transitions.kt, whose file class builds an `android.graphics.Path` when it loads; a host test of
 * anything that read these two (`swapItemMotionOf`, motion lane 2026-11-14) then failed on the Path, which the JVM
 * has no body for. Here they load on their own.
 */
val EmphasizedAccelerateEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
val EmphasizedDecelerateEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
