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
}
