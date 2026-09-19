package io.toolbox.host.background

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Short state/notification locks only. Network admission belongs to the shared proxy. */
internal object BackgroundExecutionLimiter {
    private val processId = UUID.randomUUID().toString()
    private val toolLocks = ConcurrentHashMap<String, Mutex>()
    private val executions = ConcurrentHashMap<String, Pair<String, Job>>()

    fun newToken(): String = "$processId:${UUID.randomUUID()}"
    fun isActive(taskId: String, token: String?): Boolean = executions[taskId]?.first == token && token != null

    suspend fun <T> run(taskId: String, token: String, block: suspend () -> T): T? {
        val job = currentCoroutineContext()[Job] ?: error("Background execution requires a Job")
        val entry = token to job
        if (executions.putIfAbsent(taskId, entry) != null) return null
        try { return block() } finally { executions.remove(taskId, entry) }
    }

    fun cancelExecution(taskId: String) { executions[taskId]?.second?.cancel() }

    suspend fun <T> lockTool(toolId: String, block: suspend () -> T): T =
        toolLocks.computeIfAbsent(toolId) { Mutex() }.withLock { block() }
}
