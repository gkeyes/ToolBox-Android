package io.toolbox.host.background

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface BackgroundTaskAction {
    data class Cancel(val taskId: String) : BackgroundTaskAction
    data class Stop(val sessionId: String) : BackgroundTaskAction
}

internal data class BackgroundTaskActionsState(
    val cancellingTaskId: String? = null,
    val stoppingSessionId: String? = null,
    val message: String? = null,
    val retry: BackgroundTaskAction.Cancel? = null,
)

/** Screen-scoped actions; callbacks are guarded before launching and always release their busy state. */
internal class BackgroundTaskActions(
    private val scope: CoroutineScope,
    private val cancelTask: suspend (String) -> BackgroundCancellationResult,
    private val stopSession: suspend (String) -> Boolean,
) {
    private val mutableState = MutableStateFlow(BackgroundTaskActionsState())
    val state = mutableState.asStateFlow()
    private val failedSessionStops = mutableSetOf<String>()

    fun cancel(taskId: String) {
        if (state.value.cancellingTaskId != null) return
        mutableState.update { it.copy(
            cancellingTaskId = taskId,
            message = it.message.takeIf { _ -> it.retry?.taskId == taskId },
            retry = it.retry?.takeIf { retry -> retry.taskId == taskId },
        ) }
        scope.launch {
            try {
                when (cancelTask(taskId)) {
                    BackgroundCancellationResult.Cancelled ->
                        mutableState.update { it.copy(message = null, retry = null) }
                    is BackgroundCancellationResult.AlreadyFinished ->
                        mutableState.update { it.copy(message = "任务已结束或不存在。", retry = null) }
                    is BackgroundCancellationResult.Failed -> failed(BackgroundTaskAction.Cancel(taskId))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed(BackgroundTaskAction.Cancel(taskId))
            } finally {
                mutableState.update { it.copy(cancellingTaskId = null) }
            }
        }
    }

    fun stop(sessionId: String) {
        if (state.value.stoppingSessionId != null) return
        if (sessionId in failedSessionStops) {
            failed(BackgroundTaskAction.Stop(sessionId))
            return
        }
        mutableState.update { it.copy(stoppingSessionId = sessionId, message = null, retry = null) }
        scope.launch {
            try {
                val stopped = stopSession(sessionId)
                mutableState.update { it.copy(message = if (stopped) "后台环境已停止。" else
                    "后台会话已不可用。如需完成停止清理，请前往后台保障关闭后台运行。") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed(BackgroundTaskAction.Stop(sessionId))
            } finally {
                mutableState.update { it.copy(stoppingSessionId = null) }
            }
        }
    }

    fun retry() {
        state.value.retry?.let { cancel(it.taskId) }
    }

    private fun failed(action: BackgroundTaskAction) {
        if (action is BackgroundTaskAction.Stop) failedSessionStops += action.sessionId
        mutableState.update { it.copy(
            message = when (action) {
                is BackgroundTaskAction.Cancel -> "任务未能取消，请重试。"
                is BackgroundTaskAction.Stop -> "后台环境停止未完成。请前往后台保障，关闭后台运行并重试停止。"
            },
            // stopSession can remove its in-memory identity before persistence fails.
            // Global background shutdown retains the owning tool and can finish that cleanup.
            retry = action as? BackgroundTaskAction.Cancel,
        ) }
    }
}
