package io.toolbox.core.ui.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine
import androidx.compose.ui.graphics.luminance

/**
 * A downscaled copy of the window under an overlay; [scale] maps it back to window pixels and
 * [rootOnScreen] is where that window's content sits on screen.
 */
internal class WindowBackdrop(
    val image: ImageBitmap,
    val scale: Float,
    val rootOnScreen: Offset,
    /** Relative luminance on a [LuminanceSteps]² grid over the page, row by row. */
    private val grid: FloatArray = FloatArray(LuminanceSteps * LuminanceSteps) { 0.5f },
) {
    /** Mean relative luminance of the page, so glass over a page of the opposite tone stays legible. */
    val luminance: Float = grid.average().toFloat()

    /**
     * Darkest and brightest cells under [rect]: text can land on any part of the panel, so a dark
     * panel over a half-white page must stay legible over the white half, not over the mean.
     */
    fun luminanceRangeIn(rect: Rect): ClosedFloatingPointRange<Float> {
        val cells = cellsIn(rect) ?: return luminance..luminance
        return cells.min()..cells.max()
    }

    private fun cellsIn(rect: Rect): FloatArray? {
        val pageWidth = image.width / scale
        val pageHeight = image.height / scale
        if (pageWidth <= 0f || pageHeight <= 0f) return null
        val last = LuminanceSteps - 1
        fun cell(value: Float, extent: Float) = (value / extent * last).roundToInt().coerceIn(0, last)
        val left = cell(rect.left, pageWidth)
        val right = cell(rect.right, pageWidth)
        val top = cell(rect.top, pageHeight)
        val bottom = cell(rect.bottom, pageHeight)
        val cells = FloatArray((bottom - top + 1) * (right - left + 1))
        var i = 0
        for (y in top..bottom) for (x in left..right) cells[i++] = grid[y * LuminanceSteps + x]
        return cells
    }
}

private const val LuminanceSteps = 16

/** Relative luminance on a [LuminanceSteps]² grid of samples. */
private fun Bitmap.luminanceGrid(): FloatArray {
    val last = LuminanceSteps - 1
    return FloatArray(LuminanceSteps * LuminanceSteps) { index ->
        val x = index % LuminanceSteps
        val y = index / LuminanceSteps
        androidx.compose.ui.graphics.Color(getPixel(x * (width - 1) / last, y * (height - 1) / last)).luminance()
    }
}

/**
 * Copies the window that hosts [view] (the page under a sheet). Overlay windows cannot sample
 * the page behind them, so a sheet blurs this copy instead, taken on open and again whenever
 * the page settles after a change. Returns null when the copy fails.
 */
internal suspend fun captureWindowBackdrop(view: View, scale: Float = 0.25f): WindowBackdrop? {
    val window = (view.parent as? DialogWindowProvider)?.window ?: view.context.findActivity()?.window ?: return null
    val root = window.peekDecorView() ?: return null
    if (root.width <= 0 || root.height <= 0) return null
    val location = IntArray(2).also(root::getLocationOnScreen)
    val rootOnScreen = Offset(location[0].toFloat(), location[1].toFloat())
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
                        if (result == PixelCopy.SUCCESS) WindowBackdrop(bitmap.asImageBitmap(), bitmap.width.toFloat() / root.width, rootOnScreen, bitmap.luminanceGrid()) else null,
                    )
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: IllegalArgumentException) {
            // The window has no surface yet (or any more).
            if (continuation.isActive) continuation.resume(null)
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
