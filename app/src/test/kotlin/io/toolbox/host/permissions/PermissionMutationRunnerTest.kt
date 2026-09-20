package io.toolbox.host.permissions

import io.toolbox.core.data.BundleLocator
import io.toolbox.core.data.DataMutationLock
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.PermissionGrantRepository
import io.toolbox.host.HostPermissionSideEffects
import io.toolbox.host.backup.BackupRuntimeGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionMutationRunnerTest {
    @After fun resumeAdmission() { BackupRuntimeGate.resume() }

    @Test fun failedRevokeCanRetryCleanupWithoutWritingOrEnablingTheGrant() = runTest {
        val fixture = PermissionTestFixture(this)
        val retry = fixture.failRevocation()
        assertFalse(fixture.grant().granted)
        assertEquals(1, fixture.grantWrites.size)

        // The ordinary same-value operation remains a no-op; retry is explicit.
        assertEquals(PermissionMutationResult.Saved(retry.revokedGrant), fixture.runner.submit(
            PERMISSION_TOOL_ID, SECURE_CAPABILITY, false, retry.version, retry.revokedGrant,
        ).await())
        assertEquals(1, fixture.cleanupCalls.size)

        fixture.cleanup = {}
        assertEquals(PermissionMutationResult.Saved(retry.revokedGrant), fixture.runner.retryCleanup(retry).await())
        assertEquals(2, fixture.cleanupCalls.size)
        assertEquals(listOf(retry.revokedGrant), fixture.grantWrites)
        assertTrue(fixture.cleanupGrants.all { it?.granted == false })
    }

    @Test fun retryRejectsRemovedReplacedUndeclaredAndReauthorizedTargets() = runTest {
        val invalidations = listOf<Pair<String, (PermissionTestFixture) -> Unit>>(
            "uninstalled" to { it.installed.value = null },
            "updated" to { it.replaceVersion(it.initialVersion.copy(versionCode = 2, version = "2.0.0")) },
            "same version replacement" to { it.replaceVersion(it.initialVersion.copy(bundleLocator = BundleLocator("tools/fixture/replaced"))) },
            "new install identity" to { it.replaceVersion(it.initialVersion.copy(installedAt = 9L, integrityHash = "new-hash")) },
            "enabled grant" to { it.replaceGrant(it.grant().copy(granted = true, updatedAt = 9L)) },
            "new disabled grant" to { it.replaceGrant(it.grant().copy(updatedAt = 9L)) },
            "missing grant" to { it.storedGrants.value = emptyList() },
            "undeclared capability" to { it.capabilities = emptyList() },
        )
        for ((name, invalidate) in invalidations) {
            val fixture = PermissionTestFixture(this)
            val retry = fixture.failRevocation()
            invalidate(fixture)
            assertEquals(name, PermissionMutationResult.Outdated, fixture.runner.retryCleanup(retry).await())
            assertEquals(name, 1, fixture.cleanupCalls.size)
            assertEquals(name, 1, fixture.grantWrites.size)
        }
    }

    @Test fun retryWaitsInTheSameFifoAndRejectsAnEarlierQueuedRegrant() = runTest {
        val fixture = PermissionTestFixture(this)
        val retry = fixture.failRevocation()
        val finishCleanup = CompletableDeferred<Unit>(backgroundScope.coroutineContext[Job])
        fixture.cleanup = { finishCleanup.await() }
        val regrant = fixture.runner.submit(PERMISSION_TOOL_ID, SECURE_CAPABILITY, true, retry.version, retry.revokedGrant)
        runCurrent()
        val staleRetry = fixture.runner.retryCleanup(retry)
        runCurrent()
        assertFalse(regrant.isCompleted)
        assertFalse(staleRetry.isCompleted)
        assertEquals(2, fixture.cleanupCalls.size)
        assertFalse(fixture.grant().granted)

        finishCleanup.complete(Unit)
        assertTrue(regrant.await() is PermissionMutationResult.Saved)
        assertEquals(PermissionMutationResult.Outdated, staleRetry.await())
        assertTrue(fixture.grant().granted)
        assertEquals(2, fixture.cleanupCalls.size)
    }

    @Test fun restoreAdmissionRejectsRetryUntilResumed() = runTest {
        val fixture = PermissionTestFixture(this)
        val retry = fixture.failRevocation()
        BackupRuntimeGate.pauseAndDrain()
        assertEquals(PermissionMutationResult.WriteFailed, fixture.runner.retryCleanup(retry).await())
        assertEquals(1, fixture.cleanupCalls.size)
        assertFalse(fixture.grant().granted)
        BackupRuntimeGate.resume()
        fixture.cleanup = {}
        assertTrue(fixture.runner.retryCleanup(retry).await() is PermissionMutationResult.Saved)
    }

    @Test fun packageReplacementCannotEnterBetweenRetryValidationAndCleanup() = runTest {
        val fixture = PermissionTestFixture(this)
        val retry = fixture.failRevocation()
        val finishCleanup = CompletableDeferred<Unit>(backgroundScope.coroutineContext[Job])
        fixture.cleanup = { finishCleanup.await() }
        val operation = fixture.runner.retryCleanup(retry)
        runCurrent()
        assertFalse(fixture.packageLock.tryRun({ false }) {
            fixture.replaceVersion(fixture.initialVersion.copy(installedAt = 10L))
            true
        })
        assertEquals(retry.version, fixture.installed.value?.currentVersion)
        finishCleanup.complete(Unit)
        assertTrue(operation.await() is PermissionMutationResult.Saved)
        assertTrue(fixture.packageLock.tryRun({ false }) { true })
    }

    @Test fun sameMillisecondOffOnOffWritesInvalidateTheOriginalRetry() = runTest {
        val fixture = PermissionTestFixture(this)
        val oldRetry = fixture.failRevocation()
        fixture.cleanup = {}
        assertTrue(fixture.runner.submit(
            PERMISSION_TOOL_ID, SECURE_CAPABILITY, true, oldRetry.version, fixture.grant(),
        ).await() is PermissionMutationResult.Saved)
        assertTrue(fixture.runner.submit(
            PERMISSION_TOOL_ID, SECURE_CAPABILITY, false, oldRetry.version, fixture.grant(),
        ).await() is PermissionMutationResult.Saved)
        assertFalse(fixture.grant().granted)
        assertTrue(fixture.grant().updatedAt > oldRetry.revokedGrant.updatedAt)
        assertEquals(PermissionMutationResult.Outdated, fixture.runner.retryCleanup(oldRetry).await())
        assertEquals(3, fixture.cleanupCalls.size)
    }

    @Test fun missingSecureGrantStillWipesBeforeEnablingAndFailureLeavesAnExplicitDeniedGrant() = runTest {
        val fixture = PermissionTestFixture(this)
        fixture.storedGrants.value = fixture.storedGrants.value.filterNot { it.capability == SECURE_CAPABILITY }
        fixture.cleanup = { throw IllegalStateException("injected orphan cleanup failure") }
        val failed = fixture.runner.submit(
            PERMISSION_TOOL_ID, SECURE_CAPABILITY, true, fixture.initialVersion, null,
        ).await()
        assertTrue(failed is PermissionMutationResult.CleanupFailed)
        assertEquals(1, fixture.cleanupCalls.size)
        assertEquals(listOf(false), fixture.grantWrites.map { it.granted })
        assertFalse(fixture.grant().granted)

        fixture.cleanup = {}
        val retry = (failed as PermissionMutationResult.CleanupFailed).retry
        assertTrue(fixture.runner.retryCleanup(retry).await() is PermissionMutationResult.Saved)
        assertEquals(listOf(false), fixture.grantWrites.map { it.granted })
        assertTrue(fixture.runner.submit(
            PERMISSION_TOOL_ID, SECURE_CAPABILITY, true, fixture.initialVersion, fixture.grant(),
        ).await() is PermissionMutationResult.Saved)
        assertEquals(listOf(false, true), fixture.grantWrites.map { it.granted })
        assertTrue(fixture.cleanupGrants.all { it?.granted == false })
    }

    @Test fun missingSecureGrantCannotEnableIfTheInitialDenyWriteFails() = runTest {
        val fixture = PermissionTestFixture(this)
        fixture.storedGrants.value = emptyList()
        fixture.writeFailures += SECURE_CAPABILITY
        assertEquals(PermissionMutationResult.WriteFailed, fixture.runner.submit(
            PERMISSION_TOOL_ID, SECURE_CAPABILITY, true, fixture.initialVersion, null,
        ).await())
        assertTrue(fixture.grantWrites.isEmpty())
        assertTrue(fixture.cleanupCalls.isEmpty())
    }

    @Test fun cleanupCanDrainRuntimeWorkThatStillNeedsTheSeparateRepositoryLock() = runTest {
        val fixture = PermissionTestFixture(this)
        val repositoryLock = DataMutationLock()
        val runtimeLock = Mutex()
        val runtimeStarted = CompletableDeferred<Unit>(backgroundScope.coroutineContext[Job])
        val persistRuntime = CompletableDeferred<Unit>(backgroundScope.coroutineContext[Job])
        val events = mutableListOf<String>()
        val repository = object : PermissionGrantRepository by fixture {
            override suspend fun putForVersion(grant: PermissionGrant, expectedVersionCode: Int) = repositoryLock.run {
                events += "revoke"
                fixture.putForVersion(grant, expectedVersionCode)
            }
        }
        val effects = object : HostPermissionSideEffects {
            override suspend fun onCapabilityDisabled(toolId: String, capability: String) = runtimeLock.withLock {
                repositoryLock.run { events += "cleanup" }
            }
        }
        // Production injects the outer package lock, not the repository write lock.
        val runner = PermissionMutationRunner(fixture, repository, effects, fixture, this,
            mutationLock = fixture.packageLock)
        val runtimeWriter = launch {
            runtimeLock.withLock {
                runtimeStarted.complete(Unit)
                persistRuntime.await()
                repositoryLock.run { events += "runtime persisted" }
            }
        }
        runtimeStarted.await()
        val operation = runner.submit(PERMISSION_TOOL_ID, SECURE_CAPABILITY, false, fixture.initialVersion, fixture.grant())
        runCurrent()
        try {
            assertFalse(operation.isCompleted)
            assertEquals(listOf("revoke"), events)
        } finally {
            persistRuntime.complete(Unit)
        }
        runtimeWriter.join()
        assertTrue(operation.await() is PermissionMutationResult.Saved)
        assertEquals(listOf("revoke", "runtime persisted", "cleanup"), events)
    }
}

private suspend fun PermissionTestFixture.failRevocation(): PermissionCleanupRetry {
    cleanup = { throw IllegalStateException("injected cleanup failure") }
    val result = runner.submit(PERMISSION_TOOL_ID, SECURE_CAPABILITY, false, initialVersion, grant()).await()
    assertTrue(result is PermissionMutationResult.CleanupFailed)
    return (result as PermissionMutationResult.CleanupFailed).retry
}
