package io.toolbox.tool.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Admission happens before parsing/launching, including requests waiting on a dispatcher. */
internal class RuntimeRequestBudget(private val maxRequests: Int, private val maxBytes: Int) {
    private var requests = 0
    private var bytes = 0

    init {
        require(maxRequests > 0 && maxBytes > 0)
    }

    @Synchronized
    fun acquire(retainedBytes: Int): Boolean {
        require(retainedBytes >= 0)
        if (requests >= maxRequests || retainedBytes > maxBytes - bytes) return false
        requests += 1
        bytes += retainedBytes
        return true
    }

    @Synchronized
    fun release(retainedBytes: Int) {
        check(requests > 0 && retainedBytes in 0..bytes)
        requests -= 1
        bytes -= retainedBytes
    }
}

internal class RuntimeSessionJobs(
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(
        (java.lang.Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4),
    ),
    private val localBudget: RuntimeRequestBudget = RuntimeRequestBudget(32, 16 * 1024 * 1024),
    private val globalBudget: RuntimeRequestBudget = sharedBudget,
) {
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + dispatcher)

    fun launch(retainedBytes: Int = 0, block: suspend () -> Unit): Job? {
        if (!owner.isActive || !localBudget.acquire(retainedBytes)) return null
        if (!globalBudget.acquire(retainedBytes)) {
            localBudget.release(retainedBytes)
            return null
        }
        val job = try {
            scope.launch { block() }
        } catch (failure: Throwable) {
            localBudget.release(retainedBytes)
            globalBudget.release(retainedBytes)
            throw failure
        }
        // Unlike a finally inside the body, this also runs if cancelled before dispatch.
        job.invokeOnCompletion {
            localBudget.release(retainedBytes)
            globalBudget.release(retainedBytes)
        }
        return job
    }

    fun close() {
        scope.cancel(CancellationException("ToolBox runtime session ended"))
    }

    private companion object {
        val sharedBudget = RuntimeRequestBudget(128, 64 * 1024 * 1024)
    }
}

/** Only for error correlation; it must never be used to authorize a request. */
internal fun runtimeRejectedRequestId(encoded: String): String =
    requestIdPrefix.find(encoded.take(512))?.groupValues?.get(1).orEmpty()

private val requestIdPrefix = Regex("""^\s*\{\s*"id"\s*:\s*"([A-Za-z0-9-]{1,128})"\s*[,}]""")
