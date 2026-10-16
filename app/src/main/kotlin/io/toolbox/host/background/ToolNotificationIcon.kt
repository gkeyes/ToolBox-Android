package io.toolbox.host.background

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import io.toolbox.host.R

internal fun isXiaomiFamilyDevice(): Boolean =
    Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
        Build.BRAND.equals("Xiaomi", ignoreCase = true) ||
        Build.BRAND.equals("Redmi", ignoreCase = true) ||
        Build.BRAND.equals("POCO", ignoreCase = true)

/** HyperOS/MIUI notification extra that replaces the launcher icon it draws at the left of a notification. */
internal const val MIUI_APP_ICON_EXTRA = "miui.appIcon"

/**
 * Shows the tool's own icon on a notification it caused, once.
 *
 * HyperOS/MIUI ignores the small icon in the shade and draws ToolBox's launcher icon at the left, so the
 * tool icon goes there through [MIUI_APP_ICON_EXTRA] and no large icon repeats it at the right. Stock
 * Android tints the small icon to a silhouette, so it keeps ToolBox's glyph and shows the tool's icon as
 * the large icon.
 */
internal fun Notification.Builder.setToolIcon(
    context: Context,
    toolIcon: Bitmap?,
    xiaomi: Boolean = isXiaomiFamilyDevice(),
): Notification.Builder {
    if (!xiaomi) {
        return setLargeIcon(toolIcon?.let(Icon::createWithBitmap) ?: Icon.createWithResource(context, R.mipmap.ic_launcher))
    }
    // Without a tool icon the launcher icon at the left already says ToolBox.
    toolIcon?.let { addExtras(Bundle().apply { putParcelable(MIUI_APP_ICON_EXTRA, Icon.createWithBitmap(it)) }) }
    return this
}
