package io.toolbox.host.backup

import io.toolbox.core.data.BundleLocator
import io.toolbox.core.data.ToolVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreIdentityTest {
    private val installed = ToolVersion(
        toolId = "io.toolbox.sample", versionCode = 5, version = "1.5",
        bundleLocator = BundleLocator("miniapps/io.toolbox.sample/versions/5/bundle"),
        bundleBytes = 10, integrityHash = "installed-hash", installedAt = 1,
    )

    @Test
    fun aToolThatIsNotInstalledIsInstalledAsUsual() {
        assertEquals(RestoreIdentity.Replace, restoreIdentity(null, null, false, null, 5, "x"))
    }

    @Test
    fun aSignedInstallAcceptsOnlyTheSameKey() {
        assertEquals(RestoreIdentity.Replace, restoreIdentity(installed, "key-a", false, "key-a", 4, "x"))
        val otherKey = restoreIdentity(installed, "key-a", false, "key-b", 6, "x")
        assertTrue(otherKey is RestoreIdentity.Skip && otherKey.reason.contains("签名"))
        assertTrue(restoreIdentity(installed, "key-a", false, null, 6, "x") is RestoreIdentity.Skip)
    }

    @Test
    fun anUnsignedInstallIsNeverReplacedByDifferentCode() {
        // The user's own backup of exactly this package still restores as before.
        assertEquals(RestoreIdentity.Replace, restoreIdentity(installed, null, false, null, 5, "installed-hash"))
        // Same or older version with other bytes: keep the installed code and restore only data.
        assertEquals(RestoreIdentity.KeepInstalled, restoreIdentity(installed, null, false, null, 5, "other-hash"))
        assertEquals(RestoreIdentity.KeepInstalled, restoreIdentity(installed, null, false, "attacker-key", 3, "other-hash"))
        // Newer code whose author cannot be verified is skipped, keeping the installed tool and its data.
        val newer = restoreIdentity(installed, null, false, null, 6, "other-hash")
        assertTrue(newer is RestoreIdentity.Skip && newer.reason.startsWith("包身份无法验证"))
    }

    @Test
    fun anUnreadableInstalledBundleIsSkipped() {
        val skipped = restoreIdentity(installed, null, true, null, 5, "installed-hash")
        assertTrue(skipped is RestoreIdentity.Skip && skipped.reason.startsWith("包身份无法验证"))
    }

    @Test
    fun keptInstallsStillCountAsRecoverable() {
        val preview = listOf(
            RestoreToolPlan("a", "A", "1.5", "1.5", keepInstalled = true),
            RestoreToolPlan("b", "B", "2.0", "1.0", skipped = "包身份无法验证"),
        )
        assertEquals(RestorePlanCounts(recoverable = 1, overwritten = 1, skipped = 1), restorePlanCounts(preview))
    }
}
