package io.toolbox.host.backup

import kotlinx.coroutines.*

/** Closing admission and joining actual work is stronger than WorkManager's asynchronous cancel. */
internal object BackupRuntimeGate {
    private val monitor = Any()
    private val workers = mutableSetOf<Job>()
    @Volatile var paused = false
        private set

    suspend fun <T : Any> worker(action: suspend () -> T): T? = coroutineScope {
        // A structured child, never the caller's application/ViewModel lifetime job.
        val job = checkNotNull(currentCoroutineContext()[Job])
        val admitted = synchronized(monitor) { if (paused) false else { workers += job; true } }
        if (!admitted) return@coroutineScope null
        try { action() } finally { synchronized(monitor) { workers -= job } }
    }
    suspend fun pauseAndDrain() {
        val running = synchronized(monitor) {
            check(!paused) { "RESTORE_BUSY" }
            paused = true
            workers.toList()
        }
        running.forEach { it.cancel() }
        running.joinAll()
    }
    fun resume() { synchronized(monitor) { paused = false } }
}
