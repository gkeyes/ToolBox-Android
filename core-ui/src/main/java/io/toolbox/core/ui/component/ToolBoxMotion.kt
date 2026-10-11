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
    /** Kept for callers that still need a time-based curve. */
    const val PageDurationMillis = 300

    /** First-appearance stagger: first 8 blocks, 26ms apart, rising 16dp with a spatial spring. */
    const val StaggerStepMillis = 26
    const val StaggerMaxItems = 8
    const val EntranceDurationMillis = 620
    val EntranceOffset = 16.dp

    /** Shared X-axis page motion: 30dp travel plus fade. */
    val PageShift = 30.dp

    /** Press feedback: cards/buttons 0.965, full-width rows 0.985; 6% state layer. */
    const val PressScale = 0.965f
    const val RowPressScale = 0.985f
    const val PressDimAlpha = 0.06f

    /** Sheet / dialog exit: 220ms accelerate curve (0.3, 0, 1, 1). */
    const val ExitDurationMillis = 220
    const val ScrimFadeMillis = 220
    val ExitEasing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    val EmphasizedEasing = CubicBezierEasing(0.20f, 0.0f, 0.0f, 1.0f)
    val EmphasizedDecelerateEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val StandardEasing = CubicBezierEasing(0.20f, 0.0f, 0.2f, 1.0f)

    const val SpatialDamping = 0.86f
    const val SpatialStiffness = 380f
    const val EffectsStiffness = 600f

    /** Position / size: spring(0.86, 380). */
    fun <T> spatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = SpatialDamping, stiffness = SpatialStiffness)

    /** Colour / opacity: spring(1.0, 600). */
    fun <T> effects(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 1f, stiffness = EffectsStiffness)

    /** Page push / pop: spring(1.0, 380). [distance] kept for multi-tab pager jumps. */
    fun pageSpec(distance: Int = 1): FiniteAnimationSpec<Float> =
        spring(dampingRatio = 1f, stiffness = if (distance.coerceAtLeast(1) > 1) 300f else 380f)

    /** Press in spring(1.0, 1500); release spring(0.62, 420) with a slight rebound. */
    fun pressSpec(pressed: Boolean): AnimationSpec<Float> =
        if (pressed) spring(dampingRatio = 1f, stiffness = 1500f) else spring(dampingRatio = 0.62f, stiffness = 420f)

    /** Bottom sheet entering from below: spring(0.9, 380). */
    val SheetEnter: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 380f)

    /** Centred dialog: scale 0.94 -> 1 with fade, spring(0.9, 500). */
    val DialogEnter: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 500f)

    val ScrimFade: TweenSpec<Float> = tween(durationMillis = ScrimFadeMillis, easing = StandardEasing)

    /** Expand / collapse of inline content: expandVertically + fade on the spatial spring. */
    val ExpandIn: EnterTransition =
        fadeIn(spring(dampingRatio = 1f, stiffness = EffectsStiffness)) +
            expandVertically(spring(dampingRatio = SpatialDamping, stiffness = SpatialStiffness, visibilityThreshold = IntSize.VisibilityThreshold))

    val CollapseOut: ExitTransition =
        fadeOut(spring(dampingRatio = 1f, stiffness = EffectsStiffness)) +
            shrinkVertically(spring(dampingRatio = SpatialDamping, stiffness = SpatialStiffness, visibilityThreshold = IntSize.VisibilityThreshold))

    /** Placement spring for LazyList `animateItem()`. */
    val ItemPlacement: FiniteAnimationSpec<IntOffset> =
        spring(dampingRatio = SpatialDamping, stiffness = SpatialStiffness, visibilityThreshold = IntOffset.VisibilityThreshold)

    /**
     * Fade-through split for page pushes: the covered page is gone by this point of the
     * transition and the incoming page only starts to appear after it, so two pages (and two
     * top bars) are never visible on top of each other.
     */
    const val FadeThroughSplit = 0.35f

    /** Alpha of an incoming page whose own visibility is [visibility] (0 hidden .. 1 shown). */
    fun fadeThroughIncomingAlpha(visibility: Float): Float =
        ((visibility - FadeThroughSplit) / (1f - FadeThroughSplit)).coerceIn(0f, 1f)

    /** Alpha of a page covered by a page above it whose visibility is [coverage]. */
    fun fadeThroughOutgoingAlpha(coverage: Float): Float =
        (1f - coverage / FadeThroughSplit).coerceIn(0f, 1f)

    /**
     * Alpha of a page with push progress [motion] (1 hidden .. 0 shown) under a page above it
     * whose visibility is [coverage]. A settled, uncovered page is unconditionally opaque, so
     * no transition arithmetic can ever leave the destination invisible.
     */
    fun pageLayerAlpha(motion: Float, coverage: Float): Float {
        val m = motion.coerceIn(0f, 1f)
        val c = coverage.coerceIn(0f, 1f)
        if (m <= SettledEpsilon && c <= SettledEpsilon) return 1f
        return fadeThroughIncomingAlpha(1f - m) * fadeThroughOutgoingAlpha(c)
    }

    private const val SettledEpsilon = 0.001f

    /**
     * Closed-form step response of a unit-mass spring (0 -> 1) at [timeMillis]; used where many
     * items share one clock and each item evaluates its own delayed spring in the draw phase.
     */
    fun springProgress(timeMillis: Float, dampingRatio: Float, stiffness: Float): Float {
        if (timeMillis <= 0f) return 0f
        val t = timeMillis / 1000f
        val omega = kotlin.math.sqrt(stiffness)
        return if (dampingRatio >= 1f) {
            1f - kotlin.math.exp(-omega * t) * (1f + omega * t)
        } else {
            val damped = omega * kotlin.math.sqrt(1f - dampingRatio * dampingRatio)
            val decay = kotlin.math.exp(-dampingRatio * omega * t)
            1f - decay * (kotlin.math.cos(damped * t) +
                dampingRatio / kotlin.math.sqrt(1f - dampingRatio * dampingRatio) * kotlin.math.sin(damped * t))
        }
    }
}
