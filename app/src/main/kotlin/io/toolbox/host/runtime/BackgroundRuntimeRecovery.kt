package io.toolbox.host.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Main-dispatcher owned. Repeated renderer crashes share a bounded retry budget. */
internal class BackgroundRuntimeRecovery(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val delays: List<Long> = listOf(1_000L, 5_000L, 15_000L),
    private val attemptTimeoutMillis: Long = 30_000L,
    private val budgetWindowMillis: Long = 10 * 60_000L,
) {
    private val jobs = mutableMapOf<String, Job>()
    private val attempts = mutableMapOf<String, MutableList<Long>>()

    fun request(
        toolId: String,
        allowed: suspend () -> Boolean,
        restart: suspend () -> Boolean,
        onRecovering: (Int) -> Unit,
        onInterrupted: () -> Unit,
    ) {
        if (jobs[toolId]?.isActive == true) return
        val history = attempts.getOrPut(toolId) { mutableListOf() }
        history.removeAll { nowMillis() - it >= budgetWindowMillis }
        if (history.size >= delays.size) {
            onInterrupted()
            return
        }
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (history.size < delays.size) {
                    onRecovering(history.size + 1)
                    delay(delays[history.size])
                    history += nowMillis()
                    var permitted: Boolean? = null
                    val ready = withTimeoutOrNull(attemptTimeoutMillis) {
                        permitted = allowed()
                        permitted == true && restart()
                    }
                    if (ready == true) return@launch
                    // Revocation/stop is final; a read/start timeout consumes one
                    // attempt and is retried, with no unbounded check outside the timeout.
                    if (permitted == false) break
                }
                onInterrupted()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onInterrupted()
            } finally {
                if (jobs[toolId] === currentCoroutineContext()[Job]) jobs.remove(toolId)
            }
        }
        jobs[toolId] = task
        task.start()
    }

    /** The caller can join the cancelled job before replacing files or retrying. */
    fun cancel(toolId: String): Job? {
        attempts.remove(toolId)
        return jobs.remove(toolId)?.also(Job::cancel)
    }
}

/** Keep the original process-death and reboot opt-ins independent. */
internal fun backgroundRestoreOptedIn(reason: String, processDeath: Boolean, reboot: Boolean): Boolean =
    when (reason) {
        "process" -> processDeath
        "reboot" -> reboot
        else -> false
    }
