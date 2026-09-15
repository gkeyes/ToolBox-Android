package io.toolbox.tool.packagekit.lifecycle

import io.toolbox.core.data.CatalogInstallAttempt
import io.toolbox.core.data.CatalogLifecycleRepository
import io.toolbox.core.data.CommitInstallOutcome
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.InstallTransactionRepository
import io.toolbox.tool.packagekit.fixtures.InMemoryCoreData
import io.toolbox.tool.packagekit.PackageInput
import io.toolbox.tool.packagekit.PackageResourceProbe
import io.toolbox.tool.packagekit.PackageResourceSnapshot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageImportCancellationTest {
    @Test
    fun cancellingSourceCopyInterruptsTheReadAndCleansPrivateFiles() = runBlocking {
        withRoot { root ->
            val data = InMemoryCoreData.create()
            val manager = manager(root, data)
            val control = PackageImportControl()
            val readStarted = CountDownLatch(1)
            val releaseRead = CountDownLatch(1)
            val input = object : PackageInput {
                override val displayName = "cancel.tbx"
                override fun openStream() = object : ByteArrayInputStream(packageBytes()) {
                    var reads = 0
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (++reads == 2) {
                            readStarted.countDown()
                            check(releaseRead.await(10, TimeUnit.SECONDS)) { "Cancellation did not interrupt the source read" }
                        }
                        return super.read(buffer, offset, minOf(length, 32))
                    }
                }
            }
            val attempt = async(Dispatchers.Default) { manager.importAndInstall(input, control = control) }
            try {
                assertTrue(readStarted.await(10, TimeUnit.SECONDS))
                assertTrue(control.requestCancel())
                assertEquals(PackageInstallResult.Cancelled, withTimeout(10_000) { attempt.await() })
                assertTrue(data.catalog.observeTools().first().isEmpty())
                assertClean(root)
            } finally {
                releaseRead.countDown()
            }
        }
    }

    @Test
    fun cancellingStageKeepsTheInstalledVersionAndRemovesTheTransaction() = runBlocking {
        withRoot { root ->
            val data = InMemoryCoreData.create()
            assertEquals(PackageInstallResult.Installed(ID, 1, false), manager(root, data).importAndInstall(input()))
            val control = PackageImportControl()
            var cancelledInStage = false
            var stageChecks = 0
            val probe = PackageResourceProbe { directory ->
                if (directory == root && hasStagedBundle(root) && ++stageChecks == 2) {
                    cancelledInStage = control.requestCancel() || cancelledInStage
                }
                PackageResourceSnapshot(Long.MAX_VALUE, false)
            }
            val manager = manager(root, data, probe = probe)

            val pending = manager.importAndInstall(input(2)) as PackageInstallResult.ConfirmationRequired
            assertEquals(PackageVersionConfirmationKind.UPDATE, pending.confirmation.kind)
            assertEquals(PackageInstallResult.Cancelled, manager.confirmInstall(pending.confirmation.id, control = control))
            assertTrue(cancelledInStage)
            assertEquals(1, data.catalog.observeTool(ID).first()?.currentVersion?.versionCode)
            assertTrue(data.installs.observeIncomplete().first().isEmpty())
            assertTrue(Files.exists(root.resolve("miniapps/$ID/versions/1/bundle/index.html")))
            assertFalse(Files.exists(root.resolve("miniapps/$ID/versions/2")))
            assertClean(root)
        }
    }

    @Test
    fun cancellationReportsCleanupFailureWhenTheTransactionCannotBeMarkedFailed() = runBlocking {
        withRoot { root ->
            val data = InMemoryCoreData.create()
            val originalManager = manager(root, data)
            originalManager.importAndInstall(input())
            val control = PackageImportControl()
            var stageChecks = 0
            val probe = PackageResourceProbe { directory ->
                if (directory == root && hasStagedBundle(root) && ++stageChecks == 2) assertTrue(control.requestCancel())
                PackageResourceSnapshot(Long.MAX_VALUE, false)
            }
            val failingTransactions = object : InstallTransactionRepository by data.installs {
                override suspend fun fail(transactionId: String, updatedAt: Long, failureCode: String): DataResult<Unit> =
                    DataResult.Failure.StorageFailure("forced cancellation cleanup failure")
            }
            val manager = manager(root, data, probe = probe, transactions = failingTransactions)

            val pending = manager.importAndInstall(input(2)) as PackageInstallResult.ConfirmationRequired
            val result = manager.confirmInstall(pending.confirmation.id, control = control)

            assertTrue(result is PackageInstallResult.Failed)
            assertEquals(PackageOperationFailureCode.CLEANUP_FAILURE, (result as PackageInstallResult.Failed).failure.code)
            assertEquals(1, data.catalog.observeTool(ID).first()?.currentVersion?.versionCode)
            assertEquals(1, data.installs.observeIncomplete().first().size)
            assertClean(root)
            assertEquals(PackageRecoveryResult.Recovered, originalManager.recoverPendingMutations())
            assertTrue(data.installs.observeIncomplete().first().isEmpty())
            assertEquals(1, data.catalog.observeTool(ID).first()?.currentVersion?.versionCode)
            assertClean(root)
        }
    }

    @Test
    fun cancellationDuringDirectoryScanExtractionAndIntegrityAlwaysCleansTheImport() = runBlocking {
        for (phase in listOf("directory", "extraction", "integrity")) {
            withRoot { root ->
                val data = InMemoryCoreData.create()
                val control = PackageImportControl()
                val bytes = packageBytes(includePayload = true)
                var afterExtractionChecks = 0
                var reached = false
                val probe = PackageResourceProbe { directory ->
                    val archive = directory.resolve("source.tbx")
                    val bundle = directory.resolve("bundle")
                    val payload = bundle.resolve("payload.data")
                    val integrity = bundle.resolve("integrity.json")
                    val shouldCancel = when (phase) {
                        "directory" -> Files.exists(archive) && Files.size(archive) == bytes.size.toLong() && !Files.exists(bundle)
                        "extraction" -> Files.exists(payload) && Files.size(payload) in 1 until PAYLOAD_SIZE.toLong()
                        // First check after extraction reads manifest, the next one enters the streaming integrity parser.
                        else -> Files.exists(integrity) && Files.size(integrity) > 0 && ++afterExtractionChecks == 2
                    }
                    if (shouldCancel) reached = control.requestCancel() || reached
                    PackageResourceSnapshot(Long.MAX_VALUE, false)
                }
                val input = object : PackageInput {
                    override val displayName = "phase.tbx"
                    override fun openStream() = ByteArrayInputStream(bytes)
                }
                val result = manager(root, data, probe = probe).importAndInstall(input, control = control)

                assertTrue("Cancellation hook not reached in $phase", reached)
                assertEquals("Unexpected result in $phase", PackageInstallResult.Cancelled, result)
                assertTrue(data.catalog.observeTools().first().isEmpty())
                assertTrue(data.installs.observeIncomplete().first().isEmpty())
                assertClean(root)
            }
        }
    }

    @Test
    fun cancellingBeforeConfirmationStartsDiscardsItsRetainedPackage() = runBlocking {
        withRoot { root ->
            val data = InMemoryCoreData.create()
            val manager = manager(root, data)
            manager.importAndInstall(input())
            val confirmation = manager.importAndInstall(input()) as PackageInstallResult.ConfirmationRequired
            val control = PackageImportControl()
            assertTrue(control.requestCancel())

            assertEquals(PackageInstallResult.Cancelled, manager.confirmInstall(confirmation.confirmation.id, control = control))
            assertEquals(1, data.catalog.observeTool(ID).first()?.currentVersion?.versionCode)
            assertClean(root)
            val expired = manager.confirmInstall(confirmation.confirmation.id) as PackageInstallResult.Failed
            assertEquals(PackageOperationFailureCode.CONFIRMATION_EXPIRED, expired.failure.code)
        }
    }

    @Test
    fun cancellationAtCommitBoundaryReturnsTheActualSuccessOrRollback() = runBlocking {
        for (rejectCommit in listOf(false, true)) {
            withRoot { root ->
                val data = InMemoryCoreData.create()
                manager(root, data).importAndInstall(input())
                val control = PackageImportControl()
                var commitReached = false
                var replacementBarrierHeld = false
                val cleanup = object : ToolStateCleanup {
                    override suspend fun <T> withVersionReplacement(
                        toolId: String,
                        previousVersionCode: Int,
                        nextVersionCode: Int,
                        action: suspend () -> T,
                    ): T {
                        replacementBarrierHeld = true
                        return try {
                            action()
                        } finally {
                            assertClean(root)
                            replacementBarrierHeld = false
                        }
                    }

                    override suspend fun afterVersionReplacement(toolId: String, previousVersionCode: Int, nextVersionCode: Int) {
                        assertTrue(replacementBarrierHeld)
                    }

                    override suspend fun afterUninstall(toolId: String) = Unit
                }
                val lifecycle = object : CatalogLifecycleRepository by data.lifecycle {
                    override suspend fun commitInstall(attempt: CatalogInstallAttempt): DataResult<CommitInstallOutcome> {
                        commitReached = true
                        assertTrue("The runtime/storage barrier must cover commit", replacementBarrierHeld)
                        assertEquals(PackageImportPhase.COMMITTING, control.phase.value)
                        assertFalse("The commit boundary must close cancellation", control.requestCancel())
                        return if (rejectCommit) DataResult.Failure.StorageFailure("forced") else data.lifecycle.commitInstall(attempt)
                    }
                }
                val manager = manager(root, data, lifecycle)
                val confirmation = manager.importAndInstall(input()) as PackageInstallResult.ConfirmationRequired
                val result = manager.confirmInstall(confirmation.confirmation.id, cleanup = cleanup, control = control)

                assertTrue(commitReached)
                assertFalse(replacementBarrierHeld)
                if (rejectCommit) assertTrue(result is PackageInstallResult.Failed)
                else assertEquals(PackageInstallResult.Installed(ID, 1, true), result)
                assertEquals(1, data.catalog.observeTool(ID).first()?.currentVersion?.versionCode)
                assertTrue(Files.exists(root.resolve("miniapps/$ID/versions/1/bundle/index.html")))
                assertTrue(data.installs.observeIncomplete().first().isEmpty())
                assertClean(root)
            }
        }
    }

    private fun manager(
        root: Path,
        data: io.toolbox.core.data.CoreDataRepositories,
        lifecycle: CatalogLifecycleRepository = data.lifecycle,
        probe: PackageResourceProbe = PackageResourceProbe { PackageResourceSnapshot(Long.MAX_VALUE, false) },
        transactions: InstallTransactionRepository = data.installs,
    ) = ToolPackageManagers.create(
        privateFilesDirectory = root.toFile(),
        catalog = data.catalog,
        lifecycle = lifecycle,
        transactions = transactions,
        resourceProbe = probe,
    )

    private fun input(version: Int = 1) = object : PackageInput {
        override val displayName = "fixture.tbx"
        override fun openStream() = ByteArrayInputStream(packageBytes(version))
    }

    private fun packageBytes(version: Int = 1, includePayload: Boolean = false): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            val files = linkedMapOf(
                "manifest.json" to """{"schemaVersion":1,"id":"$ID","name":"Fixture","version":"1.0.$version","versionCode":$version,"entry":"index.html","apiVersion":"1.0","minHostVersion":"0.2.0","permissions":[],"securityProfile":"strict"}""".toByteArray(),
                "index.html" to "<!doctype html><html><body>fixture $version</body></html>".toByteArray(),
            )
            if (includePayload) {
                files["payload.data"] = ByteArray(PAYLOAD_SIZE) { 7 }
                val hashes = files.entries.joinToString(",") { (path, bytes) ->
                    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    "\"$path\":\"$hash\""
                }
                files["integrity.json"] = """{"schemaVersion":1,"algorithm":"SHA-256","files":{$hashes}}""".toByteArray()
            }
            files.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content)
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    private suspend fun withRoot(block: suspend (Path) -> Unit) {
        val root = Files.createTempDirectory("package-cancellation")
        try {
            block(root)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun assertClean(root: Path) {
        for (relative in listOf(".imports", ".staging", ".lifecycle/replacement-cleanup", ".lifecycle/replacement-backups")) {
            val path = root.resolve("miniapps/$relative")
            assertTrue("Residue in $relative", !Files.exists(path) || Files.list(path).use { it.findAny().isEmpty })
        }
    }

    private fun hasStagedBundle(root: Path): Boolean {
        val staging = root.resolve("miniapps/.staging")
        return Files.isDirectory(staging) && Files.list(staging).use { paths ->
            paths.anyMatch { Files.isDirectory(it.resolve("bundle")) }
        }
    }

    private companion object {
        const val ID = "io.toolbox.cancellationfixture"
        const val PAYLOAD_SIZE = 32 * 1024
    }
}
