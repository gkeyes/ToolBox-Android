package io.toolbox.host.runtime

import io.toolbox.tool.runtime.RuntimeLiveNotificationRequest
import io.toolbox.tool.runtime.RuntimeLiveNotificationTone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
        assertEquals(listOf("session-a", "session-b"), coordinator.snapshot().map { it.request.sessionId })
        assertEquals("10.10", coordinator.snapshot().first().request.primaryText)

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
        advanceTimeBy(500)
        runCurrent()
        assertEquals(4, refreshes)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun concurrentToolsOnlyPublishTheirOwnLatestChangesAndEndingDoesNotStopOtherCards() = runTest {
        val a = session("a", 0x550000, 1)
        val b = session("b", 0x550001, 2)
        val c = session("c", 0x550002, 3)
        val active = mutableListOf(a, b)
        val sink = RecordingSink()
        val controller = RuntimeNotificationController(sink)
        lateinit var coordinator: LiveNotificationCoordinator
        coordinator = LiveNotificationCoordinator(this, { testScheduler.currentTime }) {
            controller.render(RuntimeForegroundNotificationSnapshot(active.toList(), coordinator.snapshot(), false))
        }

        coordinator.start(a.toolId, a.toolName, request(a.sessionId, "10.00"))
        coordinator.start(b.toolId, b.toolName, request(b.sessionId, "20.00"))
        advanceTimeBy(500)
        runCurrent()
        assertEquals(setOf(a.notificationId, b.notificationId), sink.visible.keys)
        assertEquals(a.notificationId, sink.carrier)
        assertEquals("10.00", sink.value(a))
        assertEquals("20.00", sink.value(b))

        sink.events.clear()
        coordinator.update(b.toolId, b.toolName, request(b.sessionId, "20.10"))
        coordinator.update(b.toolId, b.toolName, request(b.sessionId, "20.20"))
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("post:${b.notificationId}"), sink.events)
        assertEquals("20.20", sink.value(b))
        assertEquals("10.00", sink.value(a))
        assertEquals(a.notificationId, sink.carrier)

        active += c
        coordinator.start(c.toolId, c.toolName, request(c.sessionId, "30.00"))
        advanceTimeBy(500)
        runCurrent()
        assertEquals(3, sink.visible.size)
        sink.events.clear()
        coordinator.update(a.toolId, a.toolName, request(a.sessionId, "10.99"))
        assertTrue(coordinator.end(a.toolId, a.sessionId))
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("post:${a.notificationId}"), sink.events)
        assertNull(sink.visible.getValue(a.notificationId).presentation)
        assertEquals("20.20", sink.value(b))
        assertEquals("30.00", sink.value(c))
        assertEquals(3, sink.visible.size)

        coordinator.update(b.toolId, b.toolName, request(b.sessionId, "20.30"))
        sink.events.clear()
        coordinator.update(b.toolId, b.toolName, request(b.sessionId, "20.99"))
        active.remove(b)
        coordinator.clearSessions(listOf(b.sessionId))
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("cancel:${b.notificationId}"), sink.events)
        assertEquals(setOf(a.notificationId, c.notificationId), sink.visible.keys)
        assertEquals(a.notificationId, sink.carrier)
    }

    @Test
    fun foregroundCarrierStaysStableUntilStoppedAndTransfersWithoutRemovingTheReplacement() {
        val first = session("first", 0x550010, 1)
        val second = session("second", 0x550011, 2)
        val third = session("third", 0x550012, 3)
        listOf(listOf(first, second), listOf(second, first)).forEach { order ->
            val sink = RecordingSink()
            val controller = RuntimeNotificationController(sink)
            controller.render(snapshot(order.take(1)))
            controller.render(snapshot(order + third))
            assertEquals(order.first().notificationId, sink.carrier)
            assertEquals(3, sink.visible.size)

            sink.events.clear()
            val remaining = listOf(order.last(), third)
            controller.render(snapshot(remaining))
            assertEquals(
                listOf("promote:${order.last().notificationId}", "cancel:${order.first().notificationId}"),
                sink.events,
            )
            assertEquals(remaining.map { it.notificationId }.toSet(), sink.visible.keys)
            assertEquals(order.last().notificationId, sink.carrier)

            sink.events.clear()
            controller.render(snapshot(remaining))
            assertTrue(sink.events.isEmpty())
            controller.render(snapshot(emptyList()))
            assertEquals(
                listOf("stopForeground", "cancel:${order.last().notificationId}", "cancel:${third.notificationId}"),
                sink.events,
            )
            assertFalse(controller.hasForegroundCarrier)
            assertTrue(sink.visible.isEmpty())
        }
    }

    @Test
    fun oneRejectedCardDoesNotBlockOtherCardsOrBecomeFalselyRecordedAsPublished() {
        val a = session("a", 0x550000, 1)
        val b = session("b", 0x550001, 2)
        val c = session("c", 0x550002, 3)
        val sink = RecordingSink()
        val controller = RuntimeNotificationController(sink)
        sink.rejected += b.notificationId
        val snapshot = snapshot(listOf(a, b, c))
        controller.render(snapshot)
        assertEquals(setOf(a.notificationId, c.notificationId), sink.visible.keys)
        sink.rejected.clear()
        sink.events.clear()
        controller.render(snapshot)
        assertEquals(listOf("post:${b.notificationId}"), sink.events)
        assertEquals(setOf(a.notificationId, b.notificationId, c.notificationId), sink.visible.keys)
        assertEquals(a.notificationId, sink.carrier)
    }

    @Test
    fun notificationIdsAreUniqueStableAndDoNotDependOnSessionStringHashes() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val allocator = RuntimeNotificationIds()
        val a = allocator.claim("Aa")
        val b = allocator.claim("BB")
        assertNotEquals(a, b)
        assertEquals(a, allocator.claim("Aa"))
        assertEquals(b, allocator.claim("BB"))
        assertTrue(a >= RuntimeNotificationIds.FIRST_ID)
        assertNotEquals(RuntimeNotificationIds.LEGACY_RUNTIME_ID, a)
        assertNotEquals(0x544259, a)

        val restored = RuntimeNotificationIds()
        assertEquals(b, restored.claim("BB", b))
        assertEquals(a, restored.claim("Aa", a))
        val added = restored.claim("new-session")
        val duplicate = restored.claim("bad-duplicate", a)
        val invalid = restored.claim("bad-id", -1)
        assertEquals(5, setOf(a, b, added, duplicate, invalid).size)
        restored.release("Aa")
        assertEquals(b, restored.claim("BB"))
        assertNotEquals(a, restored.claim("replacement"))
    }

    private fun session(name: String, notificationId: Int, startedAt: Long) = RuntimeBackgroundSessionUi(
        sessionId = "session-$name",
        toolId = "tool-$name",
        toolName = "工具 $name",
        startedAt = startedAt,
        notificationId = notificationId,
    )

    private fun snapshot(sessions: List<RuntimeBackgroundSessionUi>) =
        RuntimeForegroundNotificationSnapshot(sessions, emptyList(), usesLocation = false)

    private class RecordingSink : RuntimeNotificationSink {
        val visible = linkedMapOf<Int, RuntimeNotificationCard>()
        val events = mutableListOf<String>()
        val rejected = mutableSetOf<Int>()
        var carrier: Int? = null

        fun value(session: RuntimeBackgroundSessionUi) =
            visible.getValue(session.notificationId).presentation?.request?.primaryText

        override fun promote(card: RuntimeNotificationCard, usesLocation: Boolean) {
            events += "promote:${card.notificationId}"
            carrier?.takeIf { it != card.notificationId }?.let(visible::remove)
            carrier = card.notificationId
            visible[card.notificationId] = card
        }

        override fun post(card: RuntimeNotificationCard): Boolean {
            events += "post:${card.notificationId}"
            if (card.notificationId in rejected) return false
            visible[card.notificationId] = card
            return true
        }

        override fun cancel(notificationId: Int) {
            events += "cancel:$notificationId"
            visible.remove(notificationId)
        }

        override fun stopForeground() {
            events += "stopForeground"
            carrier?.let(visible::remove)
            carrier = null
        }
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
