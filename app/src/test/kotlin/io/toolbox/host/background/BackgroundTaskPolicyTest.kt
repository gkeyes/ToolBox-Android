package io.toolbox.host.background

import io.toolbox.core.data.BackgroundOperation
import io.toolbox.core.data.BackgroundTask
import io.toolbox.core.data.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundTaskPolicyTest {
    @Test
    fun retryBudgetAndPeriodicCompletionUseTheDeclaredSchedule() {
        assertTrue(BackgroundRetryPolicy.shouldRetry(1))
        assertTrue(BackgroundRetryPolicy.shouldRetry(2))
        assertTrue(BackgroundRetryPolicy.shouldRetry(3))
        assertFalse(BackgroundRetryPolicy.shouldRetry(4))

        assertEquals(
            BackgroundRunCompletion(TaskState.QUEUED, 901_000),
            task(TaskState.RUNNING, periodic = true).completionAfterRun(1_000),
        )
        assertEquals(
            BackgroundRunCompletion(TaskState.COMPLETED, null),
            task(TaskState.RUNNING, periodic = false).completionAfterRun(1_000),
        )
    }

    @Test
    fun reconciliationOnlyRequeuesRunningTasksWithoutLiveWork() {
        assertEquals(
            BackgroundReconciliationAction.SCHEDULE_QUEUED,
            task(TaskState.QUEUED, periodic = false).reconciliationAction(hasUnfinishedWork = false),
        )
        assertNull(task(TaskState.QUEUED, periodic = false).reconciliationAction(hasUnfinishedWork = true))
        assertEquals(
            BackgroundReconciliationAction.REQUEUE_RUNNING_AND_SCHEDULE,
            task(TaskState.RUNNING, periodic = false).reconciliationAction(hasUnfinishedWork = false),
        )
        assertNull(task(TaskState.RUNNING, periodic = false).reconciliationAction(hasUnfinishedWork = true))
        assertNull(task(TaskState.COMPLETED, periodic = false).reconciliationAction(hasUnfinishedWork = false))
    }

    private fun task(state: TaskState, periodic: Boolean): BackgroundTask = BackgroundTask(
        taskId = "task",
        toolId = "io.toolbox.task",
        versionCode = 1,
        key = "key",
        operation = BackgroundOperation.NOTIFY,
        specJson = "{}",
        periodic = periodic,
        intervalMinutes = if (periodic) 15 else null,
        state = state,
        createdAt = 1,
        updatedAt = 1,
        nextRunAt = null,
        runAttempt = 0,
    )
}
