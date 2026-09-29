package io.toolbox.host.runtime

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeNotificationControllerTest {
    @Test
    fun foregroundServiceUsesLocationTypeOnlyWhenSnapshotRequiresIt() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            runtimeForegroundServiceTypes(usesLocation = false),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            runtimeForegroundServiceTypes(usesLocation = true),
        )
    }

    @Test
    fun removingLastSessionStopsForegroundCarrier() {
        val sink = RecordingSink()
        val controller = RuntimeNotificationController(sink)
        val session = RuntimeBackgroundSessionUi(
            sessionId = "session-1",
            toolId = "com.example.tool",
            toolName = "Example",
            startedAt = 1L,
            notificationId = 42,
        )

        controller.render(RuntimeForegroundNotificationSnapshot(listOf(session), emptyList(), usesLocation = false))
        assertTrue(controller.hasForegroundCarrier)

        controller.render(RuntimeForegroundNotificationSnapshot(emptyList(), emptyList(), usesLocation = false))

        assertFalse(controller.hasForegroundCarrier)
        assertEquals(1, sink.stopForegroundCalls)
        assertEquals(listOf(42), sink.cancelledIds)
    }

    private class RecordingSink : RuntimeNotificationSink {
        var stopForegroundCalls = 0
        val cancelledIds = mutableListOf<Int>()

        override fun promote(card: RuntimeNotificationCard, usesLocation: Boolean) = Unit
        override fun post(card: RuntimeNotificationCard) = true
        override fun cancel(notificationId: Int) { cancelledIds += notificationId }
        override fun stopForeground() { stopForegroundCalls++ }
    }
}
