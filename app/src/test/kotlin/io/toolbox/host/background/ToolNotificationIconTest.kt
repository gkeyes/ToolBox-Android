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
}
