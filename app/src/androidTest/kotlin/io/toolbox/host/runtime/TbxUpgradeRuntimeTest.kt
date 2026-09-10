package io.toolbox.host.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.toolbox.core.data.CatalogInstallAttempt
import io.toolbox.core.data.CatalogLifecycleRepository
import io.toolbox.core.data.CommitInstallOutcome
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.PermissionGrant
import io.toolbox.host.HostImportResult
import io.toolbox.host.ProductionHostPackageOperations
import io.toolbox.host.permissions.PermissionMutationResult
import io.toolbox.tool.runtime.RpcValue
import io.toolbox.tool.runtime.RuntimeCreationPermitResult
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeIdentity
import io.toolbox.tool.runtime.RuntimePermitProvider
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import io.toolbox.tool.runtime.RuntimeProfileManager
import io.toolbox.tool.runtime.RuntimeIsolationMode
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TbxUpgradeRuntimeTest {
    @Test
    fun liveBridgeLoginSurvivesUpdateSameVersionAndProfileReuse() = runBlocking(Dispatchers.IO) {
        val f = UpgradeFixture()
        try {
            f.install(1)
            val oldPage = f.page()
            f.login(oldPage)
            val original = f.envelope.read()
            assertNotNull(original)
            val dedicated = RuntimeProfileManager(f.application.filesDir).providerCapabilities()
                .preferredIsolationMode == RuntimeIsolationMode.DEDICATED_PROFILE
            if (dedicated) assertEquals(true, evaluate(oldPage, "localStorage.setItem('fixture-browser-data','keep');true"))
            val origin = RuntimeIdentity.origin(f.toolId)
            val profile = RuntimeIdentity.profileName(f.toolId)
            f.install(2)
            val newPage = f.page()
            assertNotSame(oldPage, newPage)
            f.assertLogin(newPage, "2")
            assertEquals(original, f.envelope.read())
            if (dedicated) assertEquals("keep", evaluate(newPage, "localStorage.getItem('fixture-browser-data')"))
            f.install(2, "same-version-new-bytes")
            val sameVersionPage = f.page()
            assertNotSame(newPage, sameVersionPage)
            f.assertLogin(sameVersionPage, "same-version-new-bytes")
            assertEquals(original, f.envelope.read())
            assertTrue(f.hasKey())
            assertEquals(origin, RuntimeIdentity.origin(f.toolId))
            assertEquals(profile, RuntimeIdentity.profileName(f.toolId))
            if (dedicated) assertEquals("keep", evaluate(sameVersionPage, "localStorage.getItem('fixture-browser-data')"))
        } finally { f.close() }
    }

    @Test
    fun oldThreeLineReplacementMarkerReplayPreservesLaterDataAndRunningPage() = runBlocking(Dispatchers.IO) {
        val f = UpgradeFixture()
        try {
            f.install(1)
            f.login(f.page())
            val original = f.envelope.read()
            val flaky = ProductionHostPackageOperations(f.application, f.stores.repositories,
                f.dependencies.runtimeDataCleaner, f.dependencies.backgroundOperations,
                invalidateToolIcon = { error("injected post-commit invalidation failure") })
            f.install(2, installer = flaky)
            val markerDirectory = File(f.application.filesDir, "miniapps/.lifecycle/replacement-cleanup")
            val marker = markerDirectory.listFiles().orEmpty().single { it.readLines().firstOrNull() == f.toolId }
            // This is also the legacy on-disk format; no special migration or destructive callback.
            assertEquals(listOf(f.toolId, "1", "2"), marker.readLines())
            assertEquals(original, f.envelope.read())
            val page = f.page()
            f.assertLogin(page, "2")
            val secure = createRuntimeSecureStorageHandler(f.toolId, f.stores.repositories.keyValues, { 20 }, { true })
            secure.set("after-update", RpcValue.StringValue("synthetic-later-secret"))
            val later = f.envelope.read()
            repeat(2) { f.dependencies.recoverPendingPackageMutations() }
            assertEquals(later, f.envelope.read())
            assertEquals(RpcValue.StringValue("synthetic-later-secret"), secure.get("after-update"))
            assertSame(page, (f.dependencies.runtimeSessions.state(f.toolId).value as RuntimeUiState.Ready).webView)
            assertFalse(marker.exists())
        } finally { f.close() }
    }

    @Test
    fun actualHostCommitFailureRollsBackHigherAndSameVersionBeforeReopening() = runBlocking(Dispatchers.IO) {
        val f = UpgradeFixture()
        try {
            f.install(1)
            f.login(f.page())
            val old = f.stores.repositories.catalog.observeTool(f.toolId).first()
            val original = f.envelope.read()
            val failing = object : CatalogLifecycleRepository by f.stores.repositories.lifecycle {
                override suspend fun commitInstall(attempt: CatalogInstallAttempt): DataResult<CommitInstallOutcome> =
                    DataResult.Failure.StorageFailure("injected commit failure")
            }
            val packages = ProductionHostPackageOperations(f.application, f.stores.repositories.copy(lifecycle = failing),
                f.dependencies.runtimeDataCleaner, f.dependencies.backgroundOperations)
            for (version in listOf(2, 1)) {
                val candidate = packages.importPackage(f.bytes(version, "must-not-publish")) as HostImportResult.ConfirmationRequired
                assertTrue(packages.confirmImport(candidate.confirmation.id) is HostImportResult.Failed)
                assertEquals(old, f.stores.repositories.catalog.observeTool(f.toolId).first())
                assertEquals(original, f.envelope.read())
                f.assertLogin(f.page(), "1")
            }
        } finally { f.close() }
    }

    @Test
    fun versionReplacementDrainsStorageAndBlocksNewRuntimeAndConcurrentMutation() = runBlocking(Dispatchers.IO) {
        withTimeout(30_000) {
            val f = UpgradeFixture()
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            try {
                f.install(1)
                f.login(f.page())
                val original = f.envelope.read()
                val pending = f.packages.importPackage(f.bytes(2)) as HostImportResult.ConfirmationRequired
                val writer = async {
                    withRuntimeStorageAccess(f.toolId, ToolStorageNamespace.Secure, { true }) {
                        entered.complete(Unit)
                        resume.await()
                    }
                }
                entered.await()
                val update = async { f.packages.confirmImport(pending.confirmation.id) }
                // The real lease is acquired before waiting for the admitted storage operation.
                f.dependencies.runtimeSessions.state(f.toolId).first { it is RuntimeUiState.Loading }
                var blocked = false
                repeat(100) {
                    if (!blocked) {
                        val permit = f.dependencies.runtimePermitProvider.acquireRuntimePermit(f.toolId, false)
                        if (permit is RuntimeCreationPermitResult.Rejected) blocked = true
                        else kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
                            (permit as RuntimeCreationPermitResult.Ready).permit.close()
                        }
                        if (!blocked) delay(20)
                    }
                }
                assertTrue("Update must hold the runtime barrier", blocked)
                assertFalse(update.isCompleted)
                val busy = f.packages.importPackage(f.bytes(3)) as HostImportResult.Failed
                assertEquals("BUSY", busy.code)
                resume.complete(Unit)
                writer.await()
                assertTrue(update.await() is HostImportResult.Installed)
                assertEquals(original, f.envelope.read())
                f.assertLogin(f.page(), "2")
            } finally {
                resume.complete(Unit)
                f.close()
            }
        }
    }

    @Test
    fun releaseCancelsInFlightRuntimePreparationBeforeReplacement() = runBlocking(Dispatchers.IO) {
        val f = UpgradeFixture()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val sessions = RuntimeSessionManager(f.application, f.stores.repositories, f.dependencies.runtimePreparer,
            RuntimePermitProvider { _, _ ->
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }, { f.dependencies.runtimeBridgeProvider })
        try {
            f.install(1)
            sessions.openForeground(f.toolId)
            withTimeout(30_000) { entered.await(); sessions.releaseTool(f.toolId); cancelled.await() }
            assertEquals(RuntimeUiState.Loading, sessions.state(f.toolId).value)
            f.install(1, "replacement")
            assertEquals(RuntimeUiState.Loading, sessions.state(f.toolId).value)
        } finally { sessions.releaseTool(f.toolId); f.close() }
    }

    @Test
    fun missingKeyAndCorruptCiphertextNeverCreateAReplacementOrOverwriteOldRows() = runBlocking(Dispatchers.IO) {
        val f = UpgradeFixture()
        try {
            f.install(1)
            val secure = createRuntimeSecureStorageHandler(f.toolId, f.stores.repositories.keyValues, { 1 }, { true })
            secure.set("token", RpcValue.StringValue("synthetic-secret"))
            val original = f.envelope.read()!!
            val bad = org.json.JSONObject(original).put("ciphertext", "AQIDBA").toString()
            f.envelope.write(bad, 2)
            expectUnreadable { secure.get("token") }
            expectUnreadable { secure.set("token", RpcValue.StringValue("not-written")) }
            assertEquals(bad, f.envelope.read())
            assertTrue(f.hasKey())
            f.envelope.write(original, 3)
            f.keyStore().deleteEntry(f.alias)
            repeat(2) {
                expectUnreadable { secure.get("token") }
                expectUnreadable { secure.set("token", RpcValue.StringValue("not-written")) }
                assertFalse(f.hasKey())
                assertEquals(original, f.envelope.read())
            }
        } finally { f.close() }
    }

    @Test
    fun distinctToolCannotReadSecretsAndExplicitRevocationStillWipesThem() = runBlocking(Dispatchers.IO) {
        val a = UpgradeFixture()
        val b = UpgradeFixture()
        try {
            a.install(1); b.install(1, installer = a.packages)
            a.login(a.page())
            val other = createRuntimeSecureStorageHandler(b.toolId, a.stores.repositories.keyValues, { 1 }, { true })
            assertEquals(null, other.get("fixture.auth"))
            assertFalse(b.hasKey())
            assertEquals(PermissionMutationResult.Saved,
                a.dependencies.permissionMutations.submit(a.toolId, "storage.secure", false, 1).await())
            assertEquals(null, a.envelope.read())
            assertFalse(a.hasKey())
            assertEquals(RpcValue.StringValue("keep-settings"),
                StandardToolKvStorageHandler(a.toolId, a.stores.repositories.keyValues, { 1 }).get("fixture.settings"))
            assertEquals(PermissionMutationResult.Saved,
                a.dependencies.permissionMutations.submit(a.toolId, "storage.secure", true, 1).await())
            assertEquals(null, createRuntimeSecureStorageHandler(a.toolId, a.stores.repositories.keyValues, { 1 }, { true }).get("fixture.auth"))
        } finally { a.packages.deleteTool(b.toolId); a.close(); b.close() }
    }

    private suspend fun expectUnreadable(action: suspend () -> Unit) {
        try { action(); error("Unreadable credentials must fail closed") }
        catch (failure: RuntimeHandlerException) { assertEquals(RuntimeRpcErrorCode.INTERNAL_ERROR, failure.errorCode) }
    }
}
