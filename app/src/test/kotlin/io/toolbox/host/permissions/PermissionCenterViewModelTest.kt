package io.toolbox.host.permissions

import androidx.lifecycle.viewModelScope
import io.toolbox.host.backup.BackupRuntimeGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionCenterViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<PermissionCenterViewModel>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }

    @After fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        BackupRuntimeGate.resume()
        Dispatchers.resetMain()
    }

    @Test fun failureExposesRetryAndBusyRetryCannotToggleOrSubmitTwice() = runTest(dispatcher) {
        val fixture = PermissionTestFixture(this)
        fixture.cleanup = { throw IllegalStateException("injected cleanup failure") }
        val model = createModel(fixture)
        runCurrent()
        model.setEnabled(SECURE_CAPABILITY, false)
        assertTrue(SECURE_CAPABILITY in model.state.value.busyCapabilities)
        runCurrent()
        assertFalse(model.secureEnabled())
        val retry = checkNotNull(model.state.value.cleanupRetries[SECURE_CAPABILITY])
        assertTrue(model.state.value.busyCapabilities.isEmpty())

        val finishCleanup = CompletableDeferred<Unit>(backgroundScope.coroutineContext[Job])
        fixture.cleanup = { finishCleanup.await() }
        model.retryCleanup(SECURE_CAPABILITY)
        runCurrent()
        assertTrue(SECURE_CAPABILITY in model.state.value.busyCapabilities)
        assertFalse(model.secureEnabled())
        assertEquals(retry, model.state.value.cleanupRetries[SECURE_CAPABILITY])
        model.retryCleanup(SECURE_CAPABILITY)
        model.setEnabled(SECURE_CAPABILITY, true)
        runCurrent()
        assertEquals(2, fixture.cleanupCalls.size)
        assertEquals(listOf(retry.revokedGrant), fixture.grantWrites)
        finishCleanup.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.secureEnabled())
        assertTrue(model.state.value.cleanupRetries.isEmpty())
        assertTrue(model.state.value.busyCapabilities.isEmpty())
        assertNull(model.state.value.message)
    }

    @Test fun repeatedCleanupFailureAndClosedAdmissionRetainAnActionableRetry() = runTest(dispatcher) {
        val fixture = PermissionTestFixture(this)
        fixture.cleanup = { throw IllegalStateException("injected cleanup failure") }
        val model = createModel(fixture)
        runCurrent()
        model.setEnabled(SECURE_CAPABILITY, false)
        runCurrent()
        val retry = model.state.value.cleanupRetries[SECURE_CAPABILITY]
        model.retryCleanup(SECURE_CAPABILITY)
        runCurrent()
        assertEquals(retry, model.state.value.cleanupRetries[SECURE_CAPABILITY])
        assertTrue(model.state.value.busyCapabilities.isEmpty())
        assertFalse(model.secureEnabled())

        BackupRuntimeGate.pauseAndDrain()
        model.retryCleanup(SECURE_CAPABILITY)
        runCurrent()
        assertEquals(retry, model.state.value.cleanupRetries[SECURE_CAPABILITY])
        assertTrue(model.state.value.busyCapabilities.isEmpty())
        assertEquals(2, fixture.cleanupCalls.size)
        BackupRuntimeGate.resume()
        fixture.cleanup = {}
        model.retryCleanup(SECURE_CAPABILITY)
        advanceUntilIdle()
        assertTrue(model.state.value.cleanupRetries.isEmpty())
        assertFalse(model.secureEnabled())
        assertEquals(1, fixture.grantWrites.size)
    }

    @Test fun sameCodeReplacementAndGrantChangesRemoveStaleRetryActions() = runTest(dispatcher) {
        val fixture = PermissionTestFixture(this)
        fixture.cleanup = { throw IllegalStateException("injected cleanup failure") }
        val model = createModel(fixture)
        runCurrent()
        model.setEnabled(SECURE_CAPABILITY, false)
        runCurrent()
        assertTrue(model.state.value.cleanupRetries.isNotEmpty())
        fixture.replaceVersion(fixture.initialVersion.copy(installedAt = 9L, integrityHash = "replacement"))
        runCurrent()
        assertTrue(model.state.value.cleanupRetries.isEmpty())
        assertFalse(model.secureEnabled())
        model.retryCleanup(SECURE_CAPABILITY)
        runCurrent()
        assertEquals(1, fixture.cleanupCalls.size)

        fixture.cleanup = {}
        model.setEnabled(SECURE_CAPABILITY, true)
        runCurrent()
        fixture.cleanup = { throw IllegalStateException("injected cleanup failure") }
        model.setEnabled(SECURE_CAPABILITY, false)
        runCurrent()
        assertTrue(model.state.value.cleanupRetries.isNotEmpty())
        fixture.replaceGrant(fixture.grant().copy(granted = true, updatedAt = 20L))
        runCurrent()
        assertTrue(model.secureEnabled())
        assertTrue(model.state.value.cleanupRetries.isEmpty())
        model.retryCleanup(SECURE_CAPABILITY)
        runCurrent()
        assertEquals(3, fixture.cleanupCalls.size)
        fixture.installed.value = null
        runCurrent()
        assertEquals(PermissionLoadState.NotInstalled, model.state.value.loadState)
        assertTrue(model.state.value.items.isEmpty())
    }

    @Test fun lateCleanupCompletionCannotClearANewVersionsBusyStateOrFailure() = runTest(dispatcher) {
        val fixture = PermissionTestFixture(this)
        fixture.cleanup = { throw IllegalStateException("injected cleanup failure") }
        val model = createModel(fixture)
        runCurrent()
        model.setEnabled(SECURE_CAPABILITY, false)
        runCurrent()
        val finishCleanup = CompletableDeferred<Unit>(backgroundScope.coroutineContext[Job])
        fixture.cleanup = { finishCleanup.await() }
        model.retryCleanup(SECURE_CAPABILITY)
        runCurrent()

        // Deliver a replacement while the old page receipt is outstanding. The
        // runner's package-lock race is covered separately at its faithful layer.
        fixture.replaceVersion(fixture.initialVersion.copy(installedAt = 20L))
        fixture.replaceGrant(fixture.grant().copy(granted = true, updatedAt = 21L))
        runCurrent()
        assertTrue(model.secureEnabled())
        assertTrue(model.state.value.cleanupRetries.isEmpty())
        fixture.writeFailures += "haptics"
        model.setEnabled("haptics", false)
        assertEquals(setOf("haptics"), model.state.value.busyCapabilities)
        finishCleanup.complete(Unit)
        advanceUntilIdle()
        assertTrue(model.secureEnabled())
        assertTrue(model.state.value.cleanupRetries.isEmpty())
        assertTrue(model.state.value.busyCapabilities.isEmpty())
        assertEquals("权限操作未完成，请重试。", model.state.value.message)
    }

    @Test fun anOldSystemPermissionResultCannotApplyAfterItsGrantRecordChanges() = runTest(dispatcher) {
        val fixture = PermissionTestFixture(this)
        val model = createModel(fixture)
        runCurrent()
        val request = async { model.requests.first() }
        runCurrent()
        model.setEnabled("location", true)
        runCurrent()
        val pending = request.await()
        assertTrue("location" in model.state.value.busyCapabilities)
        fixture.replaceGrant(fixture.grant("location").copy(updatedAt = 9L))
        runCurrent()
        model.systemPermissionResult(pending.id, pending.permissions.associateWith { true })
        advanceUntilIdle()
        assertFalse(fixture.grant("location").granted)
        assertTrue(fixture.grantWrites.isEmpty())
        assertTrue(model.state.value.busyCapabilities.isEmpty())
    }

    @Test fun receiptReadFailureReleasesBusyAndNeverKeepsARetryAfterReauthorization() = runTest(dispatcher) {
        for (failCatalog in listOf(true, false)) {
            val fixture = PermissionTestFixture(this)
            val finishCleanup = CompletableDeferred<Unit>(backgroundScope.coroutineContext[Job])
            fixture.cleanup = {
                finishCleanup.await()
                throw IllegalStateException("injected cleanup failure")
            }
            val model = createModel(fixture)
            runCurrent()
            model.setEnabled(SECURE_CAPABILITY, false)
            runCurrent()
            assertFalse(model.secureEnabled())
            if (failCatalog) fixture.failNextCatalogRead = true else fixture.failNextGrantRead = true
            finishCleanup.complete(Unit)
            runCurrent()
            assertTrue(model.state.value.busyCapabilities.isEmpty())
            assertEquals("暂时无法确认最新权限状态，可重试清除。", model.state.value.message)
            assertTrue(model.state.value.cleanupRetries.isNotEmpty())
            assertFalse(model.secureEnabled())

            fixture.replaceGrant(fixture.grant().copy(granted = true, updatedAt = 20L))
            runCurrent()
            assertTrue(model.state.value.cleanupRetries.isEmpty())
            model.retryCleanup(SECURE_CAPABILITY)
            runCurrent()
            assertEquals(1, fixture.cleanupCalls.size)
            assertTrue(model.secureEnabled())
        }
    }

    @Test fun successfulCleanupWithAFailedReceiptReadDoesNotClaimSuccessOrStayBusy() = runTest(dispatcher) {
        val fixture = PermissionTestFixture(this)
        val finishCleanup = CompletableDeferred<Unit>(backgroundScope.coroutineContext[Job])
        fixture.cleanup = { finishCleanup.await() }
        val model = createModel(fixture)
        runCurrent()
        model.setEnabled(SECURE_CAPABILITY, false)
        runCurrent()
        fixture.failNextGrantRead = true
        finishCleanup.complete(Unit)
        runCurrent()
        assertTrue(model.state.value.busyCapabilities.isEmpty())
        assertTrue(model.state.value.cleanupRetries.isEmpty())
        assertFalse(model.secureEnabled())
        assertEquals("暂时无法确认最新权限状态，请返回后重新打开权限。", model.state.value.message)
        fixture.cleanup = {}
        model.setEnabled(SECURE_CAPABILITY, true)
        advanceUntilIdle()
        assertTrue(model.secureEnabled())
        assertNull(model.state.value.message)
    }

    private fun createModel(fixture: PermissionTestFixture) = PermissionCenterViewModel(
        PERMISSION_TOOL_ID, fixture, fixture, fixture, fixture.runner,
    ).also(viewModels::add)

    private fun PermissionCenterViewModel.secureEnabled() = state.value.items.first { it.capability == SECURE_CAPABILITY }.enabled
}
