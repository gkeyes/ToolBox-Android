package io.toolbox.host.background

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BackgroundExecutionConcurrencyTest {
    @Test fun slowNetworkDoesNotBlockAnotherTaskOrCancellation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val network = async {
            BackgroundExecutionLimiter.run("network", BackgroundExecutionLimiter.newToken()) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val notification = async {
            BackgroundExecutionLimiter.run("notification", BackgroundExecutionLimiter.newToken()) {
                BackgroundExecutionLimiter.lockTool("same-tool") { "posted" }
            }
        }
        assertEquals("posted", notification.await())
        BackgroundExecutionLimiter.cancelExecution("network")
        network.join()
        assertTrue(network.isCancelled)
        assertFalse(release.isCompleted)
        // The cancelled run releases its admission record.
        assertEquals("again", BackgroundExecutionLimiter.run("network", BackgroundExecutionLimiter.newToken()) { "again" })
    }
    @Test fun retriesAreFiniteExceptionHandling() {
        assertTrue((1..3).all(BackgroundRetryPolicy::shouldRetry))
        assertFalse(BackgroundRetryPolicy.shouldRetry(4))
    }
}
