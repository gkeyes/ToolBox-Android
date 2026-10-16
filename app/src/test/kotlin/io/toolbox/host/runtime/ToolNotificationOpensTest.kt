package io.toolbox.host.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ToolNotificationOpensTest {
    @Before @After fun reset() = ToolNotificationOpens.resetForTest()

    @Test fun deliversTapsInOrderOnceAndOnlyToTheirTool() {
        ToolNotificationOpens.record("tool.a", "feed-1", openedAt = 1)
        ToolNotificationOpens.record("tool.b", "x", openedAt = 2)
        ToolNotificationOpens.record("tool.a", "feed-2", openedAt = 3)

        val delivered = mutableListOf<ToolNotificationOpen>()
        ToolNotificationOpens.deliver("tool.a", now = 10) { delivered += it; true }
        ToolNotificationOpens.deliver("tool.a", now = 10) { delivered += it; true }

        assertEquals(listOf("feed-1" to 1L, "feed-2" to 3L), delivered.map { it.notificationId to it.openedAt })
        val other = mutableListOf<ToolNotificationOpen>()
        ToolNotificationOpens.deliver("tool.b", now = 10) { other += it; true }
        assertEquals(listOf("x" to 2L), other.map { it.notificationId to it.openedAt })
    }

    @Test fun repeatedTapOnOneNotificationBeforeLoadIsOneOpen() {
        ToolNotificationOpens.record("tool.a", "feed-1", openedAt = 1)
        ToolNotificationOpens.record("tool.a", "feed-1", openedAt = 4)

        val delivered = mutableListOf<ToolNotificationOpen>()
        ToolNotificationOpens.deliver("tool.a", now = 10) { delivered += it; true }

        assertEquals(listOf("feed-1" to 4L), delivered.map { it.notificationId to it.openedAt })
    }

    @Test fun undeliveredTapsStayForTheNextLoad() {
        ToolNotificationOpens.record("tool.a", "n1", openedAt = 1)
        ToolNotificationOpens.record("tool.a", "n2", openedAt = 2)

        ToolNotificationOpens.deliver("tool.a", now = 10) { it.notificationId == "n1" }
        val retried = mutableListOf<String>()
        ToolNotificationOpens.deliver("tool.a", now = 10) { retried += it.notificationId; true }

        assertEquals(listOf("n2"), retried)
    }

    @Test fun listenersHearTheTappedTool() {
        val heard = mutableListOf<String>()
        val remove = ToolNotificationOpens.addListener { heard += it }
        ToolNotificationOpens.record("tool.a", "n1")
        remove()
        ToolNotificationOpens.record("tool.b", "n1")

        assertEquals(listOf("tool.a"), heard)
    }

    @Test fun aTapThePageNeverReceivedExpires() {
        // The wall clock jumps back an hour between the taps; expiry follows the monotonic clock.
        ToolNotificationOpens.record("tool.a", "old", openedAt = 5_000_000, tappedAtMonotonic = 1_000)
        ToolNotificationOpens.record("tool.a", "new", openedAt = 1_400_000, tappedAtMonotonic = 1_000 + ToolNotificationOpens.MAX_AGE_MS)

        val delivered = mutableListOf<String>()
        ToolNotificationOpens.deliver("tool.a", now = 1_001 + ToolNotificationOpens.MAX_AGE_MS) { delivered += it.notificationId; true }

        assertEquals(listOf("new"), delivered)
    }

    @Test fun discardedAndOversizedTapsAreNeverDelivered() {
        ToolNotificationOpens.record("tool.a", "n1", openedAt = 1)
        ToolNotificationOpens.discard("tool.a")
        ToolNotificationOpens.record("tool.b", "x".repeat(257), openedAt = 1)

        val delivered = mutableListOf<String>()
        ToolNotificationOpens.deliver("tool.a", now = 10) { delivered += it.notificationId; true }
        ToolNotificationOpens.deliver("tool.b", now = 10) { delivered += it.notificationId; true }

        assertEquals(emptyList<String>(), delivered)
    }
}
