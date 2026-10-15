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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.toSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size

/**
 * Liquid Glass for a panel in an overlay window (a dialog). The overlay cannot sample the page
 * behind it, so [rememberToolBoxWindowGlass] copies the page window once the overlay opens and
 * [toolBoxWindowGlassEffect] refracts that copy: blur, vibrancy, edge lens and rim highlight.
 */
@Stable
class ToolBoxWindowGlass internal constructor() {
    internal var copy by mutableStateOf<WindowBackdrop?>(null)
    /** The panel's page tone as last drawn, shared with the buttons on it. */
    internal val tone = PanelTone()
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
    /** Null: measure the scale of the layers the glass sits in (a popup zooming in on its own). */
    private val layerScale: (() -> Float)?,
    /** Drawn over the copy (made legible like the panel's): the panel's own fill, for controls on it. */
    private val overlay: (() -> Color)? = null,
    /** Read on every draw, so a control moving with its panel (a slide, a drag) is redrawn in register. */
    private val motion: () -> Unit = {},
    /** The panel's tone: controls on it take the panel's legible fill, not one of their own. */
    private val panelTone: PanelTone? = null,
) : Backdrop {
    override val isCoordinatesDependent: Boolean = true

    /** Darkest to brightest luminance of the page under the glass as last drawn; the fill reads it in the same frame. */
    var regionLuminance: ClosedFloatingPointRange<Float> = copy.luminance..copy.luminance
        private set

    private val layerProbe by lazy { GraphicsLayerScope() }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        motion()
        // A pressed button grows through layerBlock, a layer the node's coordinates already sit
        // in (so the origin and scale below compensate for it); read it so every frame of the
        // press redraws the copy in register instead of stretching the last one.
        if (layerBlock != null) layerProbe.layerBlock()
        val node = coordinates?.takeIf { it.isAttached } ?: return
        // On screen, not in window: popups and dialogs need not share the page window's origin.
        val origin = node.positionOnScreen() - copy.rootOnScreen
        regionLuminance = copy.luminanceRangeIn(Rect(origin, node.size.toSize()))
        val pageSize = IntSize(
            (copy.image.width / copy.scale).roundToInt(),
            (copy.image.height / copy.scale).roundToInt(),
        )
        // Each axis on its own: a button stretched by a drag scales x and y differently.
        val fixed = layerScale?.invoke()
        val zero = node.localToRoot(Offset.Zero)
        val width = node.size.width.toFloat()
        val height = node.size.height.toFloat()
        val scaleX = fixed ?: if (width <= 0f) 1f else (node.localToRoot(Offset(width, 0f)) - zero).x / width
        val scaleY = fixed ?: if (height <= 0f) 1f else (node.localToRoot(Offset(0f, height)) - zero).y / height
        scale(1f / scaleX.coerceAtLeast(0.01f), 1f / scaleY.coerceAtLeast(0.01f), pivot = Offset.Zero) {
            translate(-origin.x, -origin.y) {
                drawImage(copy.image, dstOffset = IntOffset.Zero, dstSize = pageSize)
            }
        }
        // Past the control's bounds too: blur and lens sample a padded region around it.
        if (overlay != null) drawRect(legibleFill(overlay(), panelTone?.range ?: regionLuminance), topLeft = Offset(-OverlayBleed, -OverlayBleed), size = Size(size.width + 2 * OverlayBleed, size.height + 2 * OverlayBleed))
    }
}

/**
 * Buttons on a panel of window glass (a sheet, a dialog, the group panel) refract what the panel
 * shows: the page copy under the panel's [fill], blurred as much as the panel. Without a copy
 * (实色材质, or before it lands) they keep the clear backdrop. [motion] reads whatever moves the
 * panel, as for the panel's own glass.
 */
@Composable
internal fun ProvideWindowButtonBackdrop(
    copy: WindowBackdrop?,
    fill: () -> Color,
    blurRadius: Dp,
    motion: () -> Unit = {},
    tone: PanelTone? = null,
    content: @Composable () -> Unit,
) {
    val currentFill by rememberUpdatedState(fill)
    val currentMotion by rememberUpdatedState(motion)
    val backdrop = remember(copy, tone) {
        copy?.let { WindowCopyBackdrop(it, null, { currentFill() }, { currentMotion() }, tone) }
    }
    // One provider either way, so the content keeps its state while the copy comes and goes.
    CompositionLocalProvider(
        LocalToolBoxButtonBackdrop provides (backdrop ?: LocalToolBoxButtonBackdrop.current),
        LocalToolBoxButtonBlur provides (if (backdrop != null) blurRadius else LocalToolBoxButtonBlur.current),
        content = content,
    )
}

