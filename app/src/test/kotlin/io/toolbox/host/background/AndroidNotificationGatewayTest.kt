package io.toolbox.host.background

import android.app.Application
import io.toolbox.host.ToolLaunchActivity
import io.toolbox.host.resolveLaunchToolId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AndroidNotificationGatewayTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    @Test
    fun contentIntentOpensTheNotifyingToolThroughTheValidatedRoute() {
        val intent = shadowOf(toolNotificationContentIntent(app, "demo.tool", "n1")).savedIntent

        assertEquals(ToolLaunchActivity::class.java.name, intent.component?.className)
        assertEquals("demo.tool", resolveLaunchToolId(app, ToolLaunchActivity.forwardIntent(app, intent)))
    }

    @Test
    fun contentIntentsAreDistinctPerToolAndNotification() {
        val a = shadowOf(toolNotificationContentIntent(app, "tool.a", "n1")).savedIntent.data
        val b = shadowOf(toolNotificationContentIntent(app, "tool.b", "n1")).savedIntent.data
        val c = shadowOf(toolNotificationContentIntent(app, "tool.a", "n2")).savedIntent.data

        assertNotEquals(a, b)
        assertNotEquals(a, c)
    }
}
