package io.toolbox.tool.packagekit

import io.toolbox.core.data.PermissionGrant
import io.toolbox.tool.packagekit.lifecycle.InstallLifecycleResult
import io.toolbox.tool.packagekit.lifecycle.LifecycleFailure
import io.toolbox.tool.packagekit.lifecycle.LifecycleFailureCode
import io.toolbox.tool.packagekit.lifecycle.RecoveryLifecycleResult
import io.toolbox.tool.packagekit.lifecycle.RollbackLifecycleResult
import io.toolbox.tool.packagekit.lifecycle.ToolPackageLifecycle
import io.toolbox.tool.packagekit.lifecycle.ToolPackageStartupRecoveries
import io.toolbox.tool.packagekit.lifecycle.ToolPackageStartupRecoveryResult
import io.toolbox.tool.packagekit.lifecycle.UninstallLifecycleResult
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import kotlin.io.path.inputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageInspectorTest {
    @Test
    fun durableInspectionRecoveryIsBoundedExclusiveAndCleansUnlockedResidue() = runBlocking {
        val sessions = Files.createTempDirectory("tbx-resume-sessions")
        val sourceOpenCount = AtomicInteger()
        val receiptSyncEntered = CountDownLatch(1)
        val allowReceiptSync = CountDownLatch(1)
        try {
            val originalInspector = ToolPackageInspectors.create(sessions)
            val original = originalInspector.inspect(object : PackageInput {
                override val displayName = "durable.tbx"
                override fun openStream(): InputStream {
                    sourceOpenCount.incrementAndGet()
                    return PackageTestFixtures.validUnsigned().inputStream()
                }
            }) as InspectionResult.Inspected

            val restarted = ToolPackageInspectors.create(sessions)
            val claimedRoot = sessions.resolve(".claimed")
            Files.createDirectory(claimedRoot)
            Files.move(
                sessions.resolve(original.inspection.sessionId),
                claimedRoot.resolve(original.inspection.sessionId),
                StandardCopyOption.ATOMIC_MOVE,
            )
            val pendingJournalRecovery = recoverStartup(
                restarted,
                RecoveryLifecycleResult.Pending(
                    LifecycleFailure(LifecycleFailureCode.RECOVERY_REQUIRED, "Injected pending journal"),
                ),
            )
            assertTrue(pendingJournalRecovery is ToolPackageStartupRecoveryResult.Pending)
            assertTrue(Files.isDirectory(claimedRoot.resolve(original.inspection.sessionId)))
            assertEquals(false, Files.exists(sessions.resolve(original.inspection.sessionId)))

            val coldRecovery = recoveredInspections(restarted)
            assertEquals(listOf(original.inspection), coldRecovery.inspections)
            assertTrue(Files.isDirectory(sessions.resolve(original.inspection.sessionId)))
            assertEquals(false, Files.exists(claimedRoot.resolve(original.inspection.sessionId)))
            assertEquals(
                original.inspection,
                (restarted.resume(original.inspection.sessionId) as ResumeInspectionResult.Resumed).inspection,
            )
            assertEquals(listOf(original.inspection), recoveredInspections(restarted).inspections)
            assertEquals(1, sourceOpenCount.get())

            val interruptedInspector = DefaultPackageInspector(
                sessionRoot = sessions,
                recoveryScanHook = RecoveryScanHook { throw java.io.InterruptedIOException("injected scan interrupt") },
            )
            val scanInterruption = runCatching { recoveredInspections(interruptedInspector) }.exceptionOrNull()
            assertTrue(scanInterruption is CancellationException)
            assertEquals(
                original.inspection,
                (restarted.resume(original.inspection.sessionId) as ResumeInspectionResult.Resumed).inspection,
            )

            val inspecting = DefaultPackageInspector(
                sessionRoot = sessions,
                receiptDirectorySync = ReceiptDirectorySync {
                    receiptSyncEntered.countDown()
                    check(allowReceiptSync.await(5, TimeUnit.SECONDS)) { "Timed out waiting to publish receipt" }
                },
            )
            val inFlight = async(Dispatchers.Default) {
                inspecting.inspect(BytePackageInput("in-flight.tbx", PackageTestFixtures.validUnsigned()))
            }
            assertTrue(receiptSyncEntered.await(5, TimeUnit.SECONDS))
            val whileBusy = recoveredInspections(restarted)
            assertEquals(1, whileBusy.busySessionCount)
            assertEquals(listOf(original.inspection), whileBusy.inspections)
            allowReceiptSync.countDown()
            assertTrue(inFlight.await() is InspectionResult.Inspected)

            val corrupt = originalInspector.inspect(
                BytePackageInput("corrupt.tbx", PackageTestFixtures.validUnsigned()),
            ) as InspectionResult.Inspected
            Files.write(
                sessions.resolve(corrupt.inspection.sessionId).resolve(VerifiedInspectionReceipts.FILE_NAME),
                byteArrayOf(0x7b),
            )
            val incompleteId = UUID.randomUUID().toString()
            Files.createDirectory(sessions.resolve(incompleteId))
            val recovered = recoveredInspections(restarted)
            assertEquals(2, recovered.cleanedResidueCount)
            assertEquals(
                setOf(PackageRejectionCode.RECEIPT_INVALID, PackageRejectionCode.RECEIPT_MISSING),
                recovered.issues.map { it.rejection.code }.toSet(),
            )
            assertTrue(recovered.issues.all { it.residueRemoved })

            recovered.inspections.forEach { restarted.discard(it.sessionId) }
            repeat(33) { Files.createDirectory(sessions.resolve(UUID.randomUUID().toString())) }
            val bounded = recoveredInspections(restarted)
            assertTrue(bounded.truncated)
            assertTrue(bounded.cleanedResidueCount <= 32)
            val remaining = recoveredInspections(restarted)
            assertEquals(1, remaining.cleanedResidueCount)
            assertEquals(false, remaining.truncated)
            PackageTestFixtures.assertDirectoryEmpty(sessions)
        } finally {
            allowReceiptSync.countDown()
            PackageTestFixtures.deleteTree(sessions)
        }
    }

    @Test
    fun completedInspectionCanBeClaimedExactlyOnceWithoutReopeningInput() = runBlocking {
        val sessions = Files.createTempDirectory("tbx-claim-sessions")
        val openCount = AtomicInteger()
        val cleanupEntered = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        try {
            val inspector = ToolPackageInspectors.create(sessions)
            val result = inspector.inspect(object : PackageInput {
                override val displayName = "counted.tbx"
                override fun openStream(): InputStream {
                    openCount.incrementAndGet()
                    return PackageTestFixtures.validUnsigned().inputStream()
                }
            })
            val inspection = (result as InspectionResult.Inspected).inspection
            val start = CompletableDeferred<Unit>()
            val claims = List(2) {
                async(Dispatchers.Default) {
                    start.await()
                    inspector.claimInspectionSession(inspection.sessionId)
                }
            }
            start.complete(Unit)
            val claimResults = claims.awaitAll()
            val claimed = claimResults.filterIsInstance<InspectionSessionClaimResult.Claimed>().single()
            assertEquals(1, claimResults.count { it == InspectionSessionClaimResult.AlreadyClaimed })
            assertEquals(1, openCount.get())
            assertTrue(Files.isDirectory(claimed.lease.bundleDirectory))
            assertEquals(DiscardResult.NotFound, inspector.discard(inspection.sessionId))
            assertTrue(Files.isDirectory(claimed.lease.bundleDirectory))
            assertEquals(ClaimYieldResult.Yielded, claimed.lease.yieldOwnership())

            val recoveredInspector = DefaultPackageInspector(
                sessionRoot = sessions,
                terminalCleanupHook = TerminalCleanupHook {
                    cleanupEntered.countDown()
                    check(allowCleanup.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release terminal cleanup" }
                },
            )
            val recovered = recoveredInspector.claimInspectionSession(inspection.sessionId)
                as InspectionSessionClaimResult.Claimed
            assertTrue(Files.isDirectory(recovered.lease.bundleDirectory))
            assertEquals(inspection, recovered.lease.receipt.inspection)
            assertEquals(
                InspectionSessionClaimResult.AlreadyClaimed,
                inspector.claimInspectionSession(inspection.sessionId),
            )
            assertEquals(1, openCount.get())
            val cleanupResult = async(Dispatchers.Default) { recovered.lease.cleanup() }
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
            val observingInspector = ToolPackageInspectors.create(sessions)
            assertEquals(
                InspectionSessionClaimResult.AlreadyClaimed,
                observingInspector.claimInspectionSession(inspection.sessionId),
            )
            val busyDiscard = observingInspector.discard(inspection.sessionId) as DiscardResult.Failed
            assertEquals(PackageRejectionCode.CLEANUP_FAILED, busyDiscard.rejection.code)
            assertTrue(busyDiscard.rejection.detail.contains("cleanup is in progress"))
            allowCleanup.countDown()
            assertEquals(DiscardResult.Discarded, cleanupResult.await())
            assertEquals(DiscardResult.Discarded, recovered.lease.discard())
            assertEquals(
                InspectionSessionClaimResult.Consumed,
                recoveredInspector.claimInspectionSession(inspection.sessionId),
            )
            PackageTestFixtures.assertDirectoryEmpty(sessions)

            val incompleteClaimId = UUID.randomUUID().toString()
            Files.createDirectories(sessions.resolve(".claimed").resolve(incompleteClaimId))
            val incomplete = recoveredInspector.claimInspectionSession(incompleteClaimId)
                as InspectionSessionClaimResult.Failed
            assertEquals(PackageRejectionCode.SESSION_IO_FAILED, incomplete.rejection.code)
            PackageTestFixtures.assertDirectoryEmpty(sessions)

            val disposingId = UUID.randomUUID().toString()
            Files.createDirectories(sessions.resolve(".disposing").resolve(disposingId).resolve("bundle"))
            val failingInspector = DefaultPackageInspector(
                sessionRoot = sessions,
                terminalCleanupHook = TerminalCleanupHook { throw IOException("injected cleanup failure") },
            )
            val cleanupFailure = failingInspector.claimInspectionSession(disposingId)
                as InspectionSessionClaimResult.Failed
            assertEquals(PackageRejectionCode.CLEANUP_FAILED, cleanupFailure.rejection.code)
            assertTrue(Files.exists(sessions.resolve(".disposing").resolve(disposingId)))
            val restartedInspector = ToolPackageInspectors.create(sessions)
            assertEquals(
                InspectionSessionClaimResult.Consumed,
                restartedInspector.claimInspectionSession(disposingId),
            )
            PackageTestFixtures.assertDirectoryEmpty(sessions)

            val addedFileInspector = ToolPackageInspectors.create(sessions)
            val addedFileInspection = (
                addedFileInspector.inspect(BytePackageInput("added-file.tbx", PackageTestFixtures.validUnsigned()))
                    as InspectionResult.Inspected
                ).inspection
            Files.write(
                sessions.resolve(addedFileInspection.sessionId).resolve("bundle").resolve("injected.js"),
                "injected".toByteArray(),
            )
            val addedFileFailure = addedFileInspector.claimInspectionSession(addedFileInspection.sessionId)
                as InspectionSessionClaimResult.Failed
            assertEquals(PackageRejectionCode.RECEIPT_TREE_MISMATCH, addedFileFailure.rejection.code)
            PackageTestFixtures.assertDirectoryEmpty(sessions)

            for ((name, expected, corruptReceipt) in listOf(
                Triple("missing-receipt", PackageRejectionCode.RECEIPT_MISSING, false),
                Triple("corrupt-receipt", PackageRejectionCode.RECEIPT_INVALID, true),
            )) {
                val receiptInspector = ToolPackageInspectors.create(sessions)
                val receiptInspection = (
                    receiptInspector.inspect(BytePackageInput("$name.tbx", PackageTestFixtures.validUnsigned()))
                        as InspectionResult.Inspected
                    ).inspection
                val receiptPath = sessions.resolve(receiptInspection.sessionId).resolve(VerifiedInspectionReceipts.FILE_NAME)
                if (corruptReceipt) Files.write(receiptPath, byteArrayOf(0x7b)) else Files.delete(receiptPath)
                val receiptFailure = receiptInspector.claimInspectionSession(receiptInspection.sessionId)
                    as InspectionSessionClaimResult.Failed
                assertEquals(expected, receiptFailure.rejection.code)
                PackageTestFixtures.assertDirectoryEmpty(sessions)
            }
        } finally {
            allowCleanup.countDown()
            PackageTestFixtures.deleteTree(sessions)
        }
    }

    @Test
    fun positionCalculatorInspection() {
        val sessions = Files.createTempDirectory("tbx-example-sessions")
        try {
            val archive = findExampleArchive()
            val inspector = ToolPackageInspectors.create(sessions)
            val result = inspectSynchronously(
                inspector,
                object : PackageInput {
                    override val displayName = "position-calculator.tbx"
                    override fun openStream() = archive.inputStream()
                },
            )

            val inspection = (result as InspectionResult.Inspected).inspection
            assertTrue(inspection.installable)
            assertEquals("io.toolbox.positioncalculator", inspection.manifest.id)
            assertEquals("仓位", inspection.manifest.shortName)
            assertEquals("根据账户资金、入场价和止损价计算风险仓位。", inspection.manifest.description)
            assertEquals("1.0.0", inspection.manifest.version)
            assertEquals(1, inspection.manifest.versionCode)
            assertEquals("index.html", inspection.manifest.entry)
            assertEquals(listOf("storage", "clipboard.write", "haptics"), inspection.manifest.permissions.map { it.name })
            assertEquals(SecurityProfile.STRICT, inspection.manifest.securityProfile)
            assertEquals(listOf("计算", "投资"), inspection.manifest.categories)
            assertEquals(ManifestOrientation.PORTRAIT, inspection.manifest.ui.orientation)
            assertEquals(false, inspection.manifest.ui.allowFullscreen)
            assertEquals(ManifestStatusBarStyle.AUTO, inspection.manifest.ui.statusBarStyle)
            assertEquals(true, inspection.manifest.ui.showHostToolbar)
            assertEquals(2_097_152, inspection.manifest.limits.storageBytes)
            assertEquals(262_144, inspection.manifest.limits.maxBridgePayloadBytes)
            assertEquals(SignatureState.UNSIGNED, inspection.signature.state)
            assertEquals(6, inspection.archive.fileCount)
            assertEquals(7_469L, inspection.archive.extractedBytes)
            assertTrue(inspection.archive.files.containsAll(listOf("manifest.json", "index.html", "integrity.json")))
            assertTrue(inspection.riskFindings.isEmpty())
            assertEquals(DiscardResult.Discarded, discardSynchronously(inspector, inspection.sessionId))
            assertEquals(DiscardResult.NotFound, discardSynchronously(inspector, inspection.sessionId))
            PackageTestFixtures.assertDirectoryEmpty(sessions)
        } finally {
            PackageTestFixtures.deleteTree(sessions)
        }
    }

    @Test
    fun cancellationAndSessionRootFailureTerminateWithoutInspectionResidue() = runBlocking {
        val sessions = Files.createTempDirectory("tbx-cancel-sessions")
        val started = CountDownLatch(1)
        try {
            val job = launch(Dispatchers.Default) {
                ToolPackageInspectors.create(sessions).inspect(object : PackageInput {
                    override val displayName = "blocking.tbx"
                    override fun openStream() = object : InputStream() {
                        override fun read(): Int = waitUntilInterrupted()
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = waitUntilInterrupted()

                        private fun waitUntilInterrupted(): Int {
                            started.countDown()
                            try {
                                Thread.sleep(Long.MAX_VALUE)
                            } catch (error: InterruptedException) {
                                throw InterruptedIOException("cancelled")
                            }
                            return -1
                        }
                    }
                })
                error("Cancelled inspection returned a reusable result")
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            PackageTestFixtures.assertDirectoryEmpty(sessions)

            val syncAttempts = AtomicInteger()
            val persistenceInterruption = runCatching {
                DefaultPackageInspector(
                    sessionRoot = sessions,
                    receiptDirectorySync = ReceiptDirectorySync {
                        syncAttempts.incrementAndGet()
                        throw InterruptedIOException("receipt directory sync interrupted")
                    },
                ).inspect(BytePackageInput("persist-cancel.tbx", PackageTestFixtures.validUnsigned()))
            }.exceptionOrNull()
            assertTrue(persistenceInterruption is CancellationException)
            assertEquals(1, syncAttempts.get())
            PackageTestFixtures.assertDirectoryEmpty(sessions)

            val persistenceFailure = DefaultPackageInspector(
                sessionRoot = sessions,
                receiptDirectorySync = ReceiptDirectorySync { throw IOException("directory fsync failed") },
            ).inspect(BytePackageInput("persist-failure.tbx", PackageTestFixtures.validUnsigned()))
                as InspectionResult.Rejected
            assertEquals(PackageRejectionCode.RECEIPT_INVALID, persistenceFailure.rejection.code)
            PackageTestFixtures.assertDirectoryEmpty(sessions)

            val invalidRoot = Files.createTempFile("tbx-session-root", ".file")
            try {
                val result = ToolPackageInspectors.create(invalidRoot).inspect(
                    BytePackageInput("valid.tbx", PackageTestFixtures.validUnsigned()),
                )
                val rejection = result as InspectionResult.Rejected
                assertEquals(PackageRejectionCode.SESSION_IO_FAILED, rejection.rejection.code)
            } finally {
                Files.deleteIfExists(invalidRoot)
            }
        } finally {
            PackageTestFixtures.deleteTree(sessions)
        }
    }

    private fun findExampleArchive(): Path {
        val working = Path.of(System.getProperty("user.dir"))
        return sequenceOf(
            working.resolve("examples/position-calculator.tbx"),
            working.resolve("../examples/position-calculator.tbx"),
        ).firstOrNull(Files::isRegularFile) ?: error("examples/position-calculator.tbx not found from $working")
    }

    private suspend fun recoveredInspections(inspector: ToolPackageInspector): ResumableInspectionRecovery =
        (recoverStartup(inspector, RecoveryLifecycleResult.Recovered) as ToolPackageStartupRecoveryResult.Recovered)
            .inspections

    private suspend fun recoverStartup(
        inspector: ToolPackageInspector,
        lifecycleResult: RecoveryLifecycleResult,
    ): ToolPackageStartupRecoveryResult = ToolPackageStartupRecoveries
        .create(FixedRecoveryLifecycle(lifecycleResult), inspector)
        .recover()

    private class FixedRecoveryLifecycle(
        private val recoveryResult: RecoveryLifecycleResult,
    ) : ToolPackageLifecycle {
        override suspend fun install(
            inspectionSessionId: String,
            initialGrants: List<PermissionGrant>,
        ): InstallLifecycleResult = error("Install is outside this recovery test")

        override suspend fun rollback(toolId: String): RollbackLifecycleResult =
            error("Rollback is outside this recovery test")

        override suspend fun uninstall(toolId: String): UninstallLifecycleResult =
            error("Uninstall is outside this recovery test")

        override suspend fun recover(): RecoveryLifecycleResult = recoveryResult
    }
}
