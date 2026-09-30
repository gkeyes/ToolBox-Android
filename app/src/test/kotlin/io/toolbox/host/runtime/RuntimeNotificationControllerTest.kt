package io.toolbox.host.runtime

import android.content.pm.ServiceInfo
import io.toolbox.tool.runtime.RuntimeLiveNotificationRequest
import io.toolbox.tool.runtime.RuntimeLiveNotificationTone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeNotificationControllerTest {
    private val emptySnapshot = RuntimeForegroundNotificationSnapshot(emptyList(), emptyList(), usesLocation = false)
    private val activeSnapshot = RuntimeForegroundNotificationSnapshot(
        listOf(RuntimeBackgroundSessionUi("session-1", "com.example.tool", "Example", 1L, 42)),
        emptyList(),
        usesLocation = false,
    )

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

    @Test
    fun newSessionDuringSupportQueryReplacesTheOldEmptySnapshot() = runBlocking {
        val state = MutableStateFlow(emptySnapshot)
        val entered = CompletableDeferred<Unit>()
        val finishQuery = CompletableDeferred<Unit>()
        val presented = mutableListOf<RuntimeForegroundNotificationSnapshot>()
        var stops = 0
        val collector = async {
            presentCurrentRuntimeNotificationSnapshot(
                refreshSupport = { entered.complete(Unit); finishQuery.await() },
                currentSnapshot = { state.value },
                present = { presented += it },
                stopIfEmpty = { stops++ },
            )
        }
        entered.await()
        state.value = activeSnapshot
        finishQuery.complete(Unit)
        collector.await()
        assertEquals(listOf(activeSnapshot), presented)
        assertEquals(0, stops)
    }

    @Test
    fun stopRechecksThePublishedSnapshotAfterPresentation() = runBlocking {
        val state = MutableStateFlow(emptySnapshot)
        var stops = 0
        presentCurrentRuntimeNotificationSnapshot(
            refreshSupport = {},
            currentSnapshot = { state.value },
            present = { state.value = activeSnapshot },
            stopIfEmpty = { stops++ },
        )
        assertEquals(0, stops)

        state.value = emptySnapshot
        presentCurrentRuntimeNotificationSnapshot(
            refreshSupport = {},
            currentSnapshot = { state.value },
            present = {},
            stopIfEmpty = { stops++ },
        )
        assertEquals(1, stops)
    }

    @Test
    fun identicalExplicitTimeSkipsRefreshButOmittedTimeGetsFreshReceipt() = runBlocking {
        var now = 1_000L
        var refreshes = 0
        val coordinator = LiveNotificationCoordinator(this, { now }, { refreshes++ })
        val explicit = liveRequest(updatedAt = 123L)
        coordinator.start("com.example.tool", "Example", explicit)
        val first = coordinator.snapshot().single()
        kotlinx.coroutines.yield()
        assertEquals(1, refreshes)

        now = 2_000L
        coordinator.update("com.example.tool", "Example", explicit)
        kotlinx.coroutines.yield()
        assertEquals(first, coordinator.snapshot().single())
        assertEquals(1, refreshes)

        val omitted = liveRequest(updatedAt = null)
        coordinator.update("com.example.tool", "Example", omitted)
        val second = coordinator.snapshot().single()
        assertEquals(2_000L, second.receivedAt)
        kotlinx.coroutines.yield()
        assertEquals(2, refreshes)

        now = 3_000L
        coordinator.update("com.example.tool", "Example", omitted)
        assertEquals(3_000L, coordinator.snapshot().single().receivedAt)
        kotlinx.coroutines.yield()
        assertEquals(3, refreshes)
    }

    private fun liveRequest(updatedAt: Long?) = RuntimeLiveNotificationRequest(
        sessionId = "session-1",
        title = "Example",
        primaryText = "Running",
        secondaryText = null,
        body = null,
        shortText = null,
        updatedAt = updatedAt,
        progress = null,
        accentColor = null,
        tone = RuntimeLiveNotificationTone.NEUTRAL,
    )

    private class RecordingSink : RuntimeNotificationSink {
        var stopForegroundCalls = 0
        val cancelledIds = mutableListOf<Int>()

        override fun promote(card: RuntimeNotificationCard, usesLocation: Boolean) = Unit
        override fun post(card: RuntimeNotificationCard) = true
        override fun cancel(notificationId: Int) { cancelledIds += notificationId }
        override fun stopForeground() { stopForegroundCalls++ }
    }
}
