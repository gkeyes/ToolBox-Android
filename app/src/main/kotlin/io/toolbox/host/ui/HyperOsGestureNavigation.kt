package io.toolbox.host.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.WindowManager

private fun isXiaomiDevice(): Boolean =
    Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
        Build.BRAND.equals("Xiaomi", ignoreCase = true) ||
        Build.BRAND.equals("Redmi", ignoreCase = true) ||
        Build.BRAND.equals("POCO", ignoreCase = true)

/**
 * HyperOS/MIUI exposes its full-screen gesture mode through force_fsg_nav_bar.
 * Xiaomi's developer guidance recommends translucent navigation only in gesture
 * mode; classic three-button navigation keeps the ordinary navigation surface.
 */
internal fun isHyperOsGestureNavigation(context: Context): Boolean {
    if (!isXiaomiDevice()) return false
    return runCatching {
        Settings.Global.getInt(context.contentResolver, "force_fsg_nav_bar", 0) != 0
    }.getOrDefault(false)
}

internal fun Activity.applyHyperOsGestureNavigationImmersion() {
    if (!isXiaomiDevice()) return
    if (isHyperOsGestureNavigation(this)) {
        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
    }
}
