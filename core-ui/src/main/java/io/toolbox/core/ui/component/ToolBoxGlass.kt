package io.toolbox.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.toolbox.core.ui.theme.ToolBoxThemeStyle
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

@Stable
class ToolBoxGlassState internal constructor(
    internal val hazeState: HazeState,
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
    val hazeState = rememberHazeState()
    return remember(hazeState) { ToolBoxGlassState(hazeState) }
}

@Composable
fun Modifier.toolBoxBackdropSource(state: ToolBoxGlassState): Modifier =
    if (
        ToolBoxThemeTokens.style == ToolBoxThemeStyle.LiquidGlass &&
        ToolBoxThemeTokens.materials.realBlurEnabled &&
        LocalToolBoxGlassActive.current &&
        LocalView.current.isHardwareAccelerated
    ) {
        hazeSource(state.hazeState)
    } else {
        this
    }

@Composable
fun Modifier.toolBoxGlassEffect(
    state: ToolBoxGlassState,
    shape: Shape,
    blurAllowed: Boolean = true,
    mask: Brush? = null,
    borderWidth: Dp = 0.75.dp,
): Modifier {
    val style = ToolBoxThemeTokens.style
    val materials = ToolBoxThemeTokens.materials
    val glassActive = LocalToolBoxGlassActive.current
    if (style != ToolBoxThemeStyle.LiquidGlass) return this

    val hardwareAccelerated = LocalView.current.isHardwareAccelerated
    val hazeStyle = HazeStyle(
        backgroundColor = materials.glassFallback,
        tint = HazeTint(materials.glassTint),
        blurRadius = materials.blurRadius,
        noiseFactor = materials.noiseFactor,
        fallbackTint = HazeTint(materials.glassFallback),
    )
    return clip(shape)
        .hazeEffect(state = state.hazeState, style = hazeStyle) {
            blurEnabled = materials.realBlurEnabled && glassActive && blurAllowed && hardwareAccelerated
            this.mask = mask
        }
        .then(
            if (borderWidth > 0.dp) {
                Modifier.border(borderWidth, materials.glassBorder, shape)
            } else {
                Modifier
            },
        )
}

@Composable
fun Modifier.toolBoxTopGlassEffect(state: ToolBoxGlassState): Modifier {
    val materials = ToolBoxThemeTokens.materials
    val canRenderLiveGlass = ToolBoxThemeTokens.style == ToolBoxThemeStyle.LiquidGlass &&
        materials.realBlurEnabled &&
        materials.topEdgeFadeEnabled &&
        LocalToolBoxGlassActive.current &&
        LocalView.current.isHardwareAccelerated
    if (!canRenderLiveGlass) return toolBoxSolidGlass(RectangleShape)

    return toolBoxGlassEffect(
        state = state,
        shape = RectangleShape,
        mask = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Black,
                0.74f to Color.Black,
                1f to Color.Transparent,
            ),
        ),
        borderWidth = 0.dp,
    )
}

@Composable
fun Modifier.toolBoxSolidGlass(shape: Shape): Modifier {
    val colors = ToolBoxThemeTokens.colors
    val materials = ToolBoxThemeTokens.materials
    val fill = if (ToolBoxThemeTokens.style == ToolBoxThemeStyle.LiquidGlass) {
        materials.glassTint.compositeOver(colors.background)
    } else {
        colors.background
    }
    return clip(shape)
        .background(fill)
        .border(0.75.dp, materials.glassBorder, shape)
}
