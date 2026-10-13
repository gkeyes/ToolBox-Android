package io.toolbox.host.runtime

import io.toolbox.core.data.ToolVersion
import io.toolbox.tool.runtime.HardenedRuntimeWebView
import io.toolbox.tool.runtime.PreparedToolRuntime
import io.toolbox.tool.runtime.RpcValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Repeating background timers per tool. Registry calls are made on the main thread. */
internal class RuntimeBackgroundTimers(
    private val scope: CoroutineScope,
    private val publishedHosts: StateFlow<Map<String, RuntimeHost>>,
    private val nowMillis: () -> Long,
    private val requestBackgroundRecovery: (toolId: String, expectedVersion: ToolVersion) -> Unit,
    private val onRuntimeFailed: (toolId: String, runtime: PreparedToolRuntime, code: String, message: String) -> Unit,
) {
    private val timersByTool = mutableMapOf<String, MutableMap<String, Job>>()

    /** Starts [key]'s timer for [toolId], replacing any timer already using that key. */
    fun start(toolId: String, key: String, intervalMillis: Long, expectedVersion: ToolVersion) {
        val jobs = timersByTool.getOrPut(toolId, ::linkedMapOf)
        jobs.remove(key)?.cancel()
        // Ticks, event encoding and delivery queueing run off the main thread; the tool's
        // interval is used as given. Only recovery and failure handling touch the main thread.
        jobs[key] = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(intervalMillis)
                val host = publishedHosts.value[toolId] ?: awaitRecoveredHost(toolId, expectedVersion, intervalMillis)
                if (!HardenedRuntimeWebView.awaitOpenForEvents(host.webView)) continue
                val emitted = HardenedRuntimeWebView.emitEvent(
                    host.webView,
                    EVENT_BACKGROUND_TIMER,
                    RpcValue.ObjectValue(
                        mapOf(
                            "key" to RpcValue.StringValue(key),
                            "firedAt" to RpcValue.Number(nowMillis().toDouble()),
                        ),
                    ),
                )
                if (!emitted) {
                    withContext(Dispatchers.Main.immediate) {
                        onRuntimeFailed(toolId, host.runtime, "EVENT_BACKLOG_OVERFLOW", "后台事件积压，正在按既有规则恢复。")
                    }
                    break
                }
            }
        }
    }

    /**
     * Requests recovery and suspends until a host exists, instead of polling the main
     * thread at the timer's interval. Recovery is re-requested at most once per second
     * (or per interval, if longer) while no host appears.
     */
    private suspend fun awaitRecoveredHost(toolId: String, expectedVersion: ToolVersion, intervalMillis: Long): RuntimeHost {
        while (true) {
            withContext(Dispatchers.Main.immediate) { requestBackgroundRecovery(toolId, expectedVersion) }
            withTimeoutOrNull(maxOf(intervalMillis, TIMER_RECOVERY_RECHECK_MILLIS)) {
                publishedHosts.first { toolId in it }[toolId]
            }?.let { return it }
        }
    }

    fun cancel(toolId: String, key: String): Boolean =
        timersByTool[toolId]?.remove(key)?.also(Job::cancel) != null

    fun cancelAll(toolId: String) {
        timersByTool.remove(toolId)?.values?.forEach(Job::cancel)
    }

    private companion object {
        const val EVENT_BACKGROUND_TIMER = "background.timer"
        const val TIMER_RECOVERY_RECHECK_MILLIS = 1_000L
    }
}
