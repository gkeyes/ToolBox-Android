package io.toolbox.host.runtime

import io.toolbox.tool.runtime.RuntimeLiveNotificationRequest
import io.toolbox.tool.runtime.RuntimeLiveNotificationTone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveNotificationCoordinatorTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun coalescesUpdatesKeepsLatestSnapshotAndCleansSessions() = runTest {
        var refreshes = 0
        val coordinator = LiveNotificationCoordinator(
            scope = this,
            nowMillis = { testScheduler.currentTime },
            onSnapshotChanged = { refreshes += 1 },
        )

        coordinator.start("tool-a", "工具 A", request("session-a", "10.00"))
        coordinator.start("tool-a", "工具 A", request("session-a", "10.10"))
        coordinator.start("tool-b", "工具 B", request("session-b", "20.00"))

        assertEquals(1, refreshes)
        assertEquals(listOf("session-b", "session-a"), coordinator.snapshot().map { it.request.sessionId })
        assertEquals("10.10", coordinator.snapshot().last().request.primaryText)

        advanceTimeBy(500)
        runCurrent()
        assertEquals(2, refreshes)

        assertFalse(coordinator.update("tool-a", "工具 A", request("unknown", "0")))
        assertTrue(coordinator.update("tool-a", "工具 A", request("session-a", "10.20")))
        assertEquals("session-a", coordinator.snapshot().first().request.sessionId)

        assertTrue(coordinator.end("tool-b", "session-b"))
        assertEquals(3, refreshes)
        assertEquals(listOf("session-a"), coordinator.snapshot().map { it.request.sessionId })

        coordinator.clearSessions(listOf("session-a"))
        assertEquals(4, refreshes)
        assertTrue(coordinator.snapshot().isEmpty())
    }

    private fun request(sessionId: String, value: String) = RuntimeLiveNotificationRequest(
        sessionId = sessionId,
        title = "行情",
        primaryText = value,
        secondaryText = null,
        body = null,
        shortText = value,
        updatedAt = null,
        progress = null,
        accentColor = null,
        tone = RuntimeLiveNotificationTone.NEUTRAL,
    )
}
