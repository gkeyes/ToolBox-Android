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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * A downscaled copy of the window under an overlay; [scale] maps it back to window pixels and
 * [rootOnScreen] is where that window's content sits on screen.
 */
internal class WindowBackdrop(val image: ImageBitmap, val scale: Float, val rootOnScreen: Offset)

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
                        if (result == PixelCopy.SUCCESS) WindowBackdrop(bitmap.asImageBitmap(), bitmap.width.toFloat() / root.width, rootOnScreen) else null,
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
