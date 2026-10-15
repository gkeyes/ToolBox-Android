package io.toolbox.core.ui.component

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import kotlin.math.roundToInt

/**
 * Liquid Glass for a panel in an overlay window (a dialog). The overlay cannot sample the page
 * behind it, so [rememberToolBoxWindowGlass] copies the page window once the overlay opens and
 * [toolBoxWindowGlassEffect] refracts that copy: blur, vibrancy, edge lens and rim highlight.
 */
@Stable
class ToolBoxWindowGlass internal constructor() {
    internal var copy by mutableStateOf<WindowBackdrop?>(null)

    /** False until the page copy lands (and always with 实色材质 on): draw an opaque panel then. */
    val ready: Boolean get() = copy != null
}

/** [hostView] is the page's view: read LocalView outside the Dialog. */
@Composable
fun rememberToolBoxWindowGlass(hostView: View): ToolBoxWindowGlass {
    val glass = remember { ToolBoxWindowGlass() }
    val live = ToolBoxThemeTokens.materials.realBlurEnabled && hostView.isHardwareAccelerated
    val configuration = LocalConfiguration.current
    LaunchedEffect(hostView, live, configuration.screenWidthDp, configuration.screenHeightDp) {
        glass.copy = null
        if (!live) return@LaunchedEffect
        // Let the page settle at its current size before copying it.
        withFrameNanos { }
        withFrameNanos { }
        glass.copy = captureWindowBackdrop(hostView, scale = 0.5f)
    }
    return glass
}

/** The page copy, pinned to where the page sits on screen. */
private class WindowCopyBackdrop(private val copy: WindowBackdrop) : Backdrop {
    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val origin = coordinates?.takeIf { it.isAttached }?.positionInWindow() ?: return
        val pageSize = IntSize(
            (copy.image.width / copy.scale).roundToInt(),
            (copy.image.height / copy.scale).roundToInt(),
        )
        translate(-origin.x, -origin.y) {
            drawImage(copy.image, dstOffset = IntOffset.Zero, dstSize = pageSize)
        }
    }
}

/**
 * Draws [glass] behind this node: the page frosted by [blurRadius] (colours and shapes stay, text
 * does not read), refracted at the rounded edge, under the overlay fill. Before the copy lands, or
 * with 实色材质 on, it draws the opaque overlay fallback instead.
 */
@Composable
fun Modifier.toolBoxWindowGlassEffect(glass: ToolBoxWindowGlass, cornerRadius: Dp, blurRadius: Dp = 28.dp): Modifier {
    val materials = ToolBoxThemeTokens.materials
    val copy = glass.copy
    if (copy == null || !materials.realBlurEnabled || !LocalView.current.isHardwareAccelerated) {
        return background(materials.overlayFallback)
    }
    val backdrop = remember(copy) { WindowCopyBackdrop(copy) }
    val lensShape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val fill = materials.overlayFill
    return drawBackdrop(
        backdrop = backdrop,
        shape = { lensShape },
        effects = {
            vibrancy()
            blur(blurRadius.toPx())
            lens(
                refractionHeight = 20.dp.toPx(),
                refractionAmount = 36.dp.toPx(),
                depthEffect = true,
            )
        },
        highlight = { Highlight.Default },
        shadow = null,
        onDrawSurface = { drawRect(fill) },
    )
}
