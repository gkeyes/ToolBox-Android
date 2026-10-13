package io.toolbox.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.RoundedRectangularShape
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

/**
 * Shared Liquid Glass backdrop. Content marked with [toolBoxBackdropSource] is recorded into a
 * layer; chrome drawn with [toolBoxGlassEffect] samples that layer and refracts it through an AGSL
 * lens (Kyant0/AndroidLiquidGlass). Chrome must be a sibling drawn above the source, never inside it.
 */
@Stable
class ToolBoxGlassState internal constructor(
    val backdrop: LayerBackdrop,
)

private val LocalToolBoxGlassActive = staticCompositionLocalOf { true }

@Composable
fun ToolBoxGlassActivity(
    active: Boolean,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalToolBoxGlassActive provides active, content = content)
}

@Composable
fun rememberToolBoxGlassState(): ToolBoxGlassState {
    val background = ToolBoxThemeTokens.colors.background
    val backdrop = rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    }
    return androidx.compose.runtime.remember(backdrop) { ToolBoxGlassState(backdrop) }
}

@Composable
private fun glassRendersLive(): Boolean =
    ToolBoxThemeTokens.materials.realBlurEnabled &&
        LocalToolBoxGlassActive.current &&
        LocalView.current.isHardwareAccelerated

@Composable
fun Modifier.toolBoxBackdropSource(state: ToolBoxGlassState): Modifier =
    if (glassRendersLive()) layerBackdrop(state.backdrop) else this

/**
 * Liquid Glass surface: vibrancy, a light blur and edge refraction over the recorded backdrop,
 * with the specular rim highlight and soft drop shadow of iOS 27 chrome. Falls back to an opaque
 * tinted fill when transparency is reduced or the window is not hardware accelerated.
 *
 * [mask] is kept for source compatibility with the old blur implementation and is ignored: the
 * refraction lens already fades the effect towards the shape's edge.
 */
@Composable
fun Modifier.toolBoxGlassEffect(
    state: ToolBoxGlassState,
    shape: Shape,
    blurAllowed: Boolean = true,
    @Suppress("UNUSED_PARAMETER") mask: Brush? = null,
    borderWidth: Dp = 0.75.dp,
): Modifier {
    val materials = ToolBoxThemeTokens.materials
    val lensShape = shape as? RoundedRectangularShape ?: shape as? CornerBasedShape
    if (!glassRendersLive() || !blurAllowed || lensShape == null) {
        return toolBoxSolidGlass(shape, borderWidth)
    }
    val tint = materials.glassTint
    return drawBackdrop(
        backdrop = state.backdrop,
        shape = { lensShape },
        effects = {
            vibrancy()
            blur(materials.blurRadius.toPx())
            lens(
                refractionHeight = 16.dp.toPx(),
                refractionAmount = 32.dp.toPx(),
                depthEffect = true,
            )
        },
        highlight = { Highlight.Default },
        shadow = { Shadow.Default },
        onDrawSurface = { drawRect(tint) },
    )
}

@Composable
fun Modifier.toolBoxSolidGlass(shape: Shape, borderWidth: Dp = 0.75.dp): Modifier {
    val colors = ToolBoxThemeTokens.colors
    val materials = ToolBoxThemeTokens.materials
    val fill = materials.glassTint.copy(alpha = 0.92f).compositeOver(colors.background)
    return clip(shape)
        .background(fill)
        .then(if (borderWidth > 0.dp) Modifier.border(borderWidth, materials.glassBorder, shape) else Modifier)
}
