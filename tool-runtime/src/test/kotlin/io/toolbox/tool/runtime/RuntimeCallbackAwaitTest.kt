package io.toolbox.tool.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCallbackAwaitTest {
    @Test
    fun callbackCompletesTheWait() = runBlocking {
        assertTrue(awaitPlatformCallback(30_000) { done -> done(); done() })
    }

    @Test
    fun lostCallbackTimesOutAsFailure() = runBlocking {
        assertFalse(awaitPlatformCallback(50) { })
    }

    @Test
    fun registrationFailureIsAFailure() = runBlocking {
        assertFalse(awaitPlatformCallback(30_000) { throw IllegalStateException("unsupported") })
    }
}
