package io.toolbox.host.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundRuntimeRecoveryTest {
    private fun TestScope.controller() = BackgroundRuntimeRecovery(
        this, { testScheduler.currentTime }, delays = listOf(10L, 20L, 30L),
        attemptTimeoutMillis = 100L, budgetWindowMillis = 1_000L,
    )

    @Test
    fun retriesWithBackoffAndStopsAfterReadiness() = runTest {
        val recovery = controller()
        val attempts = mutableListOf<Int>()
        var starts = 0
        var interrupted = 0
        recovery.request("tool", { true }, { ++starts == 2 }, { attempts += it }, { interrupted++ })
        advanceUntilIdle()
        assertEquals(listOf(1, 2), attempts)
        assertEquals(2, starts)
        assertEquals(0, interrupted)
        assertEquals(30L, testScheduler.currentTime)
    }

    @Test
    fun missingReadinessHasABoundedTimeoutAndRetryCount() = runTest {
        val recovery = controller()
        var starts = 0
        var interrupted = 0
        recovery.request("tool", { true }, { starts++; awaitCancellation() }, {}, { interrupted++ })
        advanceUntilIdle()
        assertEquals(3, starts)
        assertEquals(1, interrupted)
        assertEquals(360L, testScheduler.currentTime)
    }

    @Test
    fun concurrentFailureSignalsDoNotCreateParallelRestarts() = runTest {
        val recovery = controller()
        var starts = 0
        repeat(20) { recovery.request("tool", { true }, { starts++; true }, {}, {}) }
        advanceUntilIdle()
        assertEquals(1, starts)
    }

    @Test
    fun stopBeforeDelayPreventsCreationAndInterruptedFeedback() = runTest {
        val recovery = controller()
        var starts = 0
        var interrupted = 0
        recovery.request("tool", { true }, { starts++; true }, {}, { interrupted++ })
        runCurrent()
        recovery.cancel("tool")?.join()
        advanceUntilIdle()
        assertEquals(0, starts)
        assertEquals(0, interrupted)
    }

    @Test
    fun revocationOrReplacementDuringBackoffPreventsCreation() = runTest {
        val recovery = controller()
        var allowed = true
        var starts = 0
        var interrupted = 0
        recovery.request("tool", { allowed }, { starts++; true }, {}, { interrupted++ })
        runCurrent()
        allowed = false
        advanceUntilIdle()
        assertEquals(0, starts)
        assertEquals(1, interrupted)
    }

    @Test
    fun cancelJoinsAnInProgressPreparationBeforeFilesCanChange() = runTest {
        val recovery = controller()
        var started = false
        var released = false
        recovery.request("tool", { true }, {
            started = true
            try { awaitCancellation() } finally { released = true }
        }, {}, {})
        advanceTimeBy(10)
        runCurrent()
        assertTrue(started)
        recovery.cancel("tool")?.join()
        assertTrue(released)
        advanceUntilIdle()
    }

    @Test
    fun rapidSuccessfulRecreationsStillShareTheCrashBudget() = runTest {
        val recovery = controller()
        var starts = 0
        var interrupted = 0
        repeat(4) {
            recovery.request("tool", { true }, { starts++; true }, {}, { interrupted++ })
            advanceUntilIdle()
        }
        assertEquals(3, starts)
        assertEquals(1, interrupted)
    }

    @Test
    fun manualRetryResetsBudgetAndToolsHaveIndependentBudgets() = runTest {
        val recovery = controller()
        var starts = 0
        recovery.request("a", { true }, { starts++; false }, {}, {})
        advanceUntilIdle()
        recovery.request("b", { true }, { starts++; true }, {}, {})
        advanceUntilIdle()
        assertEquals(4, starts)
        recovery.cancel("a")?.join()
        recovery.request("a", { true }, { starts++; true }, {}, {})
        advanceUntilIdle()
        assertEquals(5, starts)
    }

    @Test
    fun stalledAuthorizationReadAlsoConsumesABoundedBudget() = runTest {
        val recovery = controller()
        var checks = 0
        var starts = 0
        var interrupted = 0
        recovery.request("tool", { checks++; awaitCancellation() }, { starts++; true }, {}, { interrupted++ })
        advanceUntilIdle()
        assertEquals(3, checks)
        assertEquals(0, starts)
        assertEquals(1, interrupted)
    }

    @Test
    fun rebootAndProcessOptInsRemainIndependent() {
        assertTrue(backgroundRestoreOptedIn("reboot", processDeath = false, reboot = true))
        assertFalse(backgroundRestoreOptedIn("process", processDeath = false, reboot = true))
        assertTrue(backgroundRestoreOptedIn("process", processDeath = true, reboot = false))
        assertFalse(backgroundRestoreOptedIn("reboot", processDeath = true, reboot = false))
        assertFalse(backgroundRestoreOptedIn("unknown", processDeath = true, reboot = true))
    }
}
