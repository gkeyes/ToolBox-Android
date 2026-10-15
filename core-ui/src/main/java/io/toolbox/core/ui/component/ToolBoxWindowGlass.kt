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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.graphics.Color
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
    internal var copySize: IntSize? = null

    /** False until the page copy lands (and always with 实色材质 on): draw an opaque panel then. */
    val ready: Boolean get() = copy != null

    /**
     * Copies [hostView]'s window now, for glass drawn inside that same window (a toast over the
     * page). Call it before the glass shows, so the copy doesn't hold the glass itself.
     */
    suspend fun capture(hostView: View) {
        copy = if (hostView.isHardwareAccelerated) captureWindowBackdrop(hostView, scale = 0.5f) else null
    }

    /** Drops the copy once its glass is gone, so a hidden toast doesn't hold a full-screen bitmap. */
    fun release() {
        copy = null
    }
}

/**
 * [hostView] is the page's view: read LocalView outside the Dialog or popup. The copy is taken
 * once [active] turns true (the overlay is showing) and again after a resize.
 */
@Composable
fun rememberToolBoxWindowGlass(hostView: View, active: Boolean = true): ToolBoxWindowGlass {
    val glass = remember { ToolBoxWindowGlass() }
    val live = ToolBoxThemeTokens.materials.realBlurEnabled && hostView.isHardwareAccelerated
    val configuration = LocalConfiguration.current
    LaunchedEffect(hostView, live, active, configuration.screenWidthDp, configuration.screenHeightDp) {
        if (!live) {
            glass.copy = null
            return@LaunchedEffect
        }
        // A copy from another screen size is out of register: drop it rather than show it.
        val size = IntSize(configuration.screenWidthDp, configuration.screenHeightDp)
        if (glass.copySize != size) glass.copy = null
        // A closing overlay keeps its last copy, so it doesn't turn solid while it animates out.
        // On reopen the old (heavily blurred) copy shows for the couple of frames until the new
        // one lands, which reads better than a solid flash.
        if (!active) return@LaunchedEffect
        // Let the page settle at its current size before copying it.
        withFrameNanos { }
        withFrameNanos { }
        glass.copy = captureWindowBackdrop(hostView, scale = 0.5f)
        glass.copySize = size
    }
    return glass
}

/**
 * The page copy, pinned to where the page sits on screen. [layerScale] is the scale of a
 * graphicsLayer the glass sits in (a panel zooming in): the copy is drawn unscaled, so it stays in
 * register with the page while the panel scales.
 */
private class WindowCopyBackdrop(
    private val copy: WindowBackdrop,
    private val layerScale: () -> Float,
) : Backdrop {
    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val node = coordinates?.takeIf { it.isAttached } ?: return
        // On screen, not in window: popups and dialogs need not share the page window's origin.
        val origin = node.positionOnScreen() - copy.rootOnScreen
        val pageSize = IntSize(
            (copy.image.width / copy.scale).roundToInt(),
            (copy.image.height / copy.scale).roundToInt(),
        )
        val inverse = 1f / layerScale().coerceAtLeast(0.01f)
        scale(inverse, inverse, pivot = Offset.Zero) {
            translate(-origin.x, -origin.y) {
                drawImage(copy.image, dstOffset = IntOffset.Zero, dstSize = pageSize)
            }
        }
    }
}

/**
 * Liquid Glass over a copy of the page: the page frosted by [blurRadius] (colours and shapes stay,
 * text does not read), refracted at the rounded edge with a rim highlight, under [fill]. [motion]
 * reads whatever moves the panel (a slide, a drag), so the copy is re-aligned on every frame of it.
 */
@Composable
internal fun Modifier.snapshotGlass(
    copy: WindowBackdrop,
    cornerRadius: Dp,
    blurRadius: Dp,
    fill: () -> Color,
    layerScale: () -> Float = { 1f },
    motion: () -> Unit = {},
): Modifier {
    val scale by rememberUpdatedState(layerScale)
    val backdrop = remember(copy) { WindowCopyBackdrop(copy) { scale() } }
    val lensShape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
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
        onDrawBackdrop = { draw ->
            motion()
            draw()
        },
        onDrawSurface = { drawRect(fill()) },
    )
}

/**
 * Draws [glass] behind this node (see [snapshotGlass]). Before the copy lands, or with 实色材质
 * on, it draws the opaque [fallback] instead.
 */
@Composable
fun Modifier.toolBoxWindowGlassEffect(
    glass: ToolBoxWindowGlass,
    cornerRadius: Dp,
    blurRadius: Dp = 28.dp,
    fill: Color = ToolBoxThemeTokens.materials.overlayFill,
    fallback: Color = ToolBoxThemeTokens.materials.overlayFallback,
    layerScale: () -> Float = { 1f },
    motion: () -> Unit = {},
): Modifier {
    val copy = glass.copy
    if (copy == null || !ToolBoxThemeTokens.materials.realBlurEnabled || !LocalView.current.isHardwareAccelerated) {
        return background(fallback)
    }
    return snapshotGlass(copy, cornerRadius, blurRadius, { fill }, layerScale, motion)
}

/**
 * The whole page behind an overlay, frosted: the copy blurred by [blurRadius] under [tint], so the
 * page reads as colour and shape around a panel, never as text beside the panel's own. Draws
 * [fallback] before the copy lands or with 实色材质 on.
 */
@Composable
fun Modifier.toolBoxWindowFrost(
    glass: ToolBoxWindowGlass,
    tint: Color,
    fallback: Color,
    blurRadius: Dp = 28.dp,
): Modifier {
    val copy = glass.copy
    if (copy == null || !ToolBoxThemeTokens.materials.realBlurEnabled || !LocalView.current.isHardwareAccelerated) {
        return background(fallback)
    }
    val backdrop = remember(copy) { WindowCopyBackdrop(copy) { 1f } }
    return drawBackdrop(
        backdrop = backdrop,
        shape = { RectangleShape },
        effects = { blur(blurRadius.toPx()) },
        highlight = null,
        shadow = null,
        onDrawSurface = { drawRect(tint) },
    )
}
