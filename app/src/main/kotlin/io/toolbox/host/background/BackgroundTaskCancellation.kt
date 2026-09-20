package io.toolbox.host.background

import io.toolbox.core.data.BackgroundTask
import io.toolbox.core.data.BackgroundTaskRepository
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.RunOutcome
import io.toolbox.core.data.TaskRunResult
import io.toolbox.core.data.TaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

internal sealed interface BackgroundCancellationResult {
    data object Cancelled : BackgroundCancellationResult
    data class AlreadyFinished(val wasCancelled: Boolean = false) : BackgroundCancellationResult
    data class Failed(val code: String) : BackgroundCancellationResult
}

internal class BackgroundCancellationException : Exception("Background cancellation did not finish")

/** Retain failed tool identities even when stopTool already removed their live sessions. */
internal class BackgroundRuntimeCancellation(private val stopTool: suspend (String) -> Unit) {
    private val mutex = Mutex()
    private val pending = linkedSetOf<String>()

    suspend fun cancel(toolIds: Collection<String>) = mutex.withLock {
        pending.addAll(toolIds)
        completeBackgroundCancellation(*pending.toList().map { toolId -> suspend {
            stopTool(toolId)
            pending.remove(toolId)
            Unit
        } }.toTypedArray())
    }
}

/** Attempt every independent cleanup step, then report any unfinished work to the caller. */
internal suspend fun completeBackgroundCancellation(vararg steps: suspend () -> Unit) {
    var failed = false
    for (step in steps) {
        try {
            step()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    if (failed) throw BackgroundCancellationException()
}

internal class BackgroundTaskCancellation(
    private val repository: BackgroundTaskRepository,
    private val notifications: BackgroundNotificationGateway,
    private val clock: BackgroundClock,
    private val json: Json,
    private val cancelScheduledWork: suspend (BackgroundTask) -> Unit,
) {
    suspend fun cancelStoredTasks(toolId: String) {
        val tasks = repository.observeTasks(toolId).first().filterNot { it.isFinished }
        completeBackgroundCancellation(*tasks.map { task -> suspend {
            if (cancelStoredTask(task) is BackgroundCancellationResult.Failed) {
                throw BackgroundCancellationException()
            }
        } }.toTypedArray())
    }

    suspend fun cancel(toolId: String, taskId: String): BackgroundCancellationResult = try {
        when (val stored = repository.getTask(taskId)) {
            is DataResult.Failure -> BackgroundCancellationResult.Failed("STORAGE_ERROR")
            is DataResult.Success -> {
                val task = stored.value
                when {
                    task == null || task.toolId != toolId -> BackgroundCancellationResult.AlreadyFinished()
                    task.isFinished -> task.finishedResult()
                    else -> {
                        cancelScheduledWork(task)
                        cancelStoredTask(task)
                    }
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        BackgroundCancellationResult.Failed("BACKGROUND_CANCEL_FAILED")
    }

    suspend fun cancelStoredTask(task: BackgroundTask): BackgroundCancellationResult =
        BackgroundExecutionLimiter.lockTool(task.toolId) {
            BackgroundExecutionLimiter.cancelExecution(task.taskId)
            val current = when (val stored = repository.getTask(task.taskId)) {
                is DataResult.Failure -> return@lockTool BackgroundCancellationResult.Failed("STORAGE_ERROR")
                is DataResult.Success -> stored.value ?: return@lockTool BackgroundCancellationResult.AlreadyFinished()
            }
            if (current.isFinished) return@lockTool current.finishedResult()
            // Leave the record cancellable until its notification has also been removed.
            notifications.cancel(current.toolId, taskNotificationId(current))
            val result = TaskRunResult(
                taskId = current.taskId,
                outcome = RunOutcome.CANCELLED,
                completedAt = maxOf(clock.nowMillis(), current.updatedAt),
                payloadJson = null,
                errorCode = "CANCELLED",
                attemptCount = current.runAttempt,
            )
            when (repository.finishCancelled(current.taskId, result)) {
                is DataResult.Success -> BackgroundCancellationResult.Cancelled
                is DataResult.Failure.InvalidState,
                is DataResult.Failure.NotFound,
                -> {
                    // A worker may have finished between the read and the administrative write.
                    when (val latest = repository.getTask(current.taskId)) {
                        is DataResult.Success -> {
                            val latestTask = latest.value
                            when {
                                latestTask == null -> BackgroundCancellationResult.AlreadyFinished()
                                latestTask.isFinished -> latestTask.finishedResult()
                                else -> BackgroundCancellationResult.Failed("STORAGE_ERROR")
                            }
                        }
                        is DataResult.Failure -> BackgroundCancellationResult.Failed("STORAGE_ERROR")
                    }
                }
                is DataResult.Failure -> BackgroundCancellationResult.Failed("STORAGE_ERROR")
            }
        }

    private fun taskNotificationId(task: BackgroundTask): String = runCatching {
        json.decodeFromString<StoredBackgroundSpec>(task.specJson).notificationId
    }.getOrNull() ?: task.taskId
}

internal val BackgroundTask.isFinished: Boolean
    get() = state == TaskState.CANCELLED || state == TaskState.COMPLETED

private fun BackgroundTask.finishedResult() =
    BackgroundCancellationResult.AlreadyFinished(wasCancelled = state == TaskState.CANCELLED)
