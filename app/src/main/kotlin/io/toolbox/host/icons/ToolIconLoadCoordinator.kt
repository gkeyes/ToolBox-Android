package io.toolbox.host.icons

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Overlapping requests share work; every later request still validates the catalog. */
internal class ToolIconLoadCoordinator<T>(
    parallelDecodes: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private data class Request(val toolId: String, val expectedVersionCode: Int?)
    private class Pending<T>(val scope: CoroutineScope, val result: Deferred<T>, var users: Int = 0)
    private val entries = mutableMapOf<String, Entry>()
    private val pending = mutableMapOf<Request, Pending<T>>()
    private val decodes = Semaphore(parallelDecodes)

    suspend fun share(toolId: String, expectedVersionCode: Int?, action: suspend () -> T): T {
        val request = Request(toolId, expectedVersionCode)
        val load = synchronized(pending) {
            val current = pending[request]?.takeUnless { it.result.isCompleted || it.result.isCancelled }
                ?: run {
                    // No consumer owns this job. Its lifetime ends on completion or when the
                    // last waiter leaves, so cancelling one row cannot cancel another row.
                    val scope = CoroutineScope(dispatcher + Job())
                    Pending(scope, scope.async(start = CoroutineStart.LAZY) { action() }).also { created ->
                        pending[request] = created
                        created.result.invokeOnCompletion {
                            synchronized(pending) {
                                if (pending[request] === created) pending.remove(request)
                            }
                            created.scope.cancel()
                        }
                    }
                }
            // Registration and counting are atomic with last-waiter cancellation.
            current.also { it.users += 1 }
        }
        try {
            return load.result.await()
        } finally {
            val last = synchronized(pending) {
                load.users -= 1
                (load.users == 0).also { empty ->
                    if (empty && pending[request] === load) pending.remove(request)
                }
            }
            if (last) load.scope.cancel()
        }
    }

    /** Call while holding the tool lock so a post-invalidation load cannot join old work. */
    fun forgetShared(toolId: String) {
        synchronized(pending) { pending.keys.removeAll { it.toolId == toolId } }
    }

    suspend fun <R> withTool(toolId: String, action: suspend () -> R): R {
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

    suspend fun <R> decode(action: suspend () -> R): R = decodes.withPermit { action() }
}
