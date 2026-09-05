package io.toolbox.host.icons

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Same-tool loads/invalidation serialize; unrelated cache hits never wait for a decode. */
internal class ToolIconLoadCoordinator(parallelDecodes: Int = 2) {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val entries = mutableMapOf<String, Entry>()
    private val decodes = Semaphore(parallelDecodes)

    suspend fun <T> withTool(toolId: String, action: suspend () -> T): T {
        val entry = synchronized(entries) {
            entries.getOrPut(toolId) { Entry() }.also { it.users += 1 }
        }
        try {
            return entry.mutex.withLock { action() }
        } finally {
            synchronized(entries) {
                entry.users -= 1
                if (entry.users == 0) entries.remove(toolId)
            }
        }
    }

    suspend fun <T> decode(action: suspend () -> T): T = decodes.withPermit { action() }
}
