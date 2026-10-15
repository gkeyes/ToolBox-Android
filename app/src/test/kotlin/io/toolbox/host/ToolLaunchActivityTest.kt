package io.toolbox.host

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ToolLaunchActivityTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    @Test
    fun exportedLauncherIgnoresABareOpenToolIntent() {
        // What any other app can send to the exported MainActivity.
        val forged = Intent(app, MainActivity::class.java)
            .setAction("io.toolbox.host.OPEN_TOOL")
            .putExtra("toolId", "demo.tool")
        assertNull(resolveLaunchToolId(app, forged))

        val guessedToken = Intent(app, MainActivity::class.java)
            .setAction("io.toolbox.host.action.LAUNCH_TOOL")
            .putExtra("io.toolbox.host.extra.LAUNCH_TOKEN", "not-a-token-this-process-issued")
        assertNull(resolveLaunchToolId(app, guessedToken))
    }

    @Test
    fun trampolineForwardsTheHostsOwnIntentOnce() {
        val activity = Robolectric.buildActivity(
            ToolLaunchActivity::class.java,
            MainActivity.openToolIntent(app, "demo.tool"),
        ).create().get()

        assertTrue(activity.isFinishing)
        val forwarded = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, forwarded.component?.className)
        val reuseFlags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertEquals(reuseFlags, forwarded.flags and reuseFlags)
        assertEquals("demo.tool", resolveLaunchToolId(app, forwarded))
        // The token is used up: replaying the same intent opens nothing.
        assertNull(resolveLaunchToolId(app, forwarded))
    }

    @Test
    fun recentsRelaunchOfTheTrampolineOpensNoTool() {
        val fromRecents = MainActivity.openToolIntent(app, "demo.tool")
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)

        val forwarded = ToolLaunchActivity.forwardIntent(app, fromRecents)

        assertEquals(MainActivity::class.java.name, forwarded.component?.className)
        assertNull(resolveLaunchToolId(app, forwarded))
    }
}
