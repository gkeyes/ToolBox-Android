package io.toolbox.tool.runtime

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RuntimePresentationCoordinatorTest {
    @Test
    fun admittedWriteBeforeCloseRemainsInTheCutoffAndNewWorkIsSealedAfterFlush() = runBlocking {
        val coordinator = RuntimePresentationCoordinator("generation") { 100L }
        assertTrue(coordinator.admitOrdinaryRequest()) // Reserved before the bridge parses JSON.
        val ticket = coordinator.beginClose()
        assertEquals(2_100L, ticket.deadline)
        assertTrue(coordinator.admitOrdinaryRequest()) // Existing close may still save through ordinary admission.
        coordinator.completeFlush(ticket.token, saved = true)
        assertTrue(ticket.result.await())
        assertFalse(coordinator.admitOrdinaryRequest())
        coordinator.checkMethodAvailable("storage.set")
        expectCode(RuntimeRpcErrorCode.SESSION_ENDED) { coordinator.checkMethodAvailable("ui.toast") }

        val drained = async { coordinator.awaitWrites() }
        yield()
        assertFalse(drained.isCompleted)
        coordinator.releaseOrdinaryRequest()
        assertFalse(drained.isCompleted)
        coordinator.releaseOrdinaryRequest()
        drained.await()
    }

    @Test
    fun cancellingCloseInvalidatesOldTokenAndCreatesANewRevision() {
        var now = 1_000L
        val coordinator = RuntimePresentationCoordinator("generation") { now }
        assertEquals(0L, coordinator.state().revision)
        assertTrue(coordinator.setForeground(true))
        assertFalse(coordinator.setForeground(true))
        assertEquals(1L, coordinator.state().revision)

        val old = coordinator.beginClose()
        assertEquals(2L, coordinator.state().revision)
        assertTrue(coordinator.state().closing)
        coordinator.cancelClose()
        assertEquals(3L, coordinator.state().revision)
        assertFalse(coordinator.state().closing)
        assertFalse(old.result.getCompletedSafely())
        assertTrue(coordinator.admitOrdinaryRequest())
        coordinator.releaseOrdinaryRequest()
        expectCode(RuntimeRpcErrorCode.CANCELLED) { coordinator.completeFlush(old.token, true) }

        val replacement = coordinator.beginClose()
        assertNotEquals(old.token, replacement.token)
        assertEquals(4L, coordinator.state().revision)
        now = 1_500L
        coordinator.completeFlush(replacement.token, true)
        assertTrue(replacement.result.getCompletedSafely())
    }

    @Test
    fun closingPausesNativeEventProductionUntilCancelled() = runBlocking {
        val coordinator = RuntimePresentationCoordinator("generation") { 100L }
        val close = coordinator.beginClose()
        val resume = async { coordinator.awaitOpenForEvents() }
        yield()
        assertFalse(resume.isCompleted)

        coordinator.cancelClose(close)
        resume.await()
        assertFalse(coordinator.state().closing)
        // A stale close attempt must not pause a recovered session again.
        coordinator.cancelClose(close)
        coordinator.awaitOpenForEvents()

        coordinator.beginClose()
        val ending = async { coordinator.awaitOpenForEvents() }
        yield()
        assertFalse(ending.isCompleted)
        coordinator.release()
        ending.await()
    }

    @Test
    fun externalCloseHasTwoSecondDeadlineWhileForegroundCloseHasNone() {
        var now = 500L
        val coordinator = RuntimePresentationCoordinator("generation") { now }
        val external = coordinator.beginClose(RuntimePresentationCoordinator.CLOSE_TIMEOUT_MILLIS)
        now = 2_501L
        expectCode(RuntimeRpcErrorCode.CANCELLED) { coordinator.completeFlush(external.token, true) }
        coordinator.cancelClose()

        val foreground = coordinator.beginClose(timeoutMillis = null)
        assertEquals(null, foreground.deadline)
        now = Long.MAX_VALUE / 2
        coordinator.completeFlush(foreground.token, true)
        assertTrue(foreground.result.getCompletedSafely())
    }

    private fun expectCode(code: RuntimeRpcErrorCode, action: () -> Unit) {
        try {
            action()
            fail("Expected $code")
        } catch (error: RuntimeHandlerException) {
            assertEquals(code, error.errorCode)
        }
    }

    private fun kotlinx.coroutines.CompletableDeferred<Boolean>.getCompletedSafely(): Boolean = runBlocking { await() }
}
