package io.toolbox.core.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import io.toolbox.core.ui.liquid.LiquidButton
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
 * Opaque button roles, used with 实色材质 on (no glass): prominent = solid accent; regular =
 * accent at 10% / 18%; neutral = rgba(118,118,128,0.12 / 0.24); destructive = danger at
 * 10% / 18%; a destructive confirmation = solid danger.
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

/**
 * The glass a filled button refracts. Whoever owns the surface behind the buttons can provide a
 * backdrop of it; without one the button is clear glass over whatever is drawn behind it: the
 * page shows through unbent, under the button's tint, rim highlight, shadow and press glow.
 */
val LocalToolBoxButtonBackdrop = staticCompositionLocalOf<Backdrop?> { null }

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
    val emphasized = tone == ToolBoxActionTone.Primary || tone == ToolBoxActionTone.DangerConfirmation
    val haptics = rememberToolBoxHaptics()
    val tap = {
        if (emphasized || tone == ToolBoxActionTone.Danger) haptics.confirm()
        onClick()
    }
    val labelStyle = if (compact) {
        ToolBoxThemeTokens.textStyles.metadata.copy(fontWeight = FontWeight.SemiBold)
    } else {
        // iOS 26 large control label: 17 / SemiBold for every role.
        ToolBoxThemeTokens.textStyles.headline
    }
    val height = when {
        // Small inline capsule, 44 / radius 22.
        compact -> 44.dp
        // Page and sheet capsules, 50 / radius 25.
        filled -> ToolBoxThemeTokens.sizes.primaryButton
        else -> ToolBoxThemeTokens.sizes.touchTarget
    }
    val live = ToolBoxThemeTokens.materials.realBlurEnabled && LocalView.current.isHardwareAccelerated

    if (filled && live) {
        // iOS 26 interactive glass: the capsule refracts what is behind it, carries a rim
        // highlight, and grows with a glow under the finger instead of shrinking.
        val glass = toolBoxGlassActionColors(tone, contentColorOverride)
        LiquidButton(
            onClick = tap,
            backdrop = LocalToolBoxButtonBackdrop.current ?: emptyBackdrop(),
            modifier = modifier
                .sizeIn(minWidth = 48.dp)
                .graphicsLayer { alpha = if (enabled) 1f else 0.42f },
            enabled = enabled,
            surfaceColor = glass.container,
            contentPadding = PaddingValues(horizontal = if (compact) 14.dp else 20.dp),
            height = height,
        ) {
            ToolBoxActionLabel(label, icon, iconColorOverride ?: glass.content, labelStyle.copy(color = glass.content), compact)
        }
        return
    }

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
    val shape = toolBoxShape(ToolBoxThemeTokens.radii.button)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (enabled && pressed) 1f else 0f,
        animationSpec = ToolBoxMotion.pressSpec(pressed),
        label = "toolbox-action-press",
    )
    // Filled (only with 实色材质 on): an opaque capsule that dims its label under the finger.
    // Bare text: the iOS borderless button, whose label dims; neither shrinks nor greys.
    val pressedOverlay = if (filled) palette.content.copy(alpha = (if (emphasized) 0.14f else 0.10f) * press.coerceIn(0f, 1f)) else Color.Transparent
    val background = if (filled) pressedOverlay.compositeOver(palette.container) else Color.Transparent

    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp)
            .heightIn(min = height)
            .graphicsLayer {
                alpha = (if (enabled) 1f else 0.42f) * (if (filled) 1f else 1f - 0.65f * press)
            }
            .clip(shape)
            .background(background)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
                onClick = tap,
            )
            .padding(
                horizontal = if (compact) 14.dp else if (filled) 20.dp else 16.dp,
                vertical = if (compact) 8.dp else 12.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolBoxActionLabel(label, icon, iconColorOverride ?: palette.content, labelStyle.copy(color = palette.content), compact)
        }
    }
}

@Composable
private fun ToolBoxActionLabel(label: String, icon: ToolBoxIconKey?, iconTint: Color, style: TextStyle, compact: Boolean) {
    if (icon != null) {
        ToolBoxIcon(
            icon = icon,
            contentDescription = null,
            modifier = Modifier.size(if (compact) 13.dp else 18.dp),
            tint = iconTint,
        )
    }
    ToolBoxText(text = label, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * Glass button roles (iOS 26): prominent = accent-tinted glass, nearly opaque so its white label
 * keeps 4.5:1; regular = the clear glass tint with an accent or neutral wash; destructive = a red
 * wash; a destructive confirmation = red-tinted prominent glass.
 */
@Composable
private fun toolBoxGlassActionColors(tone: ToolBoxActionTone, contentColorOverride: Color?): ToolBoxActionColors {
    val colors = ToolBoxThemeTokens.colors
    val dark = colors.surface.luminance() < 0.5f
    val clear = ToolBoxThemeTokens.materials.glassTint
    return when (tone) {
        ToolBoxActionTone.Primary -> prominentGlassColors(colors.primary, colors.background)
        ToolBoxActionTone.DangerConfirmation -> prominentGlassColors(colors.danger, colors.background)
        ToolBoxActionTone.Tonal -> ToolBoxActionColors(
            colors.primary.copy(alpha = if (dark) 0.18f else 0.10f).compositeOver(clear),
            contentColorOverride ?: colors.primary,
        )
        ToolBoxActionTone.Neutral -> ToolBoxActionColors(
            ToolBoxNeutralFill.copy(alpha = if (dark) 0.24f else 0.12f).compositeOver(clear),
            contentColorOverride ?: colors.textPrimary,
        )
        ToolBoxActionTone.Danger -> ToolBoxActionColors(
            colors.danger.copy(alpha = if (dark) 0.18f else 0.10f).compositeOver(clear),
            colors.danger,
        )
    }
}

private fun prominentGlassColors(fill: Color, behind: Color): ToolBoxActionColors {
    val glass = fill.copy(alpha = ProminentGlassAlpha)
    // The label is judged against the glass over the page, the lightest it can look.
    val seen = glass.compositeOver(behind)
    // Whichever label reads better: white on the accent, the iOS dark label on light reds
    // (#FF6961 over a dark page is ~3.4:1 with white, ~5:1 with #1C1C1E).
    val dark = Color(0xFF1C1C1E)
    return ToolBoxActionColors(glass, if (actionContrast(seen, Color.White) >= actionContrast(seen, dark)) Color.White else dark)
}

private const val ProminentGlassAlpha = 0.9f

@Composable
fun ToolBoxPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    /** The 44dp inline capsule, for top bars where it sits beside 44dp round buttons. */
    compact: Boolean = false,
) {
    ToolBoxActionButton(
        label = label,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        tone = if (destructive) ToolBoxActionTone.DangerConfirmation else ToolBoxActionTone.Primary,
        compact = compact,
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
