package io.toolbox.core.data

import io.toolbox.core.data.memory.InMemoryCoreData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundTaskRepositoryTest {
    @Test
    fun taskStateAndLatestResultFollowTheWorkerContract() = runTest {
        val repositories = installedRepositories()
        val oneShot = task("task-1", "refresh", periodic = false)
        assertEquals(DataResult.Success(Unit), repositories.backgroundTasks.create(oneShot))
        assertEquals(
            DataResult.Failure.DuplicateTaskKey(TOOL_ID, "refresh"),
            repositories.backgroundTasks.create(task("task-2", "refresh", periodic = false)),
        )
        assertEquals(DataResult.Success(Unit), repositories.backgroundTasks.markRunning("task-1", 20, 1))
        val result = TaskRunResult("task-1", RunOutcome.SUCCEEDED, 30, "{\"ok\":true}", null, 1)
        assertEquals(
            DataResult.Success(Unit),
            repositories.backgroundTasks.finishRun("task-1", result, TaskState.COMPLETED, null),
        )
        assertEquals(TaskState.COMPLETED, repositories.backgroundTasks.getTask("task-1").value()!!.state)
        assertEquals(result, repositories.backgroundTasks.observeResult("task-1").first())

        val retry = task("task-retry", "retry", periodic = false)
        assertEquals(DataResult.Success(Unit), repositories.backgroundTasks.create(retry))
        assertEquals(DataResult.Success(Unit), repositories.backgroundTasks.markRunning("task-retry", 40, 1))
        assertEquals(
            DataResult.Success(Unit),
            repositories.backgroundTasks.deferRetry("task-retry", 50, 1_000, 1),
        )
        assertEquals(TaskState.QUEUED, repositories.backgroundTasks.getTask("task-retry").value()!!.state)
        assertEquals(null, repositories.backgroundTasks.observeResult("task-retry").first())

        val periodic = task("task-3", "periodic", periodic = true)
        assertEquals(DataResult.Success(Unit), repositories.backgroundTasks.create(periodic))
        assertEquals(DataResult.Success(Unit), repositories.backgroundTasks.markRunning("task-3", 40, 1))
        assertEquals(
            DataResult.Success(Unit),
            repositories.backgroundTasks.finishRun(
                "task-3",
                TaskRunResult("task-3", RunOutcome.FAILED, 50, null, "NETWORK", 1),
                TaskState.QUEUED,
                900_000,
            ),
        )
        val completedPeriodic = repositories.backgroundTasks.getTask("task-3").value()!!
        assertEquals(TaskState.QUEUED, completedPeriodic.state)
        assertEquals(0, completedPeriodic.runAttempt)

        assertEquals(DataResult.Success(Unit), repositories.backgroundTasks.cancel("task-3", 60))
        assertEquals(TaskState.CANCELLED, repositories.backgroundTasks.getTask("task-3").value()!!.state)

        val cancelled = task("task-cancelled", "cancelled", periodic = false)
        assertEquals(DataResult.Success(Unit), repositories.backgroundTasks.create(cancelled))
        val cancelledResult = TaskRunResult("task-cancelled", RunOutcome.CANCELLED, 70, null, "CANCELLED", 0)
        assertEquals(
            DataResult.Success(Unit),
            repositories.backgroundTasks.finishCancelled("task-cancelled", cancelledResult),
        )
        assertEquals(cancelledResult, repositories.backgroundTasks.observeResult("task-cancelled").first())
        assertEquals(
            TaskState.CANCELLED,
            repositories.backgroundTasks.getTask("task-cancelled").value()!!.state,
        )
    }

    @Test
    fun interruptedRunsRequeueAndExpiredResultsArePruned() = runTest {
        val repositories = installedRepositories()
        val interrupted = task("task-interrupted", "interrupted", periodic = false)
        assertEquals(DataResult.Success(Unit), repositories.backgroundTasks.create(interrupted))
        assertEquals(DataResult.Success(Unit), repositories.backgroundTasks.markRunning("task-interrupted", 20, 1))
        assertEquals(DataResult.Success(Unit), repositories.backgroundTasks.requeueInterruptedRun("task-interrupted", 30))
        val recovered = repositories.backgroundTasks.getTask("task-interrupted").value()!!
        assertEquals(TaskState.QUEUED, recovered.state)
        assertEquals(30L, recovered.nextRunAt)
        assertEquals(1, recovered.runAttempt)

        val expired = task("task-expired", "expired", periodic = false)
        val retained = task("task-retained", "retained", periodic = false)
        assertEquals(DataResult.Success(Unit), repositories.backgroundTasks.create(expired))
        assertEquals(DataResult.Success(Unit), repositories.backgroundTasks.create(retained))
        assertEquals(DataResult.Success(Unit), repositories.backgroundTasks.markRunning("task-expired", 40, 1))
        assertEquals(DataResult.Success(Unit), repositories.backgroundTasks.markRunning("task-retained", 40, 1))
        val expiredResult = TaskRunResult("task-expired", RunOutcome.SUCCEEDED, 100, "{}", null, 1)
        val retainedResult = TaskRunResult("task-retained", RunOutcome.SUCCEEDED, 101, "{}", null, 1)
        assertEquals(
            DataResult.Success(Unit),
            repositories.backgroundTasks.finishRun("task-expired", expiredResult, TaskState.COMPLETED, null),
        )
        assertEquals(
            DataResult.Success(Unit),
            repositories.backgroundTasks.finishRun("task-retained", retainedResult, TaskState.COMPLETED, null),
        )
        assertEquals(DataResult.Success(1), repositories.backgroundTasks.pruneResultsCompletedBefore(101))
        assertEquals(null, repositories.backgroundTasks.observeResult("task-expired").first())
        assertEquals(retainedResult, repositories.backgroundTasks.observeResult("task-retained").first())
    }

    private suspend fun installedRepositories(): CoreDataRepositories {
        val repositories = InMemoryCoreData.create()
        val attempt = CatalogInstallAttempt(
            transactionId = "install",
            metadata = ToolMetadata(TOOL_ID, "Task tool", SecurityProfile.STRICT, 1),
            version = ToolVersion(
                TOOL_ID,
                1,
                "1.0.0",
                BundleLocator("tools/task/current"),
                100,
                "sha256:task",
                1,
            ),
            initialGrants = emptyList(),
        )
        repositories.installs.begin(
            InstallTransaction("install", TOOL_ID, 1, InstallTransactionState.PREPARING, 1, 1),
        )
        repositories.lifecycle.commitInstall(attempt)
        return repositories
    }

    private fun task(id: String, key: String, periodic: Boolean) = BackgroundTask(
        taskId = id,
        toolId = TOOL_ID,
        versionCode = 1,
        key = key,
        operation = BackgroundOperation.HTTP_GET,
        specJson = "{}",
        periodic = periodic,
        intervalMinutes = if (periodic) 15 else null,
        state = TaskState.QUEUED,
        createdAt = 10,
        updatedAt = 10,
        nextRunAt = null,
        runAttempt = 0,
    )

    private fun <T> DataResult<T>.value(): T = (this as DataResult.Success).value

    private companion object {
        const val TOOL_ID = "io.toolbox.task"
    }
}
