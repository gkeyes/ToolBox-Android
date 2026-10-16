package io.toolbox.host.background

import android.app.Application
import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import io.toolbox.host.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ToolNotificationIconTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val toolIcon = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

    private fun build(icon: Bitmap?, xiaomi: Boolean): Notification =
        Notification.Builder(app, "test")
            .setSmallIcon(R.drawable.ic_toolbox_notification)
            .setToolIcon(app, icon, xiaomi)
            .build()

    @Test fun hyperOsShowsTheToolIconOnceInPlaceOfTheAppIcon() {
        val notification = build(toolIcon, xiaomi = true)

        val replaced = notification.extras.getParcelable(MIUI_APP_ICON_EXTRA, Icon::class.java)
        assertNotNull(replaced)
        assertEquals(Icon.TYPE_BITMAP, replaced!!.type)
        assertNull("No second copy at the right", notification.getLargeIcon())
    }

    @Test fun hyperOsWithoutAToolIconKeepsOnlyTheAppIcon() {
        val notification = build(null, xiaomi = true)

        assertFalse(notification.extras.containsKey(MIUI_APP_ICON_EXTRA))
        assertNull(notification.getLargeIcon())
    }

    @Test fun stockAndroidShowsTheToolIconAsTheLargeIcon() {
        val notification = build(toolIcon, xiaomi = false)

        assertEquals(Icon.TYPE_BITMAP, notification.getLargeIcon()?.type)
        assertFalse(notification.extras.containsKey(MIUI_APP_ICON_EXTRA))
    }

    @Test fun onlyAHyperOsOrMiuiSystemCountsAsTheXiaomiShade() {
        assertEquals(true, isMiuiNotificationShade { if (it == "ro.mi.os.version.name") "OS2.0" else "" })
        assertEquals(true, isMiuiNotificationShade { if (it == "ro.miui.ui.version.name") "V14" else null })
        // An AOSP ROM on Xiaomi hardware, or any other phone, keeps the tool icon as the large icon.
        assertFalse(isMiuiNotificationShade(property = { "" }, incremental = "OS2.0.5.0.VNACNXM"))
        assertFalse(isMiuiNotificationShade(property = { null }, incremental = "eng.build.20261016"))
    }

    @Test fun unreadablePropertiesFallBackToTheBuildsOwnVersionNameOnXiaomiOnly() {
        fun shade(incremental: String, manufacturer: String = "Xiaomi") =
            isMiuiNotificationShade(incremental = incremental, manufacturer = manufacturer, property = { null })
        assertEquals(true, shade("OS2.0.5.0.VNACNXM"))
        assertEquals(true, shade("V14.0.3.0.TKHCNXM"))
        // The version needs a dot after its first number.
        assertFalse(shade("V14"))
        assertFalse(shade("OS2"))
        // Another ROM with the same shape is not HyperOS.
        assertFalse(shade("V1.2.3", manufacturer = "Google"))
        assertFalse(shade("OS2.0.1", manufacturer = "samsung"))
    }

    @Test fun stockAndroidWithoutAToolIconShowsNoLargeIcon() {
        val notification = build(null, xiaomi = false)

        assertNull("No ToolBox launcher icon repeated at the right", notification.getLargeIcon())
        assertFalse(notification.extras.containsKey(MIUI_APP_ICON_EXTRA))
    }
}
