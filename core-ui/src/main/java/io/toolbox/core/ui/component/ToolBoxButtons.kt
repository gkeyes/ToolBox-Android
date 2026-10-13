package io.toolbox.core.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.core.ui.theme.readableForeground

private enum class ToolBoxActionTone { Primary, Tonal, Neutral, Danger, DangerConfirmation }

private data class ToolBoxActionColors(val container: Color, val content: Color)

/** WCAG contrast between two opaque colors. */
internal fun actionContrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * Solid iOS 26 fills carry 17sp semibold labels, which count as large text and need 3:1.
 * White passes on #0064D2 / #0A84FF accent and light #D70015 destructive; dark #FF6961
 * destructive is only ~2.8:1 against white, so it (and any very light custom accent) takes
 * the iOS dark label #1C1C1E (~6:1) instead.
 */
private fun solidActionColors(fill: Color): ToolBoxActionColors =
    ToolBoxActionColors(fill, if (actionContrast(fill, Color.White) >= 3.0f) Color.White else Color(0xFF1C1C1E))

/**
 * Flat iOS 26 button roles: prominent = solid accent; regular = accent at 10% / 18%;
 * neutral = rgba(118,118,128,0.12 / 0.24); destructive = danger at 10% / 18%; a destructive
 * confirmation = solid danger. Translucent fills stay translucent so they sit on glass sheets.
 */
@Composable
private fun toolBoxActionColors(tone: ToolBoxActionTone, contentColorOverride: Color?): ToolBoxActionColors {
    val colors = ToolBoxThemeTokens.colors
    val dark = colors.surface.luminance() < 0.5f
    val accent = colors.primary
    return when (tone) {
        ToolBoxActionTone.Primary -> solidActionColors(accent)
        ToolBoxActionTone.DangerConfirmation -> solidActionColors(colors.danger)
        ToolBoxActionTone.Tonal -> ToolBoxActionColors(
            accent.copy(alpha = if (dark) 0.18f else 0.10f),
            contentColorOverride ?: accent,
        )
        ToolBoxActionTone.Neutral -> ToolBoxActionColors(
            ToolBoxNeutralFill.copy(alpha = if (dark) 0.24f else 0.12f),
            contentColorOverride ?: colors.textPrimary,
        )
        ToolBoxActionTone.Danger -> ToolBoxActionColors(
            colors.danger.copy(alpha = if (dark) 0.18f else 0.10f),
            colors.danger,
        )
    }
}

/** iOS tertiarySystemFill base, rgb(118,118,128); callers apply 0.12 light / 0.24 dark. */
internal val ToolBoxNeutralFill = Color(0xFF767680)

@Composable
private fun ToolBoxActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: ToolBoxActionTone,
    contentColorOverride: Color? = null,
    icon: ToolBoxIconKey? = null,
    iconColorOverride: Color? = null,
    outlined: Boolean = true,
    compact: Boolean = false,
) {
    val colors = ToolBoxThemeTokens.colors
    val filled = outlined
    val palette = if (filled) {
        toolBoxActionColors(tone, contentColorOverride)
    } else {
        val preferred = contentColorOverride ?: when (tone) {
            ToolBoxActionTone.Danger, ToolBoxActionTone.DangerConfirmation -> colors.danger
            ToolBoxActionTone.Neutral -> colors.textPrimary
            else -> colors.primary
        }
        ToolBoxActionColors(Color.Transparent, readableForeground(preferred, listOf(colors.surface)))
    }
    val emphasized = tone == ToolBoxActionTone.Primary || tone == ToolBoxActionTone.DangerConfirmation
    val shape = toolBoxShape(ToolBoxThemeTokens.radii.button)
    val haptics = rememberToolBoxHaptics()

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (enabled && pressed) 1f else 0f,
        animationSpec = ToolBoxMotion.pressSpec(pressed),
        label = "toolbox-action-press",
    )
    // A state layer tinted by the label color reads in both themes, unlike a grey wash.
    val pressedOverlay = palette.content.copy(alpha = (if (filled && emphasized) 0.14f else 0.10f) * press.coerceIn(0f, 1f))
    val background = if (filled) pressedOverlay.compositeOver(palette.container) else pressedOverlay

    val labelStyle = if (compact) {
        ToolBoxThemeTokens.textStyles.metadata.copy(fontWeight = FontWeight.SemiBold)
    } else {
        // iOS 26 large control label: 17 / SemiBold for every role.
        ToolBoxThemeTokens.textStyles.headline
    }

    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp)
            .heightIn(
                min = when {
                    // Small inline capsule, 44 / radius 22.
                    compact -> 44.dp
                    // Page and sheet capsules, 50 / radius 25.
                    filled -> ToolBoxThemeTokens.sizes.primaryButton
                    else -> ToolBoxThemeTokens.sizes.touchTarget
                },
            )
            .graphicsLayer {
                val scale = 1f - (1f - ToolBoxMotion.PressScale) * press
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.42f
            }
            .clip(shape)
            .background(background)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    if (emphasized || tone == ToolBoxActionTone.Danger) haptics.confirm()
                    onClick()
                },
            )
            .padding(
                horizontal = if (compact) 14.dp else if (filled) 20.dp else 16.dp,
                vertical = if (compact) 8.dp else 12.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                ToolBoxIcon(
                    icon = icon,
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 13.dp else 18.dp),
                    tint = iconColorOverride ?: palette.content,
                )
                Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
            }
            ToolBoxText(
                text = label,
                style = labelStyle.copy(color = palette.content),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ToolBoxPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    ToolBoxActionButton(
        label = label,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        tone = if (destructive) ToolBoxActionTone.DangerConfirmation else ToolBoxActionTone.Primary,
    )
}

@Composable
fun ToolBoxSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // Secondary actions are tonal: accent at 12% behind an accent label.
    ToolBoxActionButton(label, onClick, modifier, enabled, ToolBoxActionTone.Tonal)
}

@Composable
fun ToolBoxDestructiveButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ToolBoxActionButton(label, onClick, modifier, enabled, ToolBoxActionTone.Danger)
}

/** Standalone actions use a light material surface; embedded text actions stay minimal. */
@Composable
fun ToolBoxTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = ToolBoxThemeTokens.colors.primary,
    outlined: Boolean = true,
) {
    ToolBoxActionButton(
        label = label,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        tone = when (contentColor) {
            ToolBoxThemeTokens.colors.onSoftDanger, ToolBoxThemeTokens.colors.danger -> ToolBoxActionTone.Danger
            ToolBoxThemeTokens.colors.primary -> ToolBoxActionTone.Tonal
            else -> ToolBoxActionTone.Neutral
        },
        contentColorOverride = contentColor,
        outlined = outlined,
    )
}

@Composable
fun ToolBoxRunningStatusButton(
    stopping: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ToolBoxActionButton(
        label = if (stopping) "停止中" else "停止",
        onClick = onClick,
        modifier = modifier.sizeIn(minWidth = ToolBoxThemeTokens.sizes.touchTarget),
        enabled = enabled,
        tone = ToolBoxActionTone.Neutral,
        icon = ToolBoxIconKey.Stop,
        iconColorOverride = ToolBoxThemeTokens.colors.onSoftDanger,
        compact = true,
    )
}
