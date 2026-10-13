package io.toolbox.tool.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeWebViewDebuggingPolicyTest {
    @Test fun debugApksKeepDebuggingEnabledAcrossSystemBuilds() {
        for (buildType in listOf("user", "userdebug", "eng")) {
            val status = runtimeWebViewDebuggingStatus(debuggableApp = true, buildType)
            assertTrue(status.enabledByBuild)
            assertTrue(status.enabled)
            assertFalse(status.forcedByPlatform)
        }
    }

    @Test fun releaseApksDoNotEnableAnInterfaceOnAUserSystem() {
        val status = runtimeWebViewDebuggingStatus(debuggableApp = false, "user")
        assertFalse(status.enabledByBuild)
        assertFalse(status.enabled)
        assertFalse(status.forcedByPlatform)
    }

    @Test fun aForcedSystemInterfaceIsReportedWithoutChangingTheReleaseBuildPolicy() {
        for (buildType in listOf("userdebug", "eng")) {
            val status = runtimeWebViewDebuggingStatus(debuggableApp = false, buildType)
            assertFalse(status.enabledByBuild)
            assertTrue(status.enabled)
            assertTrue(status.forcedByPlatform)
        }
    }

    @Test fun releaseApksCanBeSwitchedOnForThisProcessOnly() {
        val off = runtimeWebViewDebuggingStatus(debuggableApp = false, "user")
        assertTrue(off.userToggleable)
        assertFalse(off.appliedEnabled)

        val on = runtimeWebViewDebuggingStatus(debuggableApp = false, "user", enabledByUser = true)
        assertFalse(on.enabledByBuild)
        assertTrue(on.enabledByUser)
        assertTrue(on.appliedEnabled)
        assertTrue(on.enabled)
        assertFalse(on.forcedByPlatform)
        assertTrue(on.userToggleable)
    }

    @Test fun debugApksAndForcedSystemsAreNotUserToggleable() {
        val debug = runtimeWebViewDebuggingStatus(debuggableApp = true, "user", enabledByUser = true)
        assertFalse(debug.userToggleable)
        assertFalse(debug.enabledByUser)
        assertTrue(debug.appliedEnabled)
        for (buildType in listOf("userdebug", "eng")) {
            val forced = runtimeWebViewDebuggingStatus(debuggableApp = false, buildType)
            assertFalse(forced.userToggleable)
            assertFalse(forced.appliedEnabled)
            assertTrue(forced.enabled)
        }
    }
}
