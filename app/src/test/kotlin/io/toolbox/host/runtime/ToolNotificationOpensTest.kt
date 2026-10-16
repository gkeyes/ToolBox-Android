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
        ToolNotificationOpens.deliver("tool.a") { delivered += it; true }
        ToolNotificationOpens.deliver("tool.a") { delivered += it; true }

        assertEquals(listOf(ToolNotificationOpen("feed-1", 1), ToolNotificationOpen("feed-2", 3)), delivered)
        val other = mutableListOf<ToolNotificationOpen>()
        ToolNotificationOpens.deliver("tool.b") { other += it; true }
        assertEquals(listOf(ToolNotificationOpen("x", 2)), other)
    }

    @Test fun repeatedTapOnOneNotificationBeforeLoadIsOneOpen() {
        ToolNotificationOpens.record("tool.a", "feed-1", openedAt = 1)
        ToolNotificationOpens.record("tool.a", "feed-1", openedAt = 4)

        val delivered = mutableListOf<ToolNotificationOpen>()
        ToolNotificationOpens.deliver("tool.a") { delivered += it; true }

        assertEquals(listOf(ToolNotificationOpen("feed-1", 4)), delivered)
    }

    @Test fun undeliveredTapsStayForTheNextLoad() {
        ToolNotificationOpens.record("tool.a", "n1", openedAt = 1)
        ToolNotificationOpens.record("tool.a", "n2", openedAt = 2)

        ToolNotificationOpens.deliver("tool.a") { it.notificationId == "n1" }
        val retried = mutableListOf<String>()
        ToolNotificationOpens.deliver("tool.a") { retried += it.notificationId; true }

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
}
