package io.toolbox.host.permissions

import io.toolbox.core.data.DataResult
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.PermissionGrantRepository
import io.toolbox.host.HostDeleteResult
import io.toolbox.host.HostExampleInstallResult
import io.toolbox.host.HostImportResult
import io.toolbox.host.HostInstalledManifest
import io.toolbox.host.HostInstalledManifestResult
import io.toolbox.host.HostManifestPermission
import io.toolbox.host.HostPackageOperations
import io.toolbox.host.HostPermissionSideEffects
import io.toolbox.tool.packagekit.PackageInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionMutationRunnerTest {
    @Test
    fun callerCancellationKeepsSameToolFifoWithoutBlockingOtherToolsOrRetainingTails() = runTest {
        val grants = MutationGrants()
        val allowFirstWrite = CompletableDeferred<Unit>()
        val allowLastCleanup = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        grants.beforeWrite = { grant ->
            calls += "write:${grant.toolId}:${grant.granted}"
            if (grant.toolId == "a" && grant.granted) allowFirstWrite.await()
        }
        val sideEffects = mutationSideEffects { toolId, _ ->
            calls += "cleanup:$toolId"
            if (toolId == "a") allowLastCleanup.await()
        }
        val runner = PermissionMutationRunner(MutationPackages(), grants, sideEffects, this)
        val first = runner.submit("a", "network", true, 1)
        val page = launch { first.await() }
        runCurrent()
        page.cancel()
        val second = runner.submit("a", "network", false, 1)
        val other = runner.submit("b", "network", true, 1)
        runCurrent()
        assertEquals(PermissionMutationResult.Saved, other.await())
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        assertEquals(listOf("write:a:true", "write:b:true"), calls)
        assertEquals(1, runner.activeToolCount)

        allowFirstWrite.complete(Unit)
        runCurrent()
        assertEquals(PermissionMutationResult.Saved, first.await())
        assertEquals(listOf("write:a:true", "write:b:true", "write:a:false", "cleanup:a"), calls)
        assertFalse(grants.observeGrants("a").first().single().granted)
        assertEquals(1, runner.activeToolCount)
        allowLastCleanup.complete(Unit)
        assertEquals(PermissionMutationResult.Saved, second.await())
        advanceUntilIdle()
        assertEquals(0, runner.activeToolCount)
    }

    @Test
    fun secureCleanupFailureCannotEnableOldSecretsAndDoesNotPoisonQueue() = runTest {
        val grants = MutationGrants()
        var fail = true
        var cleanups = 0
        val sideEffects = mutationSideEffects { _, _ ->
            assertTrue(grants.writes.isEmpty())
            cleanups += 1
            if (fail) error("private detail must not escape")
        }
        val runner = PermissionMutationRunner(MutationPackages(), grants, sideEffects, this)
        assertEquals(PermissionMutationResult.CleanupFailed, runner.submit("a", "storage.secure", true, 1).await())
        assertTrue(grants.writes.isEmpty())
        fail = false
        assertEquals(PermissionMutationResult.Saved, runner.submit("a", "storage.secure", true, 1).await())
        assertEquals(2, cleanups)
        assertEquals(listOf(true), grants.writes.map { it.granted })
        assertEquals(0, runner.activeToolCount)
    }

    @Test
    fun pendingWriteRechecksVersionAndDeclarationBeforeTouchingGrants() = runTest {
        val packages = MutationPackages()
        val grants = MutationGrants()
        val allowWrite = CompletableDeferred<Unit>()
        grants.beforeWrite = { if (it.granted) allowWrite.await() }
        val runner = PermissionMutationRunner(packages, grants, mutationSideEffects { _, _ -> }, this)
        val first = runner.submit("a", "network", true, 1)
        runCurrent()
        val queued = runner.submit("a", "network", false, 1)
        packages.version = 2
        allowWrite.complete(Unit)
        assertEquals(PermissionMutationResult.Saved, first.await())
        assertEquals(PermissionMutationResult.Outdated, queued.await())
        assertEquals(PermissionMutationResult.Outdated, runner.submit("a", "undeclared", true, 2).await())
        packages.installed = false
        assertEquals(PermissionMutationResult.Outdated, runner.submit("a", "network", true, 2).await())
        assertEquals(1, grants.writes.size)
        assertEquals(0, runner.activeToolCount)
    }

    @Test
    fun repositoryConflictDoesNotRunCleanupAndUnchangedGrantDoesNotRewrite() = runTest {
        val grants = MutationGrants()
        var cleanups = 0
        val runner = PermissionMutationRunner(
            MutationPackages(), grants, mutationSideEffects { _, _ -> cleanups += 1 }, this, now = { 42L },
        )
        assertEquals(PermissionMutationResult.Saved, runner.submit("a", "network", true, 1).await())
        assertEquals(PermissionMutationResult.Saved, runner.submit("a", "network", true, 1).await())
        assertEquals(listOf(PermissionGrant("a", "network", true, 42L)), grants.writes)
        grants.writeResult = DataResult.Failure.InvalidState("version_changed")
        assertEquals(PermissionMutationResult.WriteFailed, runner.submit("a", "network", false, 1).await())
        assertTrue(grants.observeGrants("a").first().single().granted)
        assertEquals(0, cleanups)
        assertEquals(0, runner.activeToolCount)
    }
}

private class MutationPackages : HostPackageOperations {
    var version = 1
    var installed = true

    override suspend fun installedManifest(toolId: String): HostInstalledManifestResult =
        if (!installed) HostInstalledManifestResult.NotInstalled else HostInstalledManifestResult.Found(
            HostInstalledManifest(
                toolId, "Permission fixture", version, "1.0.$version",
                listOf(HostManifestPermission("network", "网络", false), HostManifestPermission("storage.secure", "密钥", false)),
            ),
        )

    override suspend fun importPackage(input: PackageInput): HostImportResult = error("not used")
    override suspend fun confirmImport(confirmationId: String): HostImportResult = error("not used")
    override suspend fun cancelImport(confirmationId: String): io.toolbox.host.HostImportCancellationResult = error("not used")
    override suspend fun deleteTool(toolId: String): HostDeleteResult = error("not used")
    override suspend fun installBundledExamples(): HostExampleInstallResult = error("not used")
}

private class MutationGrants : PermissionGrantRepository {
    private val values = MutableStateFlow<List<PermissionGrant>>(emptyList())
    val writes = mutableListOf<PermissionGrant>()
    var beforeWrite: suspend (PermissionGrant) -> Unit = {}
    var writeResult: DataResult<Unit> = DataResult.Success(Unit)

    override fun observeGrants(toolId: String): Flow<List<PermissionGrant>> =
        values.map { stored -> stored.filter { it.toolId == toolId } }

    override suspend fun putForVersion(grant: PermissionGrant, expectedVersionCode: Int): DataResult<Unit> {
        beforeWrite(grant)
        if (writeResult is DataResult.Failure) return writeResult
        writes += grant
        values.value = values.value.filterNot { it.toolId == grant.toolId && it.capability == grant.capability } + grant
        return DataResult.Success(Unit)
    }

    override suspend fun put(grant: PermissionGrant): DataResult<Unit> = error("must use versioned write")
    override suspend fun revoke(toolId: String, capability: String): DataResult<Unit> = error("must use versioned write")
}

private fun mutationSideEffects(action: suspend (String, String) -> Unit) = object : HostPermissionSideEffects {
    override suspend fun onCapabilityDisabled(toolId: String, capability: String) = action(toolId, capability)
}
