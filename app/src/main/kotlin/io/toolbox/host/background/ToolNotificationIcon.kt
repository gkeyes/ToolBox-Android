package io.toolbox.host.background

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle

/**
 * True when the system is HyperOS or MIUI, whose shade draws the launcher icon at the left and honours
 * [MIUI_APP_ICON_EXTRA]. The brand alone is not enough: an AOSP ROM on Xiaomi hardware has no such shade,
 * and there the tool icon must stay the large icon.
 */
internal fun isMiuiNotificationShade(
    incremental: String = Build.VERSION.INCREMENTAL.orEmpty(),
    manufacturer: String = Build.MANUFACTURER.orEmpty(),
    property: (String) -> String? = ::systemProperty,
): Boolean {
    val values = MIUI_VERSION_PROPERTIES.map(property)
    if (values.any { !it.isNullOrBlank() }) return true
    // All null means the properties couldn't be read at all; HyperOS and MIUI builds still name themselves
    // in the incremental version (OS2.0.5.0.VNACNXM, V14.0.3.0.TKHCNXM). Other ROMs can use the same shape,
    // so the fallback only trusts it on Xiaomi hardware.
    return values.all { it == null } &&
        manufacturer.equals("Xiaomi", ignoreCase = true) &&
        MIUI_INCREMENTAL.matches(incremental)
}

private val MIUI_INCREMENTAL = Regex("""^(OS|V)\d+\.\d+.*""")

private val MIUI_VERSION_PROPERTIES = listOf("ro.mi.os.version.name", "ro.mi.os.version.code", "ro.miui.ui.version.name")

private fun systemProperty(name: String): String? = runCatching {
    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String
}.getOrNull()

/** HyperOS/MIUI notification extra that replaces the launcher icon it draws at the left of a notification. */
internal const val MIUI_APP_ICON_EXTRA = "miui.appIcon"

/**
 * Shows the tool's own icon on a notification it caused, once.
 *
 * HyperOS/MIUI ignores the small icon in the shade and draws ToolBox's launcher icon at the left, so the
 * tool icon goes there through [MIUI_APP_ICON_EXTRA] and no large icon repeats it at the right. Stock
 * Android tints the small icon to a silhouette, so it keeps ToolBox's glyph and shows the tool's icon as
 * the large icon, or no large icon when the tool has none.
 */
internal fun Notification.Builder.setToolIcon(
    context: Context,
    toolIcon: Bitmap?,
    xiaomi: Boolean = isMiuiNotificationShade(),
): Notification.Builder {
    if (!xiaomi) {
        // No tool icon: the ToolBox glyph at the left is enough, without the launcher icon repeating it.
        return toolIcon?.let { setLargeIcon(Icon.createWithBitmap(it)) } ?: this
    }
    // Without a tool icon the launcher icon at the left already says ToolBox.
    toolIcon?.let { addExtras(Bundle().apply { putParcelable(MIUI_APP_ICON_EXTRA, Icon.createWithBitmap(it)) }) }
    return this
}
