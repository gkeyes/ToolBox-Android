package io.toolbox.core.ui.component

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Shared motion language for ToolBox.
 *
 * Everything that follows a finger or reacts to a state change uses a spring, so retargeting
 * mid-flight keeps velocity instead of restarting a curve. Springs also collapse to their end
 * value when the system "remove animations" setting sets the duration scale to zero, so no
 * screen depends on an animation actually playing.
 *
 * - [Spatial]: position / size / layout changes (0.85 damping, medium-low stiffness).
 * - [Page]: full-width page pushes; critically damped so an entering page never overshoots
 *   past its edge and reveals the page underneath.
 * - [pressSpec]: fast, firm press-in; slightly softer, springy release.
 * - [Effects]: opacity / colour; no bounce.
 */
object ToolBoxMotion {
    /** Kept for callers that still need a time-based curve (for example drawing a progress bar). */
    const val PageDurationMillis = 300

    /** Stagger between list items on first appearance; only the first [StaggerMaxItems] animate. */
    const val StaggerStepMillis = 26
    const val StaggerMaxItems = 8
    const val EntranceDurationMillis = 360

    /** Vertical travel of a list item as it fades in on first appearance. */
    val EntranceOffset = 14.dp

    /** Press feedback: card/button scale target and the brightness drop applied on press. */
    const val PressScale = 0.965f
    const val RowPressScale = 0.985f
    const val PressDimAlpha = 0.06f

    val EmphasizedEasing = CubicBezierEasing(0.20f, 0.0f, 0.0f, 1.0f)
    val EmphasizedDecelerateEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val StandardEasing = CubicBezierEasing(0.20f, 0.0f, 0.2f, 1.0f)

    private const val SpatialDamping = 0.85f
    private const val PressInStiffness = 2_200f

    fun <T> spatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = SpatialDamping, stiffness = Spring.StiffnessMediumLow)

    fun <T> effects(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    /** Page pushes and pops. [distance] is accepted for pager jumps across several tabs. */
    fun pageSpec(distance: Int = 1): FiniteAnimationSpec<Float> =
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = if (distance.coerceAtLeast(1) > 1) 300f else 420f,
        )

    fun pressSpec(pressed: Boolean): AnimationSpec<Float> =
        if (pressed) {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = PressInStiffness)
        } else {
            spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
        }

    /** Dialogs and bottom sheets entering from below. */
    val SheetEnter: FiniteAnimationSpec<Float> =
        spring(dampingRatio = SpatialDamping, stiffness = Spring.StiffnessMediumLow)

    val ScrimFade: TweenSpec<Float> = tween(durationMillis = 220, easing = StandardEasing)

    /** Expand / collapse of inline content (disclosure rows, error details, banners). */
    val ExpandIn: EnterTransition =
        fadeIn(spring(stiffness = Spring.StiffnessMedium)) +
            expandVertically(spring(dampingRatio = SpatialDamping, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize.VisibilityThreshold))

    val CollapseOut: ExitTransition =
        fadeOut(spring(stiffness = Spring.StiffnessMedium)) +
            shrinkVertically(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize.VisibilityThreshold))

    /** Placement spring for LazyList `animateItem()`. */
    val ItemPlacement: FiniteAnimationSpec<IntOffset> =
        spring(dampingRatio = SpatialDamping, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)
}
