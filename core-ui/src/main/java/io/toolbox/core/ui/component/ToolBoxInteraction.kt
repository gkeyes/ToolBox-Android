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
import androidx.compose.runtime.saveable.rememberSaveable
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
}

@Composable
fun rememberToolBoxHaptics(): ToolBoxHaptics {
    val haptic = LocalHapticFeedback.current
    return remember(haptic) { ToolBoxHaptics { haptic.performHapticFeedback(it) } }
}

/**
 * One clock per screen for the first-appearance list stagger. Items read the shared clock in
 * their layer block, so scrolling an item back into view never replays the animation, and
 * only the first [ToolBoxMotion.StaggerMaxItems] items move at all. The played flag survives
 * configuration changes; with animations disabled the tween completes immediately.
 */
@Stable
class ToolBoxEntrance internal constructor(internal val clock: State<Float>)

@Composable
fun rememberToolBoxEntrance(): ToolBoxEntrance {
    val total = (ToolBoxMotion.StaggerMaxItems * ToolBoxMotion.StaggerStepMillis +
        ToolBoxMotion.EntranceDurationMillis).toFloat()
    var played by rememberSaveable { mutableStateOf(false) }
    val clock = remember { Animatable(if (played) total else 0f) }
    LaunchedEffect(clock) {
        if (clock.value < total) {
            clock.animateTo(total, tween(total.toInt(), easing = LinearEasing))
        }
        played = true
    }
    return remember(clock) { ToolBoxEntrance(clock.asState()) }
}

fun Modifier.toolBoxEntrance(entrance: ToolBoxEntrance, index: Int): Modifier {
    if (index < 0 || index >= ToolBoxMotion.StaggerMaxItems) return this
    return graphicsLayer {
        val local = ((entrance.clock.value - index * ToolBoxMotion.StaggerStepMillis) /
            ToolBoxMotion.EntranceDurationMillis).coerceIn(0f, 1f)
        if (local < 1f) {
            val eased = ToolBoxMotion.EmphasizedDecelerateEasing.transform(local)
            alpha = eased
            translationY = (1f - eased) * ToolBoxMotion.EntranceOffset.toPx()
        }
    }
}
