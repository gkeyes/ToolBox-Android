package io.toolbox.host.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.toolbox.core.data.BackgroundOperation
import io.toolbox.core.data.BackgroundTask
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.RunOutcome
import io.toolbox.core.data.TaskRunResult
import io.toolbox.core.data.TaskState
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ToolBoxBackgroundRuntime {
    private val dependencies = AtomicReference<BackgroundWorkerDependencies?>()

    fun install(value: BackgroundWorkerDependencies) {
        dependencies.set(value)
    }

    fun clear() {
        dependencies.set(null)
    }

    internal fun current(): BackgroundWorkerDependencies? = dependencies.get()

    internal fun resolve(context: Context): BackgroundWorkerDependencies? {
        current()?.let { return it }
        val owner = context.applicationContext as? BackgroundWorkerDependencyOwner ?: return null
        return runCatching { owner.backgroundWorkerDependencies() }
            .getOrNull()
            ?.also(::install)
    }
}

class ToolBoxBackgroundWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    private val json = Json { ignoreUnknownKeys = false }

    override suspend fun doWork(): Result =
        io.toolbox.host.backup.BackupRuntimeGate.worker { admittedWork() } ?: Result.retry()

    private suspend fun admittedWork(): Result {
        val dependencies = ToolBoxBackgroundRuntime.resolve(applicationContext) ?: return Result.failure()
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val repository = dependencies.repositories.backgroundTasks
        val storedTask = when (val found = repository.getTask(taskId)) {
            is DataResult.Success -> found.value ?: return Result.success()
            is DataResult.Failure -> return Result.failure()
        }
        if (storedTask.state == TaskState.COMPLETED || storedTask.state == TaskState.CANCELLED) return Result.success()
        val attempt = maxOf(runAttemptCount + 1, storedTask.runAttempt + 1)
        val task = storedTask.copy(executionToken = BackgroundExecutionLimiter.newToken())
        return BackgroundExecutionLimiter.run(task.taskId, requireNotNull(task.executionToken)) {
        try {
            if (!claim(storedTask, dependencies, attempt, requireNotNull(task.executionToken))) return@run Result.success()
            val policy = dependencies.authorization.policyFor(task.toolId, task.versionCode)
            if (policy == null || !policy.matches(task) || !policy.permits(task.operation)) {
                return@run cancel(task, dependencies, "BACKGROUND_NOT_ALLOWED", attempt)
            }
            when (val execution = execute(task, dependencies)) {
                is TaskExecution.Succeeded -> try {
                    finish(task, dependencies, RunOutcome.SUCCEEDED, execution.payloadJson, null, attempt)
                } finally { execution.release() }
                is TaskExecution.TerminalFailure -> finish(
                    task,
                    dependencies,
                    RunOutcome.FAILED,
                    null,
                    execution.errorCode,
                    attempt,
                )
                is TaskExecution.Cancelled -> cancel(task, dependencies, execution.errorCode, attempt)
                is TaskExecution.RetryableFailure -> {
                    if (BackgroundRetryPolicy.shouldRetry(attempt)) {
                        val nextRunAt = dependencies.clock.nowMillis() + retryDelayMillis(attempt)
                        when (
                            repository.deferRetry(
                                task.taskId,
                                dependencies.clock.nowMillis(),
                                nextRunAt,
                                attempt,
                                task.executionToken,
                            )
                        ) {
                            is DataResult.Success -> Result.retry()
                            is DataResult.Failure -> Result.failure()
                        }
                    } else {
                        finish(task, dependencies, RunOutcome.FAILED, null, execution.errorCode, attempt)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // A WorkManager constraint/system interruption is not a user cancellation.
            // Administrative cancellation already invalidates this token; compare-and-requeue cannot revive it.
            withContext(NonCancellable) {
                val now = dependencies.clock.nowMillis()
                repository.deferRetry(task.taskId, now, now, attempt, task.executionToken)
            }
            throw cancelled
        } catch (_: SerializationException) {
            finish(task, dependencies, RunOutcome.FAILED, null, "INVALID_TASK_SPEC", attempt)
        } catch (_: Exception) {
            finish(task, dependencies, RunOutcome.FAILED, null, "BACKGROUND_EXECUTION_FAILED", attempt)
        }
        } ?: Result.success()
    }

    private suspend fun claim(
        task: BackgroundTask, dependencies: BackgroundWorkerDependencies, attempt: Int, token: String,
    ): Boolean = BackgroundExecutionLimiter.lockTool(task.toolId) {
        // WorkManager may resume a RUNNING row after process death, but must never steal a live run.
        if (task.state == TaskState.RUNNING && BackgroundExecutionLimiter.isActive(task.taskId, task.executionToken)) {
            return@lockTool false
        }
        dependencies.repositories.backgroundTasks.claimExecution(
            task.taskId, task.versionCode, task.executionToken, token,
            maxOf(dependencies.clock.nowMillis(), task.updatedAt), attempt,
        ) is DataResult.Success
    }

    private suspend fun execute(
        task: BackgroundTask,
        dependencies: BackgroundWorkerDependencies,
    ): TaskExecution {
        if (!isCurrentExecution(task, dependencies)) return TaskExecution.Cancelled("CANCELLED")
        val spec = try {
            json.decodeFromString<StoredBackgroundSpec>(task.specJson)
        } catch (_: SerializationException) {
            return TaskExecution.TerminalFailure("INVALID_TASK_SPEC")
        }
        val policy = dependencies.authorization.policyFor(task.toolId, task.versionCode)
            ?: return TaskExecution.Cancelled("BACKGROUND_NOT_ALLOWED")
        if (!policy.matches(task) || !policy.permits(task.operation)) {
            return TaskExecution.Cancelled("BACKGROUND_NOT_ALLOWED")
        }
        return when (task.operation) {
            BackgroundOperation.HTTP_GET -> {
                val url = spec.url ?: return TaskExecution.TerminalFailure("INVALID_TASK_SPEC")
                when (
                    val network = dependencies.networkProxy.httpGet(
                        url = url,
                        timeoutMillis = policy.networkTimeoutMillis,
                        maxResponseBytes = policy.maxNetworkResponseBytes,
                        resourceOwner = task.toolId,
                    )
                ) {
                    is NetworkExecution.Success -> {
                        val payload = try { json.encodeToString(
                            HttpGetResult(
                                statusCode = network.statusCode,
                                finalUrl = network.finalUrl,
                                contentType = network.contentType,
                                body = network.body,
                            ),
                        ) } catch (error: Throwable) { network.release(); throw error }
                        TaskExecution.Succeeded(payload, network.release)
                    }
                    is NetworkExecution.RetryableFailure -> TaskExecution.RetryableFailure(network.errorCode)
                    is NetworkExecution.TerminalFailure -> TaskExecution.TerminalFailure(network.errorCode)
                }
            }
            BackgroundOperation.NOTIFY -> {
                val title = spec.title ?: return TaskExecution.TerminalFailure("INVALID_TASK_SPEC")
                val body = spec.body ?: return TaskExecution.TerminalFailure("INVALID_TASK_SPEC")
                val notificationId = spec.notificationId ?: task.taskId
                if (!isValidNotification(notificationId, title)) {
                    return TaskExecution.TerminalFailure("INVALID_NOTIFICATION")
                }
                when (val posted = BackgroundExecutionLimiter.lockTool(task.toolId) {
                    if (!isCurrentExecution(task, dependencies)) return@lockTool NotificationResult.Rejected("CANCELLED")
                    dependencies.notifications.post(task.toolId, notificationId, title, body)
                }) {
                    NotificationResult.Posted -> TaskExecution.Succeeded("{\"posted\":true}")
                    is NotificationResult.Rejected -> TaskExecution.TerminalFailure(posted.errorCode)
                }
            }
        }
    }

    private suspend fun cancel(
        task: BackgroundTask,
        dependencies: BackgroundWorkerDependencies,
        errorCode: String,
        attempt: Int,
    ): Result = BackgroundExecutionLimiter.lockTool(task.toolId) {
        if (!isCurrentExecution(task, dependencies)) return@lockTool Result.success()
        try {
            dependencies.notifications.cancel(task.toolId, task.notificationId())
        } catch (_: Exception) {
        }
        val result = TaskRunResult(
            taskId = task.taskId,
            outcome = RunOutcome.CANCELLED,
            completedAt = dependencies.clock.nowMillis(),
            payloadJson = null,
            errorCode = errorCode,
            attemptCount = attempt,
        )
        when (dependencies.repositories.backgroundTasks.finishCancelled(task.taskId, result, task.executionToken)) {
            is DataResult.Success,
            is DataResult.Failure.InvalidState,
            is DataResult.Failure.NotFound,
            -> Result.success()
            is DataResult.Failure -> Result.failure()
        }
    }

    private suspend fun finish(
        task: BackgroundTask,
        dependencies: BackgroundWorkerDependencies,
        outcome: RunOutcome,
        payloadJson: String?,
        errorCode: String?,
        attempt: Int,
    ): Result {
        val now = dependencies.clock.nowMillis()
        val completion = task.completionAfterRun(now)
        val result = TaskRunResult(
            taskId = task.taskId,
            outcome = outcome,
            completedAt = now,
            payloadJson = payloadJson,
            errorCode = errorCode,
            attemptCount = attempt,
        )
        val stored = dependencies.repositories.backgroundTasks.finishRun(
            taskId = task.taskId,
            result = result,
            nextState = completion.nextState,
            nextRunAt = completion.nextRunAt,
            executionToken = task.executionToken,
        )
        return when (stored) {
            is DataResult.Success,
            is DataResult.Failure.InvalidState,
            is DataResult.Failure.NotFound,
            -> Result.success()
            is DataResult.Failure -> Result.failure()
        }
    }

    private suspend fun isCurrentExecution(task: BackgroundTask, dependencies: BackgroundWorkerDependencies): Boolean {
        val current = (dependencies.repositories.backgroundTasks.getTask(task.taskId) as? DataResult.Success)?.value
        return current?.state == TaskState.RUNNING && current.versionCode == task.versionCode &&
            current.executionToken == task.executionToken
    }

    private fun BackgroundExecutionPolicy.matches(task: BackgroundTask): Boolean =
        toolId == task.toolId && versionCode == task.versionCode && canRunBackground

    private fun BackgroundExecutionPolicy.permits(operation: BackgroundOperation): Boolean = when (operation) {
        BackgroundOperation.HTTP_GET -> canUseNetwork
        BackgroundOperation.NOTIFY -> notificationsDeclared && notificationsGranted && notificationSystemPermissionGranted
    }

    private fun BackgroundTask.notificationId(): String = runCatching {
        json.decodeFromString<StoredBackgroundSpec>(specJson).notificationId
    }.getOrNull() ?: taskId

    private fun retryDelayMillis(attempt: Int): Long = 10_000L shl (attempt - 1)

    internal companion object {
        const val KEY_TASK_ID = "task_id"
        const val MAX_RETRIES = BackgroundRetryPolicy.MAX_RETRIES
    }
}

@kotlinx.serialization.Serializable
private data class HttpGetResult(
    val statusCode: Int,
    val finalUrl: String,
    val contentType: String?,
    val body: String,
)

private sealed interface TaskExecution {
    data class Succeeded(val payloadJson: String, val release: () -> Unit = {}) : TaskExecution
    data class RetryableFailure(val errorCode: String) : TaskExecution
    data class TerminalFailure(val errorCode: String) : TaskExecution
    data class Cancelled(val errorCode: String) : TaskExecution
}
