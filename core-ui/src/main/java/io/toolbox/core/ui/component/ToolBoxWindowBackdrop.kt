package io.toolbox.core.ui.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine

/** A downscaled copy of the window under an overlay; [scale] maps it back to window pixels. */
internal class WindowBackdrop(val image: ImageBitmap, val scale: Float)

/**
 * Copies the window that hosts [view] (the page under a sheet). Overlay windows cannot sample
 * the page behind them, so a sheet blurs this copy instead. The page stays put while a modal
 * sheet is open, so one copy taken on open is enough. Returns null when the copy fails.
 */
internal suspend fun captureWindowBackdrop(view: View, scale: Float = 0.25f): WindowBackdrop? {
    val window = (view.parent as? DialogWindowProvider)?.window ?: view.context.findActivity()?.window ?: return null
    val root = window.peekDecorView() ?: return null
    if (root.width <= 0 || root.height <= 0) return null
    val bitmap = Bitmap.createBitmap(
        (root.width * scale).roundToInt().coerceAtLeast(1),
        (root.height * scale).roundToInt().coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )
    return suspendCancellableCoroutine { continuation ->
        try {
            PixelCopy.request(window, bitmap, { result ->
                if (continuation.isActive) {
                    continuation.resume(
                        if (result == PixelCopy.SUCCESS) WindowBackdrop(bitmap.asImageBitmap(), bitmap.width.toFloat() / root.width) else null,
                    )
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: IllegalArgumentException) {
            // The window has no surface yet (or any more).
            if (continuation.isActive) continuation.resume(null)
        }
    }
}

/**
 * Draws [backdrop] blurred behind this node, pinned to where the page sits on screen, so the
 * panel reads as frosted glass while it slides or is dragged. [motion] reads the state that
 * moves the panel, so the backdrop redraws on every frame of that motion.
 */
@Composable
internal fun Modifier.windowBackdrop(backdrop: WindowBackdrop, blurRadius: Dp, motion: () -> Unit): Modifier {
    // Survives recomposition: placement callbacks only fire when the panel actually moves.
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return onGloballyPositioned { coordinates[0] = it }.drawWithCache {
        val radius = blurRadius.toPx()
        val layer = obtainGraphicsLayer().apply { renderEffect = BlurEffect(radius, radius, TileMode.Clamp) }
        val pageSize = IntSize(
            (backdrop.image.width / backdrop.scale).roundToInt(),
            (backdrop.image.height / backdrop.scale).roundToInt(),
        )
        onDrawWithContent {
            motion()
            val origin = coordinates[0]?.takeIf { it.isAttached }?.positionInWindow() ?: Offset.Zero
            layer.record {
                translate(-origin.x, -origin.y) {
                    drawImage(backdrop.image, dstOffset = IntOffset.Zero, dstSize = pageSize)
                }
            }
            drawLayer(layer)
            drawContent()
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
