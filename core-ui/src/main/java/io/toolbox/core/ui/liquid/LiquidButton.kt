/*
 * Vendored from Kyant0/AndroidLiquidGlass (https://github.com/Kyant0/AndroidLiquidGlass),
 * catalog app @ 65ab177, Copyright Kyant, licensed under the Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0). Package renamed; theme colours, padding and height exposed as parameters;
 * the rim highlight follows the finger.
 */
package io.toolbox.core.ui.liquid

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.shapes.Capsule
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

@Composable
fun LiquidButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    isInteractive: Boolean = true,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16f.dp),
    height: Dp = 48f.dp,
    blurRadius: Dp = 2f.dp,
    content: @Composable RowScope.() -> Unit
) {
    val animationScope = rememberCoroutineScope()

    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(
            animationScope = animationScope
        )
    }

    Row(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    blur(blurRadius.toPx())
                    lens(12f.dp.toPx(), 24f.dp.toPx())
                },
                // iOS 26: the rim light swings toward the finger and brightens while pressed.
                highlight = if (isInteractive) {
                    { followingHighlight(interactiveHighlight) }
                } else {
                    { Highlight.Default }
                },
                layerBlock = if (isInteractive) {
                    {
                        val width = size.width
                        val height = size.height

                        val progress = interactiveHighlight.pressProgress
                        val scale = lerp(1f, 1f + 4f.dp.toPx() / size.height, progress)

                        val maxOffset = size.minDimension
                        val initialDerivative = 0.05f
                        val offset = interactiveHighlight.offset
                        translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
                        translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)

                        val maxDragScale = 4f.dp.toPx() / size.height
                        val offsetAngle = atan2(offset.y, offset.x)
                        scaleX =
                            scale +
                                    maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
                                    (width / height).fastCoerceAtMost(1f)
                        scaleY =
                            scale +
                                    maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
                                    (height / width).fastCoerceAtMost(1f)
                    }
                } else {
                    null
                },
                onDrawSurface = {
                    if (tint.isSpecified) {
                        drawRect(tint, blendMode = BlendMode.Hue)
                        drawRect(tint.copy(alpha = 0.75f))
                    }
                    if (surfaceColor.isSpecified) {
                        drawRect(surfaceColor)
                    }
                }
            )
            .clickable(
                enabled = enabled,
                interactionSource = null,
                indication = if (isInteractive) null else LocalIndication.current,
                role = Role.Button,
                onClick = onClick
            )
            .then(
                if (isInteractive && enabled) {
                    Modifier
                        .then(interactiveHighlight.modifier)
                        .then(interactiveHighlight.gestureModifier)
                } else {
                    Modifier
                }
            )
            .height(height)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8f.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** Kyant's resting highlight angle: light on the top-left and bottom-right rims. */
private const val RestingHighlightAngle = 45f

/**
 * The rim highlight of a pressed glass control: at rest the default 45° light; under the finger it
 * turns to the finger's side (the shader lights both ends of the axis, so angles repeat every
 * 180°) and brightens.
 */
internal fun followingHighlight(interaction: InteractiveHighlight): Highlight {
    val progress = interaction.pressProgress.fastCoerceIn(0f, 1f)
    val size = interaction.size
    if (progress <= 0.001f || size.isUnspecified || size.width <= 0f || size.height <= 0f) return Highlight.Default
    val touch = interaction.touchPosition
    val touchAngle = Math.toDegrees(
        atan2((touch.y - size.height / 2f).toDouble(), (touch.x - size.width / 2f).toDouble()),
    ).toFloat()
    // Shortest turn on the 180° period.
    val turn = ((touchAngle - RestingHighlightAngle) % 180f + 270f) % 180f - 90f
    return Highlight(
        style = HighlightStyle.Default(
            color = Color.White.copy(alpha = 0.5f + 0.3f * progress),
            angle = RestingHighlightAngle + turn * progress,
        ),
    )
}
