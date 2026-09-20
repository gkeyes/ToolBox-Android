package io.toolbox.host.background

import io.toolbox.core.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BackgroundTaskCancellationTest {
    @Test fun readAndWriteFailuresAreNotReportedAsAlreadyFinished() = runTest {
        val repository = CancellationRepository(task("task"))
        val cancellation = cancellation(repository)
        repository.readFailure = true
        assertEquals(BackgroundCancellationResult.Failed("STORAGE_ERROR"), cancellation.cancel("tool", "task"))
        assertTrue(repository.writes.isEmpty())

        repository.readFailure = false
        repository.failedWrites += "task"
        assertEquals(BackgroundCancellationResult.Failed("STORAGE_ERROR"), cancellation.cancel("tool", "task"))
        assertEquals(TaskState.RUNNING, repository.tasks.getValue("task").state)
        repository.failedWrites.clear()
        assertEquals(BackgroundCancellationResult.Cancelled, cancellation.cancel("tool", "task"))
        assertEquals(TaskState.CANCELLED, repository.tasks.getValue("task").state)
    }

    @Test fun aSecondReadFailureCannotTurnIntoSuccessfulCancellation() = runTest {
        val repository = CancellationRepository(task("task"))
        val cancellation = cancellation(repository, stopScheduled = { repository.readFailure = true })
        assertEquals(BackgroundCancellationResult.Failed("STORAGE_ERROR"), cancellation.cancel("tool", "task"))
        assertTrue(repository.writes.isEmpty())
    }

    @Test fun finishedMissingAndWrongOwnerTasksHaveNoCancellationSideEffects() = runTest {
        val repository = CancellationRepository(task("finished", TaskState.COMPLETED), task("cancelled", TaskState.CANCELLED), task("active"))
        var stops = 0
        val cancellation = cancellation(repository, stopScheduled = { stops++ })
        assertEquals(BackgroundCancellationResult.AlreadyFinished(), cancellation.cancel("tool", "finished"))
        assertEquals(BackgroundCancellationResult.AlreadyFinished(wasCancelled = true), cancellation.cancel("tool", "cancelled"))
        assertEquals(BackgroundCancellationResult.AlreadyFinished(), cancellation.cancel("tool", "missing"))
        assertEquals(BackgroundCancellationResult.AlreadyFinished(), cancellation.cancel("other-tool", "active"))
        assertEquals(0, stops)
        assertTrue(repository.writes.isEmpty())
    }

    @Test fun finishingWorkerRaceIsRecognizedButActiveInvalidStateIsStillFailure() = runTest {
        val repository = CancellationRepository(task("task"))
        repository.beforeWrite = { id ->
            repository.tasks[id] = repository.tasks.getValue(id).copy(state = TaskState.COMPLETED)
            DataResult.Failure.InvalidState("backgroundTask")
        }
        assertEquals(BackgroundCancellationResult.AlreadyFinished(), cancellation(repository).cancel("tool", "task"))
        repository.tasks["task"] = task("task")
        repository.beforeWrite = { DataResult.Failure.InvalidState("backgroundTask") }
        assertEquals(BackgroundCancellationResult.Failed("STORAGE_ERROR"), cancellation(repository).cancel("tool", "task"))
    }

    @Test fun batchReportsPersistenceFailureContinuesOtherTasksAndRetriesOnlyActiveRecords() = runTest {
        val repository = CancellationRepository(task("failed"), task("succeeds"), task("finished", TaskState.COMPLETED))
        repository.failedWrites += "failed"
        val cancellation = cancellation(repository)
        var failed = false
        try { cancellation.cancelStoredTasks("tool") } catch (_: BackgroundCancellationException) { failed = true }
        assertTrue(failed)
        assertEquals(listOf("failed", "succeeds"), repository.writes)
        assertEquals(TaskState.CANCELLED, repository.tasks.getValue("succeeds").state)
        assertEquals(TaskState.RUNNING, repository.tasks.getValue("failed").state)
        repository.failedWrites.clear()
        cancellation.cancelStoredTasks("tool")
        assertEquals(listOf("failed", "succeeds", "failed"), repository.writes)
    }

    @Test fun schedulerOrNotificationFailureLeavesAnActionableActiveRecord() = runTest {
        val repository = CancellationRepository(task("task"))
        val failingScheduler = cancellation(repository, stopScheduled = { throw IllegalStateException("scheduler failed") })
        assertTrue(failingScheduler.cancel("tool", "task") is BackgroundCancellationResult.Failed)
        assertTrue(repository.writes.isEmpty())
        val notifications = object : BackgroundNotificationGateway {
            override suspend fun post(toolId: String, notificationId: String, title: String, body: String) = NotificationResult.Posted
            override suspend fun cancel(toolId: String, notificationId: String): Unit = throw IllegalStateException("notification failed")
            override suspend fun cancelTool(toolId: String) = Unit
        }
        val cancellation = cancellation(repository, notifications = notifications)
        assertTrue(cancellation.cancel("tool", "task") is BackgroundCancellationResult.Failed)
        assertEquals(TaskState.RUNNING, repository.tasks.getValue("task").state)
        assertTrue(repository.writes.isEmpty())
    }

    @Test fun runtimeRetryRetainsFailedToolAfterItLeavesLiveSessionsAndDoesNotRepeatSuccesses() = runTest {
        // The first owner has already lost its last live session, but its persisted record remains.
        val sessionsByTool = linkedMapOf("fails" to emptyList<String>(), "succeeds" to listOf("session"))
        val attempts = mutableListOf<String>()
        val persisted = sessionsByTool.keys.toMutableSet()
        var fail = true
        val cancellation = BackgroundRuntimeCancellation { toolId ->
            attempts += toolId
            sessionsByTool.remove(toolId)
            if (toolId == "fails" && fail) throw IllegalStateException("persistence failed")
            persisted.remove(toolId)
        }
        var failed = false
        try { cancellation.cancel(sessionsByTool.keys.toList()) } catch (_: BackgroundCancellationException) { failed = true }
        assertTrue(failed)
        assertTrue(sessionsByTool.isEmpty())
        assertEquals(listOf("fails", "succeeds"), attempts)
        assertEquals(setOf("fails"), persisted)
        fail = false
        cancellation.cancel(sessionsByTool.keys.toList())
        assertEquals(listOf("fails", "succeeds", "fails"), attempts)
        assertTrue(persisted.isEmpty())
    }

    @Test fun independentStopStepsRunAfterFailureButDoNotSwallowCoroutineCancellation() = runTest {
        var runtimeStops = 0
        var failed = false
        try {
            completeBackgroundCancellation({ throw IllegalStateException("task persistence failed") }, { runtimeStops++ })
        } catch (_: BackgroundCancellationException) { failed = true }
        assertTrue(failed)
        assertEquals(1, runtimeStops)
        var cancelled = false
        try {
            completeBackgroundCancellation({ throw CancellationException() }, { runtimeStops++ })
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(1, runtimeStops)
    }

    private fun cancellation(
        repository: CancellationRepository,
        stopScheduled: suspend (BackgroundTask) -> Unit = {},
        notifications: BackgroundNotificationGateway = object : BackgroundNotificationGateway {
            override suspend fun post(toolId: String, notificationId: String, title: String, body: String) = NotificationResult.Posted
            override suspend fun cancel(toolId: String, notificationId: String) = Unit
            override suspend fun cancelTool(toolId: String) = Unit
        },
    ) = BackgroundTaskCancellation(repository, notifications, BackgroundClock { 10L }, Json, stopScheduled)

    private fun task(id: String, state: TaskState = TaskState.RUNNING) = BackgroundTask(
        taskId = id, toolId = "tool", versionCode = 1, key = id, operation = BackgroundOperation.NOTIFY,
        specJson = "{}", periodic = false, intervalMinutes = null, state = state,
        createdAt = 0, updatedAt = 0, nextRunAt = null, runAttempt = 1,
    )
}

private class CancellationRepository(vararg initial: BackgroundTask) : BackgroundTaskRepository {
    val tasks = initial.associateByTo(linkedMapOf()) { it.taskId }
    val writes = mutableListOf<String>()
    val failedWrites = mutableSetOf<String>()
    var readFailure = false
    var beforeWrite: ((String) -> DataResult<Unit>)? = null
    override fun observeTasks(toolId: String): Flow<List<BackgroundTask>> = flowOf(tasks.values.filter { it.toolId == toolId })
    override fun observeResult(taskId: String): Flow<TaskRunResult?> = error("not used")
    override suspend fun getTask(taskId: String): DataResult<BackgroundTask?> =
        if (readFailure) DataResult.Failure.StorageFailure("getTask") else DataResult.Success(tasks[taskId])
    override suspend fun finishCancelled(taskId: String, result: TaskRunResult, executionToken: String?): DataResult<Unit> {
        writes += taskId
        beforeWrite?.let { return it(taskId) }
        if (taskId in failedWrites) return DataResult.Failure.StorageFailure("finishCancelled")
        tasks[taskId] = tasks.getValue(taskId).copy(state = TaskState.CANCELLED)
        return DataResult.Success(Unit)
    }
    override suspend fun create(task: BackgroundTask): DataResult<Unit> = error("not used")
    override suspend fun markRunning(taskId: String, updatedAt: Long, runAttempt: Int): DataResult<Unit> = error("not used")
    override suspend fun claimExecution(taskId: String, versionCode: Int, previousToken: String?, executionToken: String, updatedAt: Long, runAttempt: Int): DataResult<Unit> = error("not used")
    override suspend fun deferRetry(taskId: String, updatedAt: Long, nextRunAt: Long, runAttempt: Int, executionToken: String?): DataResult<Unit> = error("not used")
    override suspend fun requeueInterruptedRun(taskId: String, updatedAt: Long): DataResult<Unit> = error("not used")
    override suspend fun finishRun(taskId: String, result: TaskRunResult, nextState: TaskState, nextRunAt: Long?, executionToken: String?): DataResult<Unit> = error("not used")
    override suspend fun cancel(taskId: String, updatedAt: Long): DataResult<Unit> = error("not used")
    override suspend fun pruneResultsCompletedBefore(cutoffMillis: Long): DataResult<Int> = error("not used")
    override suspend fun deleteForTool(toolId: String): DataResult<Unit> = error("not used")
}
