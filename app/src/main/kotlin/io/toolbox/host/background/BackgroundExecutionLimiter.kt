package io.toolbox.host.background

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

internal object BackgroundExecutionLimiter {
    private val globalSlots = Semaphore(permits = 2)
    private val toolLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> run(toolId: String, block: suspend () -> T): T =
        lockFor(toolId).withLock {
            globalSlots.withPermit { block() }
        }

    suspend fun <T> lockTool(toolId: String, block: suspend () -> T): T =
        lockFor(toolId).withLock { block() }

    private fun lockFor(toolId: String): Mutex = toolLocks.computeIfAbsent(toolId) { Mutex() }
}
