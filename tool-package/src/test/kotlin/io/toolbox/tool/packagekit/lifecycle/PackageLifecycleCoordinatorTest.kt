package io.toolbox.tool.packagekit.lifecycle

import io.toolbox.core.data.CatalogLifecycleRepository
import io.toolbox.core.data.CatalogLifecycleSnapshot
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.GrantScope
import io.toolbox.core.data.GrantSource
import io.toolbox.core.data.GrantState
import io.toolbox.core.data.LaunchState
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.memory.InMemoryCoreData
import io.toolbox.tool.packagekit.BytePackageInput
import io.toolbox.tool.packagekit.ClaimYieldResult
import io.toolbox.tool.packagekit.ClaimedInspectionSession
import io.toolbox.tool.packagekit.DiscardResult
import io.toolbox.tool.packagekit.InspectionSessionClaimResult
import io.toolbox.tool.packagekit.InspectionSessionConsumer
import io.toolbox.tool.packagekit.InspectionResult
import io.toolbox.tool.packagekit.PackageRejection
import io.toolbox.tool.packagekit.PackageRejectionCode
import io.toolbox.tool.packagekit.PackageTestFixtures
import io.toolbox.tool.packagekit.ToolPackageInspector
import io.toolbox.tool.packagekit.ToolPackageInspectors
import io.toolbox.tool.packagekit.claimInspectionSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageLifecycleCoordinatorTest {
    @Test
    fun installUpdateRollbackAndUninstallKeepCatalogAuthoritative() = runBlocking {
        withFixture { fixture ->
            val first = fixture.inspect(versionCode = 1)
            assertEquals(
                InstallLifecycleResult.Committed(TOOL_ID, 1),
                fixture.lifecycle.install(first, emptyList()),
            )
            Files.delete(fixture.toolRoot.resolve("active.json"))
            assertEquals(
                InstallLifecycleResult.AlreadyCommitted(TOOL_ID, 1),
                fixture.lifecycle.install(first, emptyList()),
            )
            assertEquals(1, fixture.activeVersion())
            assertEquals(1, fixture.repositories.catalog.observeVersions(TOOL_ID).first().size)
            assertEquals(DataResult.Success(Unit), fixture.repositories.lifecycle.markActiveVersionStable(TOOL_ID, 1))

            val second = fixture.inspect(versionCode = 2)
            assertEquals(
                InstallLifecycleResult.Committed(TOOL_ID, 2),
                fixture.lifecycle.install(second, emptyList()),
            )
            val pending = fixture.repositories.catalog.observeVersions(TOOL_ID).first()
            assertEquals(LaunchState.PENDING, pending.single { it.versionCode == 2 }.launchState)
            assertEquals(2, fixture.activeVersion())

            assertEquals(RollbackLifecycleResult.RolledBack(TOOL_ID, 1), fixture.lifecycle.rollback(TOOL_ID))
            assertEquals(1, fixture.repositories.catalog.observeTool(TOOL_ID).first()?.activeVersionCode)
            assertEquals(1, fixture.activeVersion())

            assertEquals(UninstallLifecycleResult.Uninstalled(TOOL_ID), fixture.lifecycle.uninstall(TOOL_ID))
            assertNull(fixture.repositories.catalog.observeTool(TOOL_ID).first())
            assertFalse(Files.exists(fixture.toolRoot.resolve("versions")))
            assertFalse(Files.exists(fixture.toolRoot.resolve("active.json")))
        }
    }

    @Test
    fun lifecycleFailureAndCrashCutPointsRecoverWithoutMisreportingOrRepeatedCommits() = runBlocking {
        for (scenario in CrashScenario.entries) {
            withFixture { fixture ->
                when (scenario) {
                    CrashScenario.AFTER_VERSION_PUBLISH -> {
                        val session = fixture.inspect(1)
                        fixture.crashing(LifecycleFaultPoint.AfterVersionPublish).expectCancellation {
                            install(session, emptyList())
                        }
                        assertEquals(RecoveryLifecycleResult.Recovered, fixture.lifecycle.recover())
                        assertNull(fixture.repositories.catalog.observeTool(TOOL_ID).first())
                        assertEquals(
                            InstallLifecycleResult.Committed(TOOL_ID, 1),
                            fixture.lifecycle.install(session, emptyList()),
                        )
                    }
                    CrashScenario.AFTER_INSTALL_COMMIT -> {
                        val session = fixture.inspect(1)
                        fixture.crashing(LifecycleFaultPoint.AfterInstallCommit).expectCancellation {
                            install(session, emptyList())
                        }
                        assertEquals(
                            InstallLifecycleResult.AlreadyCommitted(TOOL_ID, 1),
                            fixture.lifecycle.install(session, emptyList()),
                        )
                        assertEquals(1, fixture.repositories.catalog.observeVersions(TOOL_ID).first().size)
                    }
                    CrashScenario.AFTER_ROLLBACK_COMMIT -> {
                        for (versionCode in 1..3) {
                            val session = fixture.inspect(versionCode)
                            assertTrue(fixture.lifecycle.install(session, emptyList()) is InstallLifecycleResult.Committed)
                            assertEquals(
                                DataResult.Success(Unit),
                                fixture.repositories.lifecycle.markActiveVersionStable(TOOL_ID, versionCode),
                            )
                        }
                        fixture.crashing(LifecycleFaultPoint.AfterRollbackCommit).expectCancellation {
                            rollback(TOOL_ID)
                        }
                        assertEquals(RecoveryLifecycleResult.Recovered, fixture.lifecycle.recover())
                        assertEquals(2, fixture.repositories.catalog.observeTool(TOOL_ID).first()?.activeVersionCode)
                        assertEquals(2, fixture.activeVersion())
                    }
                    CrashScenario.AFTER_UNINSTALL_COMMIT -> {
                        val session = fixture.inspect(1)
                        assertTrue(fixture.lifecycle.install(session, emptyList()) is InstallLifecycleResult.Committed)
                        fixture.crashing(LifecycleFaultPoint.AfterUninstallCommit).expectCancellation {
                            uninstall(TOOL_ID)
                        }
                        assertEquals(RecoveryLifecycleResult.Recovered, fixture.lifecycle.recover())
                        assertNull(fixture.repositories.catalog.observeTool(TOOL_ID).first())
                        assertFalse(Files.exists(fixture.toolRoot.resolve("versions")))
                    }
                    CrashScenario.COMMITTED_REPLAY_CANCELLATION -> {
                        val session = fixture.inspect(1)
                        assertEquals(
                            InstallLifecycleResult.Committed(TOOL_ID, 1),
                            fixture.lifecycle.install(session, emptyList()),
                        )
                        Files.delete(fixture.toolRoot.resolve("active.json"))
                        fixture.crashing(LifecycleFaultPoint.BeforeCommittedReplayCleanup).expectCancellation {
                            install(session, emptyList())
                        }
                        assertEquals(1, fixture.repositories.catalog.observeVersions(TOOL_ID).first().size)
                        assertEquals(1, fixture.repositories.catalog.observeTool(TOOL_ID).first()?.activeVersionCode)
                        assertFalse(Files.exists(fixture.toolRoot.resolve("active.json")))
                        assertEquals(
                            InstallLifecycleResult.AlreadyCommitted(TOOL_ID, 1),
                            fixture.lifecycle.install(session, emptyList()),
                        )
                        assertEquals(1, fixture.activeVersion())
                    }
                    CrashScenario.POST_CLAIM_PRE_JOURNAL_CANCELLATION -> {
                        val session = fixture.inspect(1)
                        fixture.withSnapshotCancellation().expectCancellation {
                            install(session, emptyList())
                        }
                        assertNull(fixture.repositories.catalog.observeTool(TOOL_ID).first())
                        assertTrue(fixture.repositories.catalog.observeVersions(TOOL_ID).first().isEmpty())
                        assertFalse(Files.exists(fixture.toolRoot))
                        assertFalse(Files.exists(fixture.files.resolve("miniapps/.staging")))
                        val journals = fixture.files.resolve("miniapps/.lifecycle/journals")
                        assertTrue(!Files.exists(journals) || Files.list(journals).use { it.findAny().isEmpty })
                        val reclaimed = fixture.claimForInstall(session)
                        assertEquals(ClaimYieldResult.Yielded, reclaimed.yieldOwnership())
                        assertEquals(
                            InstallLifecycleResult.Committed(TOOL_ID, 1),
                            fixture.lifecycle.install(session, emptyList()),
                        )
                    }
                    CrashScenario.PRECOMMIT_YIELD_FAILURE -> {
                        val session = fixture.inspect(1)
                        val captured = fixture.captureClaim(session)
                        val invalidGrant = PermissionGrant(
                            toolId = "io.toolbox.different",
                            permission = "storage",
                            state = GrantState.GRANTED,
                            scope = GrantScope.SESSION,
                            grantedAt = 1000L,
                            expiresAt = null,
                            source = GrantSource.INSTALL,
                        )
                        val result = fixture.withClaimOverride(captured, yieldFails = true)
                            .install(session, listOf(invalidGrant)) as InstallLifecycleResult.Failed
                        assertEquals(LifecycleFailureCode.RECOVERY_REQUIRED, result.reason.code)
                        assertTrue(result.reason.message.contains("injected yield failure"))
                        assertNull(fixture.repositories.catalog.observeTool(TOOL_ID).first())
                    }
                    CrashScenario.ACTIVE_POINTER_FAILURE -> {
                        val session = fixture.inspect(1)
                        val result = fixture.failing(LifecycleFaultPoint.BeforeActivePointerWrite)
                            .install(session, emptyList()) as InstallLifecycleResult.CommittedRecoveryPending
                        assertEquals(LifecycleFailureCode.RECOVERY_REQUIRED, result.reason.code)
                        assertFalse(Files.exists(fixture.toolRoot.resolve("active.json")))
                        assertEquals(RecoveryLifecycleResult.Recovered, fixture.lifecycle.recover())
                        assertEquals(1, fixture.activeVersion())
                    }
                    CrashScenario.CLAIM_CLEANUP_FAILURE -> {
                        val session = fixture.inspect(1)
                        val captured = fixture.claimForInstall(session)
                        val result = fixture.withClaimOverride(captured, cleanupFails = true)
                            .install(session, emptyList()) as InstallLifecycleResult.CommittedRecoveryPending
                        assertEquals(LifecycleFailureCode.RECOVERY_REQUIRED, result.reason.code)
                        assertEquals(ClaimYieldResult.Yielded, captured.yieldOwnership())
                        assertEquals(RecoveryLifecycleResult.Recovered, fixture.lifecycle.recover())
                        assertEquals(1, fixture.repositories.catalog.observeVersions(TOOL_ID).first().size)
                    }
                }
            }
        }
    }

    @Test
    fun corruptLifecycleJournalsFailClosedAndRemainAvailableForRecovery() = runBlocking {
        for (scenario in CorruptJournalScenario.entries) {
            withFixture { fixture ->
                val journals = fixture.files.resolve("miniapps/.lifecycle/journals")
                Files.createDirectories(journals)
                val contentId = UUID.randomUUID().toString()
                val fileId = if (scenario == CorruptJournalScenario.MISMATCHED_NAME) UUID.randomUUID().toString() else contentId
                val valid = """
                    schema=1
                    operationId=$contentId
                    kind=UNINSTALL
                    phase=PREPARED
                    toolId=$TOOL_ID
                    versionCode=
                    priorVersionCode=
                    sessionId=
                """.trimIndent() + "\n"
                val bytes = when (scenario) {
                    CorruptJournalScenario.MALFORMED -> "not-a-journal\n"
                    CorruptJournalScenario.DUPLICATE_KEY -> valid + "kind=INSTALL\n"
                    CorruptJournalScenario.MISMATCHED_NAME -> valid
                }
                val journal = journals.resolve("$fileId.journal")
                Files.write(journal, bytes.toByteArray())

                val result = fixture.lifecycle.recover() as RecoveryLifecycleResult.Pending

                assertEquals(LifecycleFailureCode.RECOVERY_REQUIRED, result.reason.code)
                assertTrue(Files.exists(journal))
                assertNull(fixture.repositories.catalog.observeTool(TOOL_ID).first())
            }
        }
    }

    @Test
    fun claimedSourceTreeMutationIsBlockedBeforeCatalogCommit() = runBlocking {
        for (scenario in SourceMutationScenario.entries) {
            withFixture { fixture ->
                val session = fixture.inspect(1)
                val claimed = fixture.claimForInstall(session)
                val bundle = claimed.bundleDirectory
                when (scenario) {
                    SourceMutationScenario.SYMLINK -> {
                        val external = fixture.root.resolve("external.js")
                        Files.write(external, "external".toByteArray())
                        Files.delete(bundle.resolve("app.js"))
                        Files.createSymbolicLink(bundle.resolve("app.js"), external)
                    }
                    SourceMutationScenario.SPECIAL_FILE -> {
                        Files.delete(bundle.resolve("app.js"))
                        val process = ProcessBuilder("mkfifo", bundle.resolve("app.js").toString())
                            .redirectErrorStream(true)
                            .start()
                        process.inputStream.readAllBytes()
                        assertEquals(0, process.waitFor())
                    }
                    SourceMutationScenario.EXTRA_FILE ->
                        Files.write(bundle.resolve("injected.js"), "injected".toByteArray())
                }

                val result = fixture.withExactClaim(claimed).install(session, emptyList()) as InstallLifecycleResult.Failed

                assertEquals(LifecycleFailureCode.FILE_INTEGRITY_MISMATCH, result.reason.code)
                assertNull(fixture.repositories.catalog.observeTool(TOOL_ID).first())
                assertFalse(Files.exists(fixture.toolRoot.resolve("versions/1")))
            }
        }
    }

    @Test
    fun preexistingVersionCollisionPreservesExistingBytesAndCatalog() = runBlocking {
        withFixture { fixture ->
            val session = fixture.inspect(1)
            val target = fixture.toolRoot.resolve("versions/1/bundle")
            Files.createDirectories(target)
            val sentinel = target.resolve("sentinel.bin")
            val original = byteArrayOf(1, 3, 3, 7)
            Files.write(sentinel, original)

            val result = fixture.lifecycle.install(session, emptyList())

            assertEquals(LifecycleFailureCode.FILE_COLLISION, (result as InstallLifecycleResult.Failed).reason.code)
            assertArrayEquals(original, Files.readAllBytes(sentinel))
            assertNull(fixture.repositories.catalog.observeTool(TOOL_ID).first())
            assertTrue(fixture.repositories.catalog.observeVersions(TOOL_ID).first().isEmpty())
        }
    }

    private suspend fun ToolPackageLifecycle.expectCancellation(block: suspend ToolPackageLifecycle.() -> Unit) {
        try {
            block()
            throw AssertionError("Expected injected lifecycle cancellation")
        } catch (_: CancellationException) {
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val root = Files.createTempDirectory("tbx-lifecycle")
        try {
            val sessions = root.resolve("sessions")
            val files = root.resolve("files")
            Files.createDirectories(sessions)
            Files.createDirectories(files)
            val repositories = InMemoryCoreData.create()
            val inspector = ToolPackageInspectors.create(sessions)
            val lifecycle = DefaultToolPackageLifecycle(files, inspector, repositories.lifecycle, now = { 1000L })
            block(Fixture(root, files, inspector, repositories, lifecycle))
        } finally {
            PackageTestFixtures.deleteTree(root)
        }
    }

    private data class Fixture(
        val root: Path,
        val files: Path,
        val inspector: io.toolbox.tool.packagekit.ToolPackageInspector,
        val repositories: io.toolbox.core.data.CoreDataRepositories,
        val lifecycle: ToolPackageLifecycle,
    ) {
        val toolRoot: Path = files.resolve("miniapps/$TOOL_ID")

        suspend fun inspect(versionCode: Int): String {
            val manifest = PackageTestFixtures.manifest().toString(Charsets.UTF_8)
                .replace("\"version\":\"1.0.0\"", "\"version\":\"1.0.$versionCode\"")
                .replace("\"versionCode\":1", "\"versionCode\":$versionCode")
                .toByteArray()
            val result = inspector.inspect(
                BytePackageInput("fixture-$versionCode.tbx", PackageTestFixtures.validUnsigned(manifestBytes = manifest)),
            )
            return (result as InspectionResult.Inspected).inspection.sessionId
        }

        fun crashing(point: LifecycleFaultPoint): ToolPackageLifecycle = DefaultToolPackageLifecycle(
            files,
            inspector,
            repositories.lifecycle,
            now = { 1000L },
            faultHook = LifecycleFaultHook { reached ->
                if (reached == point) throw CancellationException("injected $point")
            },
        )

        fun failing(point: LifecycleFaultPoint): ToolPackageLifecycle = DefaultToolPackageLifecycle(
            files,
            inspector,
            repositories.lifecycle,
            now = { 1000L },
            faultHook = LifecycleFaultHook { reached ->
                if (reached == point) throw java.io.IOException("injected $point")
            },
        )

        fun withSnapshotCancellation(): ToolPackageLifecycle = DefaultToolPackageLifecycle(
            files,
            inspector,
            SnapshotCancellingCatalog(repositories.lifecycle),
            now = { 1000L },
        )

        suspend fun captureClaim(sessionId: String): ClaimedInspectionSession {
            val lease = claimForInstall(sessionId)
            assertEquals(ClaimYieldResult.Yielded, lease.yieldOwnership())
            return lease
        }

        suspend fun claimForInstall(sessionId: String): ClaimedInspectionSession =
            (inspector.claimInspectionSession(sessionId) as InspectionSessionClaimResult.Claimed).lease

        fun withExactClaim(claimed: ClaimedInspectionSession): ToolPackageLifecycle =
            DefaultToolPackageLifecycle(
                files,
                ClaimOverrideInspector(inspector, claimed),
                repositories.lifecycle,
                now = { 1000L },
            )

        fun withClaimOverride(
            captured: ClaimedInspectionSession,
            yieldFails: Boolean = false,
            cleanupFails: Boolean = false,
        ): ToolPackageLifecycle {
            val replacement = ClaimedInspectionSession(
                sessionId = captured.sessionId,
                bundleDirectory = captured.bundleDirectory,
                receipt = captured.receipt,
                ioDispatcher = Dispatchers.Unconfined,
                discardAction = {
                    if (cleanupFails) {
                        DiscardResult.Failed(
                            PackageRejection(PackageRejectionCode.CLEANUP_FAILED, "injected cleanup failure"),
                        )
                    } else {
                        DiscardResult.Discarded
                    }
                },
                yieldAction = {
                    if (yieldFails) {
                        PackageRejection(PackageRejectionCode.CLEANUP_FAILED, "injected yield failure")
                    } else {
                        null
                    }
                },
            )
            val override = ClaimOverrideInspector(inspector, replacement)
            return DefaultToolPackageLifecycle(
                files,
                override,
                repositories.lifecycle,
                now = { 1000L },
            )
        }

        fun activeVersion(): Int {
            val json = Files.readString(toolRoot.resolve("active.json"))
            return Regex("\"versionCode\":(\\d+)").find(json)!!.groupValues[1].toInt()
        }
    }

    private enum class CrashScenario {
        AFTER_VERSION_PUBLISH,
        AFTER_INSTALL_COMMIT,
        AFTER_ROLLBACK_COMMIT,
        AFTER_UNINSTALL_COMMIT,
        COMMITTED_REPLAY_CANCELLATION,
        POST_CLAIM_PRE_JOURNAL_CANCELLATION,
        PRECOMMIT_YIELD_FAILURE,
        ACTIVE_POINTER_FAILURE,
        CLAIM_CLEANUP_FAILURE,
    }

    private enum class CorruptJournalScenario { MALFORMED, DUPLICATE_KEY, MISMATCHED_NAME }

    private enum class SourceMutationScenario { SYMLINK, SPECIAL_FILE, EXTRA_FILE }

    private class SnapshotCancellingCatalog(
        delegate: CatalogLifecycleRepository,
    ) : CatalogLifecycleRepository by delegate {
        override suspend fun snapshot(toolId: String): DataResult<CatalogLifecycleSnapshot> =
            throw CancellationException("injected post-claim catalog snapshot cancellation")
    }

    private class ClaimOverrideInspector(
        delegate: ToolPackageInspector,
        private val lease: ClaimedInspectionSession,
    ) : ToolPackageInspector by delegate, InspectionSessionConsumer {
        override suspend fun claimInspectionSession(sessionId: String): InspectionSessionClaimResult =
            if (sessionId == lease.sessionId) InspectionSessionClaimResult.Claimed(lease) else InspectionSessionClaimResult.NotFound
    }

    private companion object {
        const val TOOL_ID = "io.toolbox.fixture"
    }
}
