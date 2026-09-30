package io.toolbox.tool.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RuntimeEventBufferTest {
    @Test
    fun eventsStayInOrderAndOnlyPostedAcknowledgedEventsReleaseCapacity() {
        val budget = RuntimeEventBudget { 512L }
        val buffer = RuntimeEventBuffer(budget)
        repeat(3) { assertTrue(buffer.offer { sequence -> "event-$sequence" }) }
        assertFalse(buffer.offer { sequence -> "event-$sequence" })
        assertEquals(3, buffer.retainedCount())
        assertEquals(3L * ("event-1".length * 2 + 128).toLong(), budget.reservedBytes())

        assertEquals(1L, buffer.poll()?.sequence)
        expectInvalidAck { buffer.acknowledge(2) }
        buffer.acknowledge(1)
        assertEquals(2, buffer.retainedCount())
        assertTrue(buffer.offer { sequence -> "event-$sequence" })
        assertEquals(listOf(2L, 3L, 4L), listOfNotNull(buffer.poll(), buffer.poll(), buffer.poll()).map { it.sequence })
        assertNull(buffer.poll())
        buffer.acknowledge(4)
        assertEquals(0, buffer.retainedCount())
        assertEquals(0L, budget.reservedBytes())
    }

    @Test
    fun logicalEightMiBBudgetIsSharedAndCloseReleasesAllCredits() {
        val budget = RuntimeEventBudget { Long.MAX_VALUE }
        assertTrue(budget.acquire(RuntimeEventBudget.GLOBAL_BYTES))
        assertFalse(budget.acquire(1))
        budget.release(RuntimeEventBudget.GLOBAL_BYTES)

        val smallBudget = RuntimeEventBudget { 200L }
        val first = RuntimeEventBuffer(smallBudget)
        val second = RuntimeEventBuffer(smallBudget)
        assertTrue(first.offer { "one" })
        assertFalse(second.offer { "two" })
        first.close()
        assertTrue(second.offer { "two" })
        assertFalse(first.offer { "after-close" })
        second.close()
        assertEquals(0L, smallBudget.reservedBytes())
    }

    @Test
    fun perSessionCapacityStopsAt4096EventsAndOneMiB() {
        val countBudget = RuntimeEventBudget { RuntimeEventBudget.GLOBAL_BYTES }
        val counted = RuntimeEventBuffer(countBudget)
        repeat(RuntimeEventBuffer.MAX_EVENTS) { assertTrue(counted.offer { "e" }) }
        assertFalse(counted.offer { "overflow" })
        assertEquals(RuntimeEventBuffer.MAX_EVENTS, counted.retainedCount())
        counted.close()
        assertEquals(0L, countBudget.reservedBytes())

        val byteBudget = RuntimeEventBudget { RuntimeEventBudget.GLOBAL_BYTES }
        val bytes = RuntimeEventBuffer(byteBudget)
        assertFalse(bytes.offer { "x".repeat((RuntimeEventBuffer.MAX_BYTES / 2).toInt()) })
        assertEquals(0, bytes.retainedCount())
        assertEquals(0L, byteBudget.reservedBytes())
    }

    @Test
    fun navigationReleasesOldCreditsWithoutClosingOrReusingSequenceNumbers() {
        val budget = RuntimeEventBudget { 512L }
        val buffer = RuntimeEventBuffer(budget)
        assertTrue(buffer.offer { sequence -> "event-$sequence" })
        assertTrue(buffer.offer { sequence -> "event-$sequence" })
        val first = buffer.poll()
        assertEquals(1L, first?.sequence)
        assertTrue(budget.reservedBytes() > 0)

        buffer.resetForNavigation()
        assertEquals(0L, budget.reservedBytes())
        assertEquals(0, buffer.retainedCount())
        assertNull(buffer.poll())
        buffer.acknowledge(1)

        assertTrue(buffer.offer { sequence -> "event-$sequence" })
        assertEquals(3L, buffer.poll()?.sequence)
        buffer.acknowledge(2) // The old document's last sequence cannot release this event.
        assertEquals(1, buffer.retainedCount())
        buffer.acknowledge(3)
        assertEquals(0L, budget.reservedBytes())
    }

    private fun expectInvalidAck(action: () -> Unit) {
        try {
            action()
            fail("Expected invalid acknowledgement")
        } catch (_: IllegalArgumentException) {
        }
    }
}
