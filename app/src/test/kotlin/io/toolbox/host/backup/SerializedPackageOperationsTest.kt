package io.toolbox.host.backup

import io.toolbox.core.data.DataMutationLock
import io.toolbox.host.*
import io.toolbox.tool.packagekit.PackageInput
import java.io.ByteArrayInputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SerializedPackageOperationsTest {
    @Test fun concurrentPackageActionsReturnBusyWithoutEnteringDelegateAndRetryAfterRelease() = runBlocking {
        val lock = DataMutationLock()
        var calls = 0
        val delegate = object : HostPackageOperations {
            override suspend fun importPackage(input: PackageInput): HostImportResult { calls++; return HostImportResult.Installed("fixture", "Fixture") }
            override suspend fun confirmImport(confirmationId: String): HostImportResult { calls++; return HostImportResult.Installed("fixture", "Fixture") }
            override suspend fun cancelImport(confirmationId: String): HostImportCancellationResult { calls++; return HostImportCancellationResult.Cancelled }
            override suspend fun deleteTool(toolId: String): HostDeleteResult { calls++; return HostDeleteResult.Deleted }
            override suspend fun installBundledExamples(): HostExampleInstallResult { calls++; return HostExampleInstallResult.Installed(1) }
            override suspend fun installedManifest(toolId: String): HostInstalledManifestResult = HostInstalledManifestResult.NotInstalled
        }
        val wrapped = SerializedPackageOperations(delegate, lock)
        val input = object : PackageInput {
            override val displayName = "fixture.tbx"
            override fun openStream() = ByteArrayInputStream(byteArrayOf())
        }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch { lock.run { entered.complete(Unit); release.await() } }
        entered.await()
        try {
            withTimeout(1_000) {
                assertEquals("BUSY", (wrapped.importPackage(input) as HostImportResult.Failed).code)
                assertEquals("BUSY", (wrapped.confirmImport("fixture") as HostImportResult.Failed).code)
                assertEquals("BUSY", (wrapped.cancelImport("fixture") as HostImportCancellationResult.Failed).code)
                assertEquals("BUSY", (wrapped.deleteTool("fixture") as HostDeleteResult.Failed).code)
                assertEquals("BUSY", (wrapped.installBundledExamples() as HostExampleInstallResult.Failed).code)
            }
            assertEquals(0, calls)
        } finally { release.complete(Unit); holder.join() }
        assertTrue(wrapped.importPackage(input) is HostImportResult.Installed)
        // Restore holds the outer lock and uses the ordinary package operations.
        lock.run { assertTrue(wrapped.confirmImport("fixture") is HostImportResult.Installed) }
        assertEquals(2, calls)
    }
}
