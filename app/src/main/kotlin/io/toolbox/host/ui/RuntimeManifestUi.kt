package io.toolbox.host.ui

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import io.toolbox.tool.packagekit.ManifestOrientation
import io.toolbox.tool.packagekit.ManifestStatusBarStyle
import io.toolbox.tool.packagekit.ManifestUi

/** The Activity orientation a manifest asks for, or null to leave the host's own (unspecified). */
internal fun ManifestOrientation?.requestedOrientation(): Int? = when (this) {
    ManifestOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    ManifestOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    ManifestOrientation.UNSPECIFIED, null -> null
}

/**
 * Whether the status bar should draw dark icons, or null to keep the host theme's choice.
 * "light" means light (white) status bar content for a dark page; "dark" means dark content.
 */
internal fun ManifestStatusBarStyle.darkStatusBarIcons(): Boolean? = when (this) {
    ManifestStatusBarStyle.LIGHT -> false
    ManifestStatusBarStyle.DARK -> true
    ManifestStatusBarStyle.AUTO -> null
}

/**
 * Applies [readyUi] while the runtime is ready and keeps the last one applied while it reloads or
 * shows an error, so a portrait-locked tool does not rotate during a reload. Everything is restored
 * only when this leaves composition (the tool's screen closes).
 */
@Composable
internal fun ApplyRetainedRuntimeManifestUi(readyUi: ManifestUi?) {
    // A plain holder, not state: it only remembers the last ready value for the non-ready frames.
    val retained = remember { arrayOfNulls<ManifestUi>(1) }
    if (readyUi != null) retained[0] = readyUi
    (readyUi ?: retained[0])?.let { ApplyRuntimeManifestUi(it) }
}

/**
 * Applies the running tool's manifest `ui` block to the Activity while the tool is on screen and
 * restores the host's values when it leaves. `allowFullscreen` is enforced by the runtime WebView's
 * custom-view handling, not here.
 */
// The lock is the tool's own declared choice and is lifted when the tool leaves.
@SuppressLint("SourceLockedOrientationActivity")
@Composable
internal fun ApplyRuntimeManifestUi(ui: ManifestUi) {
    val activity = LocalActivity.current ?: return
    val orientation = ui.orientation.requestedOrientation()
    DisposableEffect(activity, orientation) {
        if (orientation != null) activity.requestedOrientation = orientation
        onDispose {
            // A configuration-change relaunch keeps the request on the same activity record and the
            // new instance re-applies it; resetting it there would rotate the screen back first.
            if (orientation != null && !activity.isChangingConfigurations) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    val darkIcons = ui.statusBarStyle.darkStatusBarIcons()
    // The host value captured right before the tool's style is applied, restored on leave.
    val hostDarkIcons = remember(activity, darkIcons) { arrayOfNulls<Boolean>(1) }
    // Launched (not applied during composition) so it runs after the host's own system-bar
    // LaunchedEffect when the Activity is recreated, instead of being overwritten by it.
    LaunchedEffect(activity, darkIcons) {
        if (darkIcons == null) return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (hostDarkIcons[0] == null) hostDarkIcons[0] = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = darkIcons
    }
    DisposableEffect(activity, darkIcons) {
        onDispose {
            hostDarkIcons[0]?.let { previous ->
                WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    .isAppearanceLightStatusBars = previous
            }
            hostDarkIcons[0] = null
        }
    }
}
