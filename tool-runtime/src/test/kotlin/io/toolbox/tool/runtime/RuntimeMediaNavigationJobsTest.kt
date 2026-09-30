package io.toolbox.tool.runtime

import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RuntimeMediaNavigationJobsTest {
    @Test
    fun navigationCancelsPendingRpcAndAllowsTheNewDocumentToCall() = runBlocking {
        val jobs = RuntimeSessionJobs()
        try {
            val entered = CompletableDeferred<Unit>()
            val cleaned = CompletableDeferred<Unit>()
            val old = requireNotNull(jobs.launch {
                try { entered.complete(Unit); awaitCancellation() } finally { cleaned.complete(Unit) }
            })
            withTimeout(2_000) { entered.await() }
            jobs.cancelPending()
            withTimeout(2_000) { old.join(); cleaned.await() }
            assertTrue(old.isCancelled)
            val delivered = CompletableDeferred<Unit>()
            val fresh = requireNotNull(jobs.launch { delivered.complete(Unit) })
            withTimeout(2_000) { fresh.join(); delivered.await() }
            assertFalse(fresh.isCancelled)
        } finally { jobs.close() }
    }

    @Test
    fun queuedOldDocumentRpcDoesNotReachTheHandlerAfterNavigation() {
        val queue = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        }
        val jobs = RuntimeSessionJobs(dispatcher = dispatcher)
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
        try {
            var staleOpened = false
            val stale = requireNotNull(jobs.launch { staleOpened = true })
            jobs.cancelPending()
            drain()
            assertTrue(stale.isCancelled)
            assertFalse(staleOpened)
            var freshOpened = false
            val fresh = requireNotNull(jobs.launch { freshOpened = true })
            drain()
            assertTrue(fresh.isCompleted)
            assertTrue(freshOpened)
        } finally { jobs.close(); drain() }
    }
}
