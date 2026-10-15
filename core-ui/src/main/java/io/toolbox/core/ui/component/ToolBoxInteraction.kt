package io.toolbox.core.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

/**
 * Press feedback shared by every tappable card, button and row: the visual content springs
 * down to [scale] and picks up the iOS rgba(118,118,128,0.14) highlight in [shape] only while
 * pressed, then springs back with a little bounce. Rows inside a card keep the default
 * rectangle: the card's clip rounds it.
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
    val overlay = ToolBoxNeutralFill
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

/**
 * Theme-wide [androidx.compose.foundation.LocalIndication]: the flat iOS highlight,
 * rgba(118,118,128,0.14) over the pressed element while the finger is down, with no ripple.
 * It fills the clickable's bounds, so it takes the shape of whatever clip precedes the
 * clickable (a card, a capsule, a menu item) instead of drawing a rectangle outside it.
 */
object ToolBoxPressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        ToolBoxPressIndicationNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = -0x2b4f1a
}

private class ToolBoxPressIndicationNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode {
    private val progress = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            val presses = mutableListOf<PressInteraction.Press>()
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses += interaction
                    is PressInteraction.Release -> presses -= interaction.press
                    is PressInteraction.Cancel -> presses -= interaction.press
                }
                val pressed = presses.isNotEmpty()
                launch {
                    progress.animateTo(
                        if (pressed) 1f else 0f,
                        tween(durationMillis = if (pressed) 60 else 220, easing = LinearEasing),
                    )
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = ToolBoxMotion.PressDimAlpha * progress.value.coerceIn(0f, 1f)
        if (alpha > 0.001f) drawRect(ToolBoxNeutralFill.copy(alpha = alpha))
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

/**
 * iOS 26 interactive glass press for a custom glass control that is not a [io.toolbox.core.ui.liquid.LiquidButton]:
 * it grows about 8% on a bouncy spring and brightens under the finger, the opposite of
 * [toolBoxPressFeedback]'s shrink and grey. The hit target is not resized.
 */
@Composable
fun Modifier.toolBoxGlassPress(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    shape: Shape = androidx.compose.foundation.shape.CircleShape,
    /** Null: a white additive glow (over glass). A colour: that wash at full press, for bare controls on a light page. */
    glow: androidx.compose.ui.graphics.Color? = null,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val progress = animateFloatAsState(
        targetValue = if (enabled && pressed) 1f else 0f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 300f),
        label = "toolbox glass press",
    )
    return graphicsLayer {
        // About 8%, but never more than GlassPressMaxGrow wider in all: a full-width button or card
        // growing 8% would reach into the panel's side margins.
        val grow = minOf(0.08f, GlassPressMaxGrow.toPx() / size.width.coerceAtLeast(1f))
        val s = 1f + grow * progress.value
        scaleX = s
        scaleY = s
    }.drawWithContent {
        drawContent()
        val amount = progress.value.coerceIn(0f, 1f)
        if (amount > 0.001f) {
            if (glow == null) {
                drawOutline(
                    shape.createOutline(size, layoutDirection, this),
                    androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f * amount),
                    blendMode = androidx.compose.ui.graphics.BlendMode.Plus,
                )
            } else {
                drawOutline(shape.createOutline(size, layoutDirection, this), glow.copy(alpha = glow.alpha * amount))
            }
        }
    }
}

/** The most a pressed glass control grows in width, so wide controls stay inside their margins. */
private val GlassPressMaxGrow = 12.dp

/** Glow of a bare (borderless) control under the finger: an accent wash on light pages, white on dark. */
@Composable
fun toolBoxBareGlow(): androidx.compose.ui.graphics.Color {
    val colors = ToolBoxThemeTokens.colors
    return if (colors.background.luminance() < 0.5f) {
        androidx.compose.ui.graphics.Color.White.copy(alpha = 0.14f)
    } else {
        colors.primary.copy(alpha = 0.10f)
    }
}
