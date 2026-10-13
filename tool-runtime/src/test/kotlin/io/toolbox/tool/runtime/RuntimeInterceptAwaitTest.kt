package io.toolbox.tool.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeInterceptAwaitTest {
    @Test
    fun slowResultIsReturnedWithoutADeadline() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            assertEquals("body", awaitInterceptResult(scope) { delay(300); "body" })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failureIsRethrownToTheWaitingThread() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val thrown = runCatching { awaitInterceptResult<String>(scope) { error("upstream failed") } }.exceptionOrNull()
            assertTrue(thrown is IllegalStateException && thrown.message == "upstream failed")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun releasingTheWebViewFreesAWaitingLoaderThread() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val started = CompletableDeferred<Unit>()
        val outcome = AtomicReference<Throwable?>()
        val finished = CountDownLatch(1)
        val loader = Thread {
            outcome.set(runCatching {
                awaitInterceptResult(scope) { started.complete(Unit); CompletableDeferred<String>().await() }
            }.exceptionOrNull())
            finished.countDown()
        }
        loader.start()
        kotlinx.coroutines.runBlocking { started.await() }
        scope.cancel()
        assertTrue("The waiting thread must be released", finished.await(5, TimeUnit.SECONDS))
        assertTrue(outcome.get() is Exception)
    }
}
