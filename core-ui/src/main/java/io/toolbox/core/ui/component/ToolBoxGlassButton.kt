package io.toolbox.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.emptyBackdrop
import io.toolbox.core.ui.liquid.LiquidButton
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import androidx.compose.ui.graphics.Shape
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.shadow.Shadow

/**
 * Round iOS 27 glass button. Uses the vendored Kyant [LiquidButton], so it refracts what is behind
 * it, carries a rim highlight and grows with a glow under the finger. [glassState] is the page
 * backdrop for chrome over the page (top bar, the tab bar's search circle); without one, or while
 * that backdrop is not being recorded (a page transition), it refracts [LocalToolBoxButtonBackdrop]
 * or is clear glass. With reduced transparency it is an opaque disc with the same footprint.
 * [tint] makes it prominent: accent- or red-tinted glass with a white glyph.
 */
@Composable
fun ToolBoxGlassIconButton(
    icon: ToolBoxIconKey,
    contentDescription: String,
    onClick: () -> Unit,
    glassState: ToolBoxGlassState? = null,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 20.dp,
    enabled: Boolean = true,
    tint: Color? = null,
    iconTint: Color? = null,
) {
    val colors = ToolBoxThemeTokens.colors
    val glyph = iconTint ?: when {
        !enabled -> colors.textTertiary
        tint != null -> Color.White
        else -> colors.textPrimary
    }
    val live = ToolBoxThemeTokens.materials.realBlurEnabled && LocalView.current.isHardwareAccelerated
    val recorded = glassState != null && glassRendersLive()
    val described = modifier
        .size(size)
        .semantics { this.contentDescription = contentDescription }
    if (live) {
        LiquidButton(
            onClick = onClick,
            backdrop = if (recorded) glassState!!.backdrop else LocalToolBoxButtonBackdrop.current ?: emptyBackdrop(),
            modifier = described,
            enabled = enabled,
            // Disabled fades the fill, not a layer around the button (that would clip its shadow).
            surfaceColor = tint?.copy(alpha = if (enabled) 0.9f else 0.38f) ?: ToolBoxThemeTokens.materials.glassTint,
            blurRadius = if (recorded) 2.dp else LocalToolBoxButtonBlur.current,
            // A round icon button centres its glyph; the default 16dp text padding would leave
            // only 12dp of a 44dp button for a 20dp icon.
            contentPadding = PaddingValues(0.dp),
            height = size,
        ) {
            ToolBoxIcon(icon = icon, contentDescription = null, tint = glyph, modifier = Modifier.size(iconSize))
        }
    } else {
        Box(
            described
                .then(if (tint != null) Modifier.clip(CircleShape).background(tint) else Modifier.toolBoxSolidGlass(CircleShape))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            ToolBoxIcon(icon = icon, contentDescription = null, tint = glyph, modifier = Modifier.size(iconSize))
        }
    }
}

/**
 * A custom glass capsule button (sheet shortcuts, detail actions, browser capsules): the same
 * interactive glass as [ToolBoxGlassIconButton] around [content]. [surface] is its tint: clear
 * glass ([toolBoxClearGlassFill]) or an accent at ~0.9 for a prominent one. With reduced
 * transparency it is an opaque capsule of [solidSurface] whose content dims under the finger.
 */
@Composable
fun ToolBoxGlassCapsule(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    surface: Color = toolBoxClearGlassFill(),
    solidSurface: Color = surface.compositeOver(ToolBoxThemeTokens.colors.surface),
    height: Dp = ToolBoxThemeTokens.sizes.primaryButton,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val live = ToolBoxThemeTokens.materials.realBlurEnabled && LocalView.current.isHardwareAccelerated
    if (live) {
        LiquidButton(
            onClick = onClick,
            backdrop = LocalToolBoxButtonBackdrop.current ?: emptyBackdrop(),
            modifier = modifier,
            enabled = enabled,
            surfaceColor = surface,
            blurRadius = LocalToolBoxButtonBlur.current,
            contentPadding = contentPadding,
            height = height,
            content = content,
        )
    } else {
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        Row(
            modifier
                .clip(CircleShape)
                .background(solidSurface)
                .clickable(enabled = enabled, role = Role.Button, interactionSource = interactionSource, indication = null, onClick = onClick)
                .graphicsLayer { alpha = if (pressed) 0.55f else 1f }
                .heightIn(min = height)
                .padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** Clear glass for a regular button: the glass tint with the iOS neutral wash, so it reads on any page. */
@Composable
fun toolBoxClearGlassFill(): Color {
    val dark = ToolBoxThemeTokens.colors.surface.luminance() < 0.5f
    return ToolBoxNeutralFill.copy(alpha = if (dark) 0.24f else 0.12f).compositeOver(ToolBoxThemeTokens.materials.glassTint)
}

/**
 * Glass body for a custom control (a disc, a capsule bar): Kyant's rim highlight and soft shadow
 * over [LocalToolBoxButtonBackdrop] (clear glass when there is none), under [tint]. Pair it with
 * [toolBoxGlassPress]. With reduced transparency it is an opaque [solid] fill in [shape].
 * Disable [shadowEnabled] for inset content whose parent clips an external drop shadow.
 */
@Composable
fun Modifier.toolBoxClearGlass(
    shape: Shape,
    tint: Color = toolBoxClearGlassFill(),
    solid: Color = tint.compositeOver(ToolBoxThemeTokens.colors.surface),
    shadowEnabled: Boolean = true,
): Modifier {
    val live = ToolBoxThemeTokens.materials.realBlurEnabled && LocalView.current.isHardwareAccelerated
    if (!live) return clip(shape).background(solid)
    val backdrop = LocalToolBoxButtonBackdrop.current ?: emptyBackdrop()
    val blurRadius = LocalToolBoxButtonBlur.current
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(blurRadius.toPx())
        },
        shadow = if (shadowEnabled) ({ Shadow.Default }) else null,
        onDrawSurface = { drawRect(tint) },
    )
}