/** [ProvideWindowButtonBackdrop] for a panel drawn with [toolBoxWindowGlassEffect]. */
@Composable
fun ProvideToolBoxWindowButtonBackdrop(
    glass: ToolBoxWindowGlass,
    fill: Color = ToolBoxThemeTokens.materials.overlayFill,
    blurRadius: Dp = 28.dp,
    motion: () -> Unit = {},
    content: @Composable () -> Unit,
) = ProvideWindowButtonBackdrop(glass.copy, { fill }, blurRadius, motion, glass.tone, content)

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
    layerScale: (() -> Float)? = null,
    motion: () -> Unit = {},
    tone: PanelTone? = null,
): Modifier {
    val scale by rememberUpdatedState(layerScale)
    val backdrop = remember(copy) { WindowCopyBackdrop(copy, layerScale?.let { { scale?.invoke() ?: 1f } }) }
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
        onDrawSurface = {
            tone?.range = backdrop.regionLuminance
            drawRect(legibleFill(fill(), backdrop.regionLuminance))
        },
    )
}

/** The page tone under a glass panel as last drawn, so the controls on it match its fill. */
internal class PanelTone {
    var range: ClosedFloatingPointRange<Float>? = null
}

/**
 * A dark panel over light content (a web article behind a dark-theme dialog) lets that light
 * through and washes out secondary text: thicken the fill then. [pageLuminance] runs from the
 * darkest to the brightest cell under the panel. A light panel is left alone: its secondary text
 * still reads over black (5:1 at .66), and saturated tiles read as "dark", so thickening it made
 * panels over colourful pages look solid. A faint light control (a 24% clear-glass disc) over
 * near-black content still takes the panel's .66 so its dark icon stays visible; over coloured
 * tiles the dark icon already reads at .24.
 */
private fun legibleFill(fill: Color, pageLuminance: ClosedFloatingPointRange<Float>): Color {
    val darkPanel = fill.copy(alpha = 1f).luminance() < 0.5f
    return when {
        darkPanel && pageLuminance.endInclusive > 0.45f && fill.alpha < DarkPanelLegibleAlpha ->
            fill.copy(alpha = DarkPanelLegibleAlpha)
        !darkPanel && pageLuminance.start < 0.05f && fill.alpha < FaintFillAlpha ->
            fill.copy(alpha = LightControlLegibleAlpha)
        else -> fill
    }
}

private const val DarkPanelLegibleAlpha = 0.77f
private const val LightControlLegibleAlpha = 0.66f
private const val FaintFillAlpha = 0.4f

/**
 * Draws [glass] behind this node (see [snapshotGlass]). Before the copy lands, or with 实色材质
 * on, it draws the opaque [fallback] instead. Only the panel itself records the page tone; a
 * control drawn from the same [glass] passes [panel] = false so it can't overwrite it.
 */
@Composable
fun Modifier.toolBoxWindowGlassEffect(
    glass: ToolBoxWindowGlass,
    cornerRadius: Dp,
    blurRadius: Dp = 28.dp,
    fill: Color = ToolBoxThemeTokens.materials.overlayFill,
    fallback: Color = ToolBoxThemeTokens.materials.overlayFallback,
    layerScale: (() -> Float)? = null,
    panel: Boolean = true,
    motion: () -> Unit = {},
): Modifier {
    val copy = glass.copy
    if (copy == null || !ToolBoxThemeTokens.materials.realBlurEnabled || !LocalView.current.isHardwareAccelerated) {
        return background(fallback)
    }
    return snapshotGlass(copy, cornerRadius, blurRadius, { fill }, layerScale, motion, if (panel) glass.tone else null)
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
    val backdrop = remember(copy) { WindowCopyBackdrop(copy, layerScale = { 1f }) }
    return drawBackdrop(
        backdrop = backdrop,
        shape = { RectangleShape },
        effects = { blur(blurRadius.toPx()) },
        highlight = null,
        shadow = null,
        onDrawSurface = { drawRect(tint) },
    )
}

/** How far a control's panel overlay extends past it, beyond any blur or lens padding. */
private const val OverlayBleed = 4096f
