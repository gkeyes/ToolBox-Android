package io.toolbox.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.liquid.LiquidButton
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

/**
 * Round iOS 27 glass button (top-bar actions, the tab bar's search circle). Uses the vendored
 * Kyant [LiquidButton] so it refracts the page and squishes under the finger; with reduced
 * transparency it becomes an opaque disc with the same footprint.
 */
@Composable
fun ToolBoxGlassIconButton(
    icon: ToolBoxIconKey,
    contentDescription: String,
    onClick: () -> Unit,
    glassState: ToolBoxGlassState,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 20.dp,
    enabled: Boolean = true,
) {
    val colors = ToolBoxThemeTokens.colors
    val iconTint = if (enabled) colors.textPrimary else colors.textTertiary
    val live = ToolBoxThemeTokens.materials.realBlurEnabled && LocalView.current.isHardwareAccelerated
    val described = modifier
        .size(size)
        .semantics { this.contentDescription = contentDescription }
    if (live) {
        LiquidButton(
            onClick = onClick,
            backdrop = glassState.backdrop,
            modifier = described,
            enabled = enabled,
            surfaceColor = ToolBoxThemeTokens.materials.glassTint,
            // A round icon button centres its glyph; the default 16dp text padding would leave
            // only 12dp of a 44dp button for a 20dp icon.
            contentPadding = PaddingValues(0.dp),
        ) {
            ToolBoxIcon(icon = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(iconSize))
        }
    } else {
        Box(
            described
                .toolBoxSolidGlass(CircleShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            ToolBoxIcon(icon = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(iconSize))
        }
    }
}
