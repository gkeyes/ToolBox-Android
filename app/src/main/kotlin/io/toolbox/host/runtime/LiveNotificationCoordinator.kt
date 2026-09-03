package io.toolbox.host.runtime

import io.toolbox.tool.runtime.RuntimeLiveNotificationRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class RuntimeLiveNotificationUi(
    val toolId: String,
    val toolName: String,
    val request: RuntimeLiveNotificationRequest,
    val receivedAt: Long,
    val sequence: Long,
)

internal data class RuntimeForegroundNotificationSnapshot(
    val sessions: List<RuntimeBackgroundSessionUi>,
    val presentations: List<RuntimeLiveNotificationUi>,
    val usesLocation: Boolean,
) {
    fun cards(): List<RuntimeNotificationCard> {
        val bySession = presentations.associateBy { it.request.sessionId }
        return sessions.sortedWith(compareBy(RuntimeBackgroundSessionUi::startedAt, RuntimeBackgroundSessionUi::sessionId))
            .map { session -> RuntimeNotificationCard(session, bySession[session.sessionId]) }
    }
}

internal data class RuntimeNotificationCard(
    val session: RuntimeBackgroundSessionUi,
    val presentation: RuntimeLiveNotificationUi?,
) {
    val notificationId: Int get() = session.notificationId
}

internal class LiveNotificationCoordinator(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val onSnapshotChanged: () -> Unit,
) {
    private val presentations = linkedMapOf<String, RuntimeLiveNotificationUi>()
    private var sequence = 0L
    private var lastRefreshAt: Long? = null
    private var pendingRefresh: Job? = null

    fun start(toolId: String, toolName: String, request: RuntimeLiveNotificationRequest) {
        val receivedAt = nowMillis()
        sequence = maxOf(sequence + 1L, receivedAt)
        presentations[request.sessionId] = RuntimeLiveNotificationUi(
            toolId = toolId,
            toolName = toolName,
            request = request,
            receivedAt = receivedAt,
            sequence = sequence,
        )
        scheduleRefresh()
    }

    fun update(toolId: String, toolName: String, request: RuntimeLiveNotificationRequest): Boolean {
        if (presentations[request.sessionId]?.toolId != toolId) return false
        start(toolId, toolName, request)
        return true
    }

    fun end(toolId: String, sessionId: String): Boolean {
        if (presentations[sessionId]?.toolId != toolId) return false
        presentations.remove(sessionId)
        scheduleRefresh(immediate = true)
        return true
    }

    fun clearSessions(sessionIds: Collection<String>) {
        if (sessionIds.any(presentations::containsKey)) {
            sessionIds.forEach(presentations::remove)
            scheduleRefresh(immediate = true)
        }
    }

    fun snapshot(): List<RuntimeLiveNotificationUi> = presentations.values.toList()

    private fun scheduleRefresh(immediate: Boolean = false) {
        val now = nowMillis()
        val last = lastRefreshAt
        val elapsed = last?.let { (now - it).coerceAtLeast(0L) }
        if (immediate || elapsed == null || elapsed >= REFRESH_COALESCE_MILLIS) {
            pendingRefresh?.cancel()
            pendingRefresh = null
            lastRefreshAt = now
            onSnapshotChanged()
            return
        }
        if (pendingRefresh?.isActive == true) return
        pendingRefresh = scope.launch {
            delay((REFRESH_COALESCE_MILLIS - elapsed).coerceAtLeast(1L))
            pendingRefresh = null
            lastRefreshAt = nowMillis()
            onSnapshotChanged()
        }
    }

    private companion object {
        const val REFRESH_COALESCE_MILLIS = 500L
    }
}
