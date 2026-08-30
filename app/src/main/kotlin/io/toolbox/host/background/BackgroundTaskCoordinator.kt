package io.toolbox.host.background

import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import io.toolbox.core.data.BackgroundOperation
import io.toolbox.core.data.BackgroundTask
import io.toolbox.core.data.CoreDataRepositories
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.TaskRunResult
import io.toolbox.core.data.TaskState
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BackgroundTaskCoordinator(
    private val workManager: WorkManager,
    private val repositories: CoreDataRepositories,
    private val authorization: BackgroundAuthorization,
    private val notifications: BackgroundNotificationGateway,
    private val clock: BackgroundClock = BackgroundClock(System::currentTimeMillis),
    private val json: Json = Json { encodeDefaults = false },
) : BackgroundTaskReader {
    private val enqueueMutex = Mutex()

    override fun tasks(toolId: String): Flow<List<BackgroundTask>> =
        repositories.backgroundTasks.observeTasks(toolId)

    override fun result(taskId: String): Flow<TaskRunResult?> =
        repositories.backgroundTasks.observeResult(taskId)

    suspend fun enqueue(
        toolId: String,
        versionCode: Int,
        request: BackgroundTaskRequest,
    ): EnqueueResult = create(toolId, versionCode, request, intervalMinutes = null)

    suspend fun schedulePeriodic(
        toolId: String,
        versionCode: Int,
        request: BackgroundTaskRequest,
        intervalMinutes: Long,
    ): EnqueueResult {
        if (intervalMinutes < MIN_PERIODIC_INTERVAL_MINUTES) {
            return EnqueueResult.Rejected("INTERVAL_TOO_SHORT")
        }
        return create(toolId, versionCode, request, intervalMinutes)
    }

    suspend fun cancel(toolId: String, taskId: String): Boolean {
        val task = (repositories.backgroundTasks.getTask(taskId) as? DataResult.Success)?.value
            ?: return false
        if (task.toolId != toolId) return false
        cancelScheduledWork(task)
        return cancelStoredTask(task)
    }

    suspend fun cancelTool(toolId: String) {
        withContext(Dispatchers.IO) { workManager.cancelAllWorkByTag(toolTag(toolId)) }
        notifications.cancelTool(toolId)
        repositories.backgroundTasks.observeTasks(toolId).first().forEach { task ->
            cancelStoredTask(task)
        }
    }

    suspend fun cancelAll(toolIds: Collection<String>) {
        withContext(Dispatchers.IO) { workManager.cancelAllWorkByTag(GLOBAL_TAG) }
        toolIds.forEach { toolId ->
            notifications.cancelTool(toolId)
            repositories.backgroundTasks.observeTasks(toolId).first().forEach { task ->
                cancelStoredTask(task)
            }
        }
    }

    suspend fun revokeCapability(toolId: String, capability: String) {
        when (capability) {
            "background.tasks" -> cancelTool(toolId)
            "network" -> cancelOperations(toolId, BackgroundOperation.HTTP_GET)
            "notifications" -> {
                cancelOperations(toolId, BackgroundOperation.NOTIFY)
                notifications.cancelTool(toolId)
            }
        }
    }

    suspend fun reconcile() {
        pruneExpiredResults()
        val toolIds = repositories.catalog.observeTools().first().map { it.metadata.id }
        if (!repositories.settings.settings.first().backgroundEnabled) {
            cancelAll(toolIds)
        } else {
            reconcileScheduledTasks(toolIds)
        }
    }

    suspend fun reconcile(toolIds: Collection<String>) {
        pruneExpiredResults()
        reconcileScheduledTasks(toolIds)
    }

    private suspend fun reconcileScheduledTasks(toolIds: Collection<String>) {
        toolIds.forEach { toolId ->
            val tasks = repositories.backgroundTasks.observeTasks(toolId).first()
            tasks.forEach { task ->
                if (task.state !in setOf(TaskState.QUEUED, TaskState.RUNNING)) return@forEach
                val existing = withContext(Dispatchers.IO) {
                    workManager.getWorkInfosForUniqueWork(workName(task.taskId)).get()
                }
                when (task.reconciliationAction(existing.any { !it.state.isFinished })) {
                    BackgroundReconciliationAction.SCHEDULE_QUEUED -> schedule(task)
                    BackgroundReconciliationAction.REQUEUE_RUNNING_AND_SCHEDULE -> {
                        val now = maxOf(clock.nowMillis(), task.updatedAt)
                        if (
                            repositories.backgroundTasks.requeueInterruptedRun(task.taskId, now) is DataResult.Success
                        ) {
                            val recovered = (repositories.backgroundTasks.getTask(task.taskId) as? DataResult.Success)
                                ?.value
                            if (recovered != null) schedule(recovered)
                        }
                    }
                    null -> Unit
                }
            }
        }
    }

    private suspend fun create(
        toolId: String,
        versionCode: Int,
        request: BackgroundTaskRequest,
        intervalMinutes: Long?,
    ): EnqueueResult = enqueueMutex.withLock {
        if (toolId.isBlank() || request.key.isBlank()) return@withLock EnqueueResult.Rejected("INVALID_INPUT")
        if (!request.isValid()) return@withLock EnqueueResult.Rejected("INVALID_INPUT")
        val policy = authorization.policyFor(toolId, versionCode)
            ?: return@withLock EnqueueResult.Rejected("TOOL_NOT_AVAILABLE")
        if (!policy.canRunBackground || policy.toolId != toolId || policy.versionCode != versionCode) {
            return@withLock EnqueueResult.Rejected("BACKGROUND_NOT_ALLOWED")
        }
        when (request) {
            is BackgroundTaskRequest.HttpGet -> if (
                !policy.networkDeclared || !policy.networkGranted || policy.allowedNetworkHosts.isEmpty()
            ) {
                return@withLock EnqueueResult.Rejected("NETWORK_NOT_ALLOWED")
            }
            is BackgroundTaskRequest.Notify -> if (
                !policy.notificationsDeclared || !policy.notificationsGranted ||
                !policy.notificationSystemPermissionGranted
            ) {
                return@withLock EnqueueResult.Rejected("NOTIFICATIONS_NOT_ALLOWED")
            }
        }

        val currentTasks = repositories.backgroundTasks.observeTasks(toolId).first()
        val active = currentTasks.count { it.state == TaskState.QUEUED || it.state == TaskState.RUNNING }
        if (active >= MAX_ACTIVE_TASKS_PER_TOOL) return@withLock EnqueueResult.Rejected("TASK_QUOTA_EXCEEDED")
        if (intervalMinutes != null) {
            val periodic = currentTasks.count {
                it.periodic && (it.state == TaskState.QUEUED || it.state == TaskState.RUNNING)
            }
            if (periodic >= MAX_PERIODIC_TASKS_PER_TOOL) {
                return@withLock EnqueueResult.Rejected("PERIODIC_TASK_QUOTA_EXCEEDED")
            }
        }

        val now = clock.nowMillis()
        val taskId = UUID.randomUUID().toString()
        val task = BackgroundTask(
            taskId = taskId,
            toolId = toolId,
            versionCode = versionCode,
            key = request.key,
            operation = when (request) {
                is BackgroundTaskRequest.HttpGet -> BackgroundOperation.HTTP_GET
                is BackgroundTaskRequest.Notify -> BackgroundOperation.NOTIFY
            },
            specJson = json.encodeToString(request.toStoredSpec()),
            periodic = intervalMinutes != null,
            intervalMinutes = intervalMinutes,
            state = TaskState.QUEUED,
            createdAt = now,
            updatedAt = now,
            nextRunAt = now,
            runAttempt = 0,
        )
        when (val stored = repositories.backgroundTasks.create(task)) {
            is DataResult.Failure -> return@withLock EnqueueResult.Rejected(stored.toErrorCode())
            is DataResult.Success -> Unit
        }

        try {
            schedule(task)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            cancelStoredTask(task)
            return@withLock EnqueueResult.Rejected("SCHEDULING_FAILED")
        }
        EnqueueResult.Enqueued(taskId)
    }

    private suspend fun schedule(task: BackgroundTask) {
        val input = Data.Builder().putString(ToolBoxBackgroundWorker.KEY_TASK_ID, task.taskId).build()
        val constraints = if (task.operation == BackgroundOperation.HTTP_GET) {
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        } else {
            Constraints.NONE
        }
        val now = clock.nowMillis()
        val delayMillis = ((task.nextRunAt ?: now) - now).coerceAtLeast(0L)
        withContext(Dispatchers.IO) {
            if (task.periodic) {
                val request = PeriodicWorkRequestBuilder<ToolBoxBackgroundWorker>(
                    requireNotNull(task.intervalMinutes),
                    TimeUnit.MINUTES,
                ).setInputData(input)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                    .addTag(GLOBAL_TAG)
                    .addTag(toolTag(task.toolId))
                    .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                    .build()
                workManager.enqueueUniquePeriodicWork(
                    workName(task.taskId),
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            } else {
                val request = OneTimeWorkRequestBuilder<ToolBoxBackgroundWorker>()
                    .setInputData(input)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                    .addTag(GLOBAL_TAG)
                    .addTag(toolTag(task.toolId))
                    .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                    .build()
                workManager.enqueueUniqueWork(workName(task.taskId), ExistingWorkPolicy.KEEP, request)
            }
        }
    }

    private suspend fun cancelScheduledWork(task: BackgroundTask) {
        withContext(Dispatchers.IO) { workManager.cancelUniqueWork(workName(task.taskId)) }
        notifications.cancel(task.toolId, taskNotificationId(task))
    }

    private suspend fun cancelOperations(toolId: String, operation: BackgroundOperation) {
        repositories.backgroundTasks.observeTasks(toolId).first()
            .filter { it.operation == operation }
            .forEach { task ->
                cancelScheduledWork(task)
                cancelStoredTask(task)
            }
    }

    private suspend fun cancelStoredTask(task: BackgroundTask): Boolean =
        BackgroundExecutionLimiter.lockTool(task.toolId) {
            val current = (repositories.backgroundTasks.getTask(task.taskId) as? DataResult.Success)?.value
                ?: return@lockTool true
            when (current.state) {
                TaskState.CANCELLED -> true
                TaskState.COMPLETED -> false
                TaskState.QUEUED,
                TaskState.RUNNING,
                -> {
                    val result = TaskRunResult(
                        taskId = current.taskId,
                        outcome = io.toolbox.core.data.RunOutcome.CANCELLED,
                        completedAt = clock.nowMillis(),
                        payloadJson = null,
                        errorCode = "CANCELLED",
                        attemptCount = current.runAttempt,
                    )
                    val cancelled = repositories.backgroundTasks.finishCancelled(current.taskId, result) is DataResult.Success
                    if (cancelled) pruneExpiredResults()
                    cancelled
                }
            }
        }

    private suspend fun pruneExpiredResults() {
        val cutoffMillis = (clock.nowMillis() - TASK_RESULT_RETENTION_MILLIS).coerceAtLeast(0L)
        repositories.backgroundTasks.pruneResultsCompletedBefore(cutoffMillis)
    }

    private fun BackgroundTaskRequest.isValid(): Boolean = when (this) {
        is BackgroundTaskRequest.HttpGet -> url.length in 1..2_048
        is BackgroundTaskRequest.Notify ->
            notificationId.isNotBlank() && title.isNotBlank() && title.length <= 64 && body.length <= 256
    }

    private fun BackgroundTaskRequest.toStoredSpec(): StoredBackgroundSpec = when (this) {
        is BackgroundTaskRequest.HttpGet -> StoredBackgroundSpec(url = url, allowRedirects = allowRedirects)
        is BackgroundTaskRequest.Notify -> StoredBackgroundSpec(
            title = title,
            body = body,
            notificationId = notificationId,
        )
    }

    private fun taskNotificationId(task: BackgroundTask): String = runCatching {
        json.decodeFromString<StoredBackgroundSpec>(task.specJson).notificationId
    }.getOrNull() ?: task.taskId

    private fun DataResult.Failure.toErrorCode(): String = when (this) {
        is DataResult.Failure.DuplicateTaskKey -> "DUPLICATE_TASK_KEY"
        is DataResult.Failure.QuotaExceeded -> "TASK_QUOTA_EXCEEDED"
        is DataResult.Failure.InvalidInput -> "INVALID_INPUT"
        else -> "STORAGE_ERROR"
    }

    private companion object {
        const val GLOBAL_TAG = "toolbox-background"
        fun toolTag(toolId: String) = "toolbox-tool:$toolId"
        fun workName(taskId: String) = "toolbox-task:$taskId"
    }
}
