package io.toolbox.host.background

import io.toolbox.host.runtime.RuntimeBackgroundSessionUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BackgroundTasksPresentationTest {
    @Test
    fun activeContinuousRuntimePreventsTheEmptyBackgroundState() {
        val page = backgroundTasksPageModel(
            toolId = "io.toolbox.stockmonitor",
            tasks = emptyList(),
            sessions = listOf(
                RuntimeBackgroundSessionUi(
                    sessionId = "session-a",
                    toolId = "io.toolbox.stockmonitor",
                    toolName = "行情哨兵",
                    startedAt = 1_000,
                    notificationId = 0x550000,
                ),
                RuntimeBackgroundSessionUi(
                    sessionId = "other-session",
                    toolId = "io.toolbox.other",
                    toolName = "其他工具",
                    startedAt = 2_000,
                    notificationId = 0x550001,
                ),
            ),
        )

        assertFalse(page.isEmpty)
        assertEquals(listOf("session-a"), page.runtimeSessions.map { it.sessionId })
    }
}
