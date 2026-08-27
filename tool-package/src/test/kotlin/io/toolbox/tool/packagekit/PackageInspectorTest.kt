package io.toolbox.tool.packagekit

import java.io.InputStream
import java.io.InterruptedIOException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import kotlin.io.path.inputStream
import kotlinx.coroutines.CompletableDeferred
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
            assertEquals(7_172L, inspection.archive.extractedBytes)
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
}
