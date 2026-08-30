package io.toolbox.host.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeReminderPolicyTest {
    @Test
    fun reminderRepeatsEveryTwelveHoursFromLastDelivery() {
        val startedAt = 1_700_000_000_000L
        val first = RuntimeReminderPolicy.nextReminderAt(startedAt, lastReminderAt = null)
        val second = RuntimeReminderPolicy.nextReminderAt(startedAt, lastReminderAt = first)

        assertEquals(RuntimeReminderPolicy.INTERVAL_MILLIS, first - startedAt)
        assertEquals(RuntimeReminderPolicy.INTERVAL_MILLIS, second - first)
    }
}
