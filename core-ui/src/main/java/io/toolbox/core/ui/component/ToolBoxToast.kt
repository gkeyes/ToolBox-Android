package io.toolbox.core.ui.component

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import kotlinx.coroutines.delay

/**
 * iOS-style toast: a liquid glass capsule near the bottom of the page, in place of the system
 * Toast. It shows in the activity's own [ToolBoxToastHost]; an activity without one, or one that
 * is not on screen, gets the system Toast.
 */
object ToolBoxToasts {
    // Main thread only.
    private val hosts = java.util.WeakHashMap<Activity, (ToolBoxToastRequest) -> Unit>()

    fun show(context: Context, message: String, long: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { show(context, message, long) }
            return
        }
        val activity = context.findActivity()
        val host = activity?.let { hosts[it] }
        if (activity != null && host != null && !activity.isFinishing && activity.window.decorView.isShown) {
            host(ToolBoxToastRequest(message, if (long) 3_500L else 2_000L))
        } else {
            Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    internal fun register(activity: Activity, host: (ToolBoxToastRequest) -> Unit) { hosts[activity] = host }

    internal fun unregister(activity: Activity, host: (ToolBoxToastRequest) -> Unit) {
        if (hosts[activity] === host) hosts.remove(activity)
    }
}

/** One toast; a new instance per show, so the same text shown twice restarts the timer. */
internal class ToolBoxToastRequest(val message: String, val durationMs: Long)

/** Place once per activity, over all of its content (a fill-size Box aligned to the bottom). */
@Composable
fun ToolBoxToastHost(modifier: Modifier = Modifier) {
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() }
    var request by remember { mutableStateOf<ToolBoxToastRequest?>(null) }
    var visible by remember { mutableStateOf(false) }
    val glass = rememberToolBoxWindowGlass(view, active = false)
    DisposableEffect(activity) {
        val host: (ToolBoxToastRequest) -> Unit = { request = it }
        if (activity != null) ToolBoxToasts.register(activity, host)
        onDispose { if (activity != null) ToolBoxToasts.unregister(activity, host) }
    }
    LaunchedEffect(request) {
        val shown = request ?: return@LaunchedEffect
        // Copy the page before the capsule shows: the capsule refracts that copy.
        if (!visible && ToolBoxThemeTokens.materials.realBlurEnabled) glass.capture(view)
        visible = true
        delay(shown.durationMs)
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        // Clear of the system navigation and of a floating bottom bar, like the system toast.
        modifier = modifier.navigationBarsPadding().padding(bottom = 56.dp),
        enter = fadeIn() + scaleIn(initialScale = 0.9f) + slideInVertically { it / 2 },
        exit = fadeOut() + scaleOut(targetScale = 0.96f),
    ) {
        ToolBoxToastCapsule(request?.message.orEmpty(), glass)
    }
}

@Composable
private fun ToolBoxToastCapsule(text: String, glass: ToolBoxWindowGlass) {
    val colors = ToolBoxThemeTokens.colors
    val materials = ToolBoxThemeTokens.materials
    val dark = colors.surface.luminance() < 0.5f
    val shape = CircleShape
    val shadowColor = Color.Black.copy(alpha = if (dark) 0.55f else 0.18f)
    Box(
        Modifier
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .widthIn(max = 520.dp)
            .heightIn(min = 46.dp)
            .shadow(18.dp, shape, clip = false, ambientColor = shadowColor, spotColor = shadowColor)
            .clip(shape)
            .toolBoxWindowGlassEffect(glass, 23.dp)
            .border(0.5.dp, materials.overlayBorder, shape)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        ToolBoxText(
            text,
            style = ToolBoxThemeTokens.textStyles.headline.copy(
                color = colors.textPrimary,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
            ),
            maxLines = 3,
        )
    }
}
