package io.toolbox.host.backup

import io.toolbox.tool.packagekit.PackageRejection
import io.toolbox.tool.packagekit.PackageRejectionCode
import io.toolbox.tool.packagekit.PackageValidationResult
import io.toolbox.tool.packagekit.backup.BackupException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** ARC-2: a backed-up tool for a newer ToolBox is skipped on its own instead of failing the whole restore. */
class RestorePackageCheckTest {
    @Test
    fun newerHostPackageIsSkippedPerTool() {
        val rejected = PackageValidationResult.Rejected(
            PackageRejection(PackageRejectionCode.UNSUPPORTED_HOST_VERSION, "Package requires ToolBox 99.0.0 or newer"),
        )
        assertNull(HostBackupService.restorablePackage(rejected))
        assertEquals("此工具需要更高版本的 ToolBox，请升级后恢复", HostBackupService.NEWER_HOST_REQUIRED)
    }

    @Test
    fun otherRejectionsStillFailThePreview() {
        val rejected = PackageValidationResult.Rejected(PackageRejection(PackageRejectionCode.MANIFEST_INVALID, "bad"))
        try {
            HostBackupService.restorablePackage(rejected)
            fail("A malformed package must fail the restore preview")
        } catch (failure: BackupException) {
            assertEquals("PACKAGE_MANIFEST_INVALID", failure.code)
        }
    }
}
