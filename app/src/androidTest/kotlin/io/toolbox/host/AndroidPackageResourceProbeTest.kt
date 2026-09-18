package io.toolbox.host

import android.os.StatFs
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.tool.packagekit.AndroidPackageResourceProbe
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against Android itself, not a fake probe or the desktop JVM provider. */
@RunWith(AndroidJUnit4::class)
class AndroidPackageResourceProbeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun readsAvailableSpaceInPrivateCacheDirectory() {
        assertReadableSpace(context.cacheDir)
    }

    @Test
    fun readsAvailableSpaceInPrivateFilesDirectory() {
        assertReadableSpace(context.filesDir)
    }

    @Test
    fun invalidDirectoryFailsInsteadOfReportingInventedCapacity() {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "probe-invalid-")
        try {
            try {
                AndroidPackageResourceProbe(context).snapshot(directory.resolve("missing"))
                fail("An invalid destination must not produce a successful snapshot")
            } catch (_: IllegalArgumentException) {
                // StatFs must keep its real lookup failure; no unlimited-space fallback.
            }
        } finally {
            assertTrue("Test directory cleanup failed", directory.toFile().deleteRecursively())
        }
    }

    private fun assertReadableSpace(parent: File) {
        val directory = Files.createTempDirectory(parent.toPath(), "probe-space-")
        try {
            val snapshot = AndroidPackageResourceProbe(context).snapshot(directory)
            val totalBytes = StatFs(directory.toString()).totalBytes
            assertTrue("Expected a real mounted filesystem", totalBytes > 0)
            assertTrue("Available space must not be negative", snapshot.availableBytes >= 0)
            assertTrue("Available space cannot exceed total space", snapshot.availableBytes <= totalBytes)
        } finally {
            assertTrue("Test directory cleanup failed", directory.toFile().deleteRecursively())
        }
    }
}
