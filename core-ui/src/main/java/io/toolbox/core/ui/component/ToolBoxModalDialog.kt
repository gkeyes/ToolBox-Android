package io.toolbox.core.ui.component

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import java.util.function.Consumer

/** A window-backed modal material, including when the content underneath is a WebView. */
@Composable
fun ToolBoxModalDialog(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        val view = LocalView.current
        val window = (view.parent as DialogWindowProvider).window
        val colors = ToolBoxThemeTokens.colors
        val materials = ToolBoxThemeTokens.materials
        val radius = ToolBoxThemeTokens.radii.card
        val density = LocalDensity.current
        val cornerPx = with(density) { radius.toPx() }
        val blurPx = with(density) { materials.blurRadius.roundToPx() }
        val shape = RoundedCornerShape(radius)

        DisposableEffect(window, colors.surface, materials.realBlurEnabled, cornerPx, blurPx) {
            val manager = window.context.getSystemService(WindowManager::class.java)
            val previousBackground = window.decorView.background
            val background = GradientDrawable().apply {
                cornerRadius = cornerPx
                setColor(colors.surface.copy(alpha = 1f).toArgb())
            }
            val attributes = window.context.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsTranslucent))
            val translucentWindow = try { attributes.getBoolean(0, false) } finally { attributes.recycle() }
            window.setBackgroundDrawable(background)
            var active = true
            fun update(enabled: Boolean) {
                if (!active) return
                val blur = enabled && materials.realBlurEnabled && translucentWindow &&
                    window.isFloating && view.isHardwareAccelerated
                // Keep a stable neutral text plate. At 90%, both light/dark secondary text
                // retain >= 4.5:1 even over the worst-case black/white backdrop.
                // If blur disappears, remove transparency in the same UI-thread callback.
                background.setColor(colors.surface.copy(alpha = if (blur) 0.90f else 1f).toArgb())
                window.setBackgroundBlurRadius(if (blur) blurPx else 0)
            }
            val listener = Consumer<Boolean> { update(it) }
            val attachListener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = update(manager.isCrossWindowBlurEnabled)
                override fun onViewDetachedFromWindow(view: View) = Unit
            }
            view.addOnAttachStateChangeListener(attachListener)
            update(manager.isCrossWindowBlurEnabled)
            manager.addCrossWindowBlurEnabledListener(window.context.mainExecutor, listener)
            onDispose {
                active = false
                view.removeOnAttachStateChangeListener(attachListener)
                manager.removeCrossWindowBlurEnabledListener(listener)
                window.setBackgroundBlurRadius(0)
                window.setBackgroundDrawable(previousBackground)
            }
        }
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth()
                .clip(shape)
                .border(1.dp, materials.glassBorder, shape)
                .verticalScroll(rememberScrollState())
                .padding(ToolBoxThemeTokens.spacing.three),
            content = content,
        )
    }
}
