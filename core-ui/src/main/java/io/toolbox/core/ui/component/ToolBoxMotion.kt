package io.toolbox.core.ui.component

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween

/**
 * Shared motion language for ToolBox.
 *
 * Large navigation uses a soft emphasized curve so content feels spatial rather than abrupt.
 * Small controls respond quickly on press and relax slightly more slowly on release.
 */
object ToolBoxMotion {
    const val PageDurationMillis = 300
    const val PressInDurationMillis = 90
    const val PressOutDurationMillis = 170

    val EmphasizedEasing = CubicBezierEasing(0.20f, 0.0f, 0.0f, 1.0f)
    val StandardEasing = CubicBezierEasing(0.20f, 0.0f, 0.2f, 1.0f)

    fun pageSpec(distance: Int = 1): TweenSpec<Float> =
        tween(
            durationMillis = (PageDurationMillis + (distance.coerceAtLeast(1) - 1) * 70)
                .coerceAtMost(440),
            easing = EmphasizedEasing,
        )

    fun pressSpec(pressed: Boolean): TweenSpec<Float> =
        tween(
            durationMillis = if (pressed) PressInDurationMillis else PressOutDurationMillis,
            easing = StandardEasing,
        )
}
