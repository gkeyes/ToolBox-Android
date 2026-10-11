package io.toolbox.core.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

/**
 * Press feedback shared by every tappable card, button and row: the visual content springs
 * down to [scale] and picks up a faint state layer, then springs back with a little bounce.
 * Animation values are read only inside the draw/layer lambdas, never during composition.
 * The hit target itself is not resized.
 */
@Composable
fun Modifier.toolBoxPressFeedback(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    scale: Float = ToolBoxMotion.PressScale,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val progress = animateFloatAsState(
        targetValue = if (enabled && pressed) 1f else 0f,
        animationSpec = ToolBoxMotion.pressSpec(pressed),
        label = "toolbox press",
    )
    val overlay = ToolBoxThemeTokens.colors.textPrimary
    return graphicsLayer {
        val amount = progress.value
        val s = 1f - (1f - scale) * amount
        scaleX = s
        scaleY = s
    }.drawWithContent {
        drawContent()
        val alpha = ToolBoxMotion.PressDimAlpha * progress.value.coerceIn(0f, 1f)
        if (alpha > 0.001f) {
            drawOutline(shape.createOutline(size, layoutDirection, this), overlay.copy(alpha = alpha))
        }
    }
}

/** Light, semantic haptics for toggles and confirmations. */
@Stable
class ToolBoxHaptics internal constructor(private val perform: (HapticFeedbackType) -> Unit) {
    fun toggle(on: Boolean) = perform(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
    fun confirm() = perform(HapticFeedbackType.Confirm)
    fun tick() = perform(HapticFeedbackType.SegmentTick)
    fun longPress() = perform(HapticFeedbackType.LongPress)
    fun reject() = perform(HapticFeedbackType.Reject)
}

@Composable
fun rememberToolBoxHaptics(): ToolBoxHaptics {
    val haptic = LocalHapticFeedback.current
    return remember(haptic) { ToolBoxHaptics { haptic.performHapticFeedback(it) } }
}

/**
 * One clock per screen for the first-appearance stagger. Each block evaluates its own delayed
 * spatial spring (offset) and effects spring (alpha) from the shared clock in its layer block,
 * so nothing recomposes per frame, scrolling never replays it, and only the first
 * [ToolBoxMotion.StaggerMaxItems] blocks move. A screen plays it once per process
 * ([key]); with animations disabled the clock completes immediately.
 */
@Stable
class ToolBoxEntrance internal constructor(internal val clock: State<Float>)

private val playedEntrances = java.util.Collections.synchronizedSet(HashSet<String>())

@Composable
fun rememberToolBoxEntrance(key: String): ToolBoxEntrance {
    val total = (ToolBoxMotion.StaggerMaxItems * ToolBoxMotion.StaggerStepMillis +
        ToolBoxMotion.EntranceDurationMillis).toFloat()
    val clock = remember(key) { Animatable(if (key in playedEntrances) total else 0f) }
    LaunchedEffect(clock) {
        playedEntrances += key
        if (clock.value < total) {
            clock.animateTo(total, tween(total.toInt(), easing = LinearEasing))
        }
    }
    return remember(clock) { ToolBoxEntrance(clock.asState()) }
}

fun Modifier.toolBoxEntrance(entrance: ToolBoxEntrance, index: Int): Modifier {
    if (index < 0 || index >= ToolBoxMotion.StaggerMaxItems) return this
    return graphicsLayer {
        val local = entrance.clock.value - index * ToolBoxMotion.StaggerStepMillis
        if (local < ToolBoxMotion.EntranceDurationMillis) {
            val move = ToolBoxMotion.springProgress(local, ToolBoxMotion.SpatialDamping, ToolBoxMotion.SpatialStiffness)
            val fade = ToolBoxMotion.springProgress(local, 1f, ToolBoxMotion.EffectsStiffness)
            alpha = fade.coerceIn(0f, 1f)
            translationY = (1f - move) * ToolBoxMotion.EntranceOffset.toPx()
        }
    }
}
