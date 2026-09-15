package io.toolbox.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

/** Static light field shared by native content and chrome; never captures a WebView. */
@Composable
fun Modifier.toolBoxOpenDesignCanvas(): Modifier {
    val colors = ToolBoxThemeTokens.colors
    val reduced = !ToolBoxThemeTokens.materials.realBlurEnabled
    return background(colors.background).drawWithCache {
        val light = Brush.radialGradient(
            listOf(colors.primary.copy(alpha = 0.12f), Color.Transparent),
            center = Offset(size.width, size.height * 0.08f),
            radius = size.width * 0.75f,
        )
        val shade = Brush.radialGradient(
            listOf(colors.textPrimary.copy(alpha = 0.045f), Color.Transparent),
            center = Offset(-size.width * 0.1f, size.height * 0.88f),
            radius = size.width * 0.65f,
        )
        onDrawBehind {
            if (!reduced) {
                drawRect(light)
                drawRect(shade)
            }
        }
    }
}

/** Stable translucent content surface: no per-row capture or moving blur. */
@Composable
fun Modifier.toolBoxOpenDesignSurface(shape: Shape): Modifier {
    val colors = ToolBoxThemeTokens.colors
    val reduced = !ToolBoxThemeTokens.materials.realBlurEnabled
    return shadow(5.dp, shape, clip = false, ambientColor = colors.textPrimary.copy(alpha = 0.10f),
        spotColor = colors.textPrimary.copy(alpha = 0.08f))
        .clip(shape)
        .background(Brush.verticalGradient(listOf(
            colors.surface.copy(alpha = if (reduced) 1f else 0.86f),
            colors.surface.copy(alpha = if (reduced) 1f else 0.64f),
        )))
        .border(1.dp, ToolBoxThemeTokens.materials.glassBorder, shape)
}
