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

    override suspend fun doWork(): Result {
        val dependencies = ToolBoxBackgroundRuntime.resolve(applicationContext) ?: return Result.failure()
        pruneExpiredResults(dependencies)
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val repository = dependencies.repositories.backgroundTasks
        val task = when (val found = repository.getTask(taskId)) {
            is DataResult.Success -> found.value ?: return Result.success()
            is DataResult.Failure -> return Result.failure()
        }
        if (task.state == TaskState.COMPLETED || task.state == TaskState.CANCELLED) return Result.success()

        val policy = dependencies.authorization.policyFor(task.toolId, task.versionCode)
        if (policy == null || !policy.matches(task) || !policy.permits(task.operation)) {
            return cancel(task, dependencies, "BACKGROUND_NOT_ALLOWED", task.runAttempt)
        }

        val attempt = maxOf(runAttemptCount + 1, task.runAttempt + 1)
        if (!claim(task, dependencies, attempt)) {
            return Result.success()
        }
        return try {
            when (val execution = BackgroundExecutionLimiter.run(task.toolId) {
                execute(task, dependencies)
            }) {
                is TaskExecution.Succeeded -> finish(task, dependencies, RunOutcome.SUCCEEDED, execution.payloadJson, null, attempt)
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
            cancel(task, dependencies, "CANCELLED", attempt)
            throw cancelled
        } catch (_: SerializationException) {
            finish(task, dependencies, RunOutcome.FAILED, null, "INVALID_TASK_SPEC", attempt)
        } catch (_: Exception) {
            finish(task, dependencies, RunOutcome.FAILED, null, "BACKGROUND_EXECUTION_FAILED", attempt)
        }
    }

    private suspend fun claim(
        task: BackgroundTask,
        dependencies: BackgroundWorkerDependencies,
        attempt: Int,
    ): Boolean {
        val repository = dependencies.repositories.backgroundTasks
        val now = maxOf(dependencies.clock.nowMillis(), task.updatedAt)
        val claimed = when (task.state) {
            TaskState.QUEUED -> repository.markRunning(task.taskId, now, attempt)
            TaskState.RUNNING -> {
                when (repository.deferRetry(task.taskId, now, now, task.runAttempt)) {
                    is DataResult.Success -> repository.markRunning(task.taskId, now, attempt)
                    is DataResult.Failure -> return false
                }
            }
            TaskState.COMPLETED,
            TaskState.CANCELLED,
            -> return false
        }
        return claimed is DataResult.Success
    }

    private suspend fun execute(
        task: BackgroundTask,
        dependencies: BackgroundWorkerDependencies,
    ): TaskExecution {
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
                if (spec.allowRedirects && !policy.allowNetworkRedirects) {
                    return TaskExecution.TerminalFailure("REDIRECTS_DISABLED")
                }
                when (
                    val network = dependencies.networkProxy.httpGet(
                        url = url,
                        allowedHosts = policy.allowedNetworkHosts,
                        allowRedirects = spec.allowRedirects,
                        timeoutMillis = policy.networkTimeoutMillis,
                        maxResponseBytes = minOf(policy.maxNetworkResponseBytes, MAX_RESULT_BYTES),
                    )
                ) {
                    is NetworkExecution.Success -> {
                        val payload = json.encodeToString(
                            HttpGetResult(
                                statusCode = network.statusCode,
                                finalUrl = network.finalUrl,
                                contentType = network.contentType,
                                body = network.body,
                            ),
                        )
                        if (payload.toByteArray(Charsets.UTF_8).size > MAX_RESULT_BYTES) {
                            TaskExecution.TerminalFailure("RESULT_TOO_LARGE")
                        } else {
                            TaskExecution.Succeeded(payload)
                        }
                    }
                    is NetworkExecution.RetryableFailure -> TaskExecution.RetryableFailure(network.errorCode)
                    is NetworkExecution.TerminalFailure -> TaskExecution.TerminalFailure(network.errorCode)
                }
            }
            BackgroundOperation.NOTIFY -> {
                val title = spec.title ?: return TaskExecution.TerminalFailure("INVALID_TASK_SPEC")
                val body = spec.body ?: return TaskExecution.TerminalFailure("INVALID_TASK_SPEC")
                val notificationId = spec.notificationId ?: task.taskId
                if (!isValidNotification(notificationId, title, body)) {
                    return TaskExecution.TerminalFailure("INVALID_NOTIFICATION")
                }
                when (val posted = dependencies.notifications.post(task.toolId, notificationId, title, body)) {
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
    ): Result {
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
        return when (dependencies.repositories.backgroundTasks.finishCancelled(task.taskId, result)) {
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
        )
        return when (stored) {
            is DataResult.Success,
            is DataResult.Failure.InvalidState,
            is DataResult.Failure.NotFound,
            -> Result.success()
            is DataResult.Failure -> Result.failure()
        }
    }

    private fun BackgroundExecutionPolicy.matches(task: BackgroundTask): Boolean =
        toolId == task.toolId && versionCode == task.versionCode && canRunBackground

    private fun BackgroundExecutionPolicy.permits(operation: BackgroundOperation): Boolean = when (operation) {
        BackgroundOperation.HTTP_GET -> networkDeclared && networkGranted && allowedNetworkHosts.isNotEmpty()
        BackgroundOperation.NOTIFY -> notificationsDeclared && notificationsGranted && notificationSystemPermissionGranted
    }

    private fun BackgroundTask.notificationId(): String = runCatching {
        json.decodeFromString<StoredBackgroundSpec>(specJson).notificationId
    }.getOrNull() ?: taskId

    private suspend fun pruneExpiredResults(dependencies: BackgroundWorkerDependencies) {
        val cutoffMillis = (dependencies.clock.nowMillis() - TASK_RESULT_RETENTION_MILLIS).coerceAtLeast(0L)
        dependencies.repositories.backgroundTasks.pruneResultsCompletedBefore(cutoffMillis)
    }

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
    data class Succeeded(val payloadJson: String) : TaskExecution
    data class RetryableFailure(val errorCode: String) : TaskExecution
    data class TerminalFailure(val errorCode: String) : TaskExecution
    data class Cancelled(val errorCode: String) : TaskExecution
}
