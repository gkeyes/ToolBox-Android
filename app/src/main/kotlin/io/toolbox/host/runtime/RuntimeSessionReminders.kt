package io.toolbox.host.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Posts the periodic "still running" reminder for each background session. Main thread only. */
internal class RuntimeSessionReminders(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val sessionsByTool: Map<String, MutableMap<String, StoredRuntimeSession>>,
    private val notifications: RuntimeSessionNotifications,
    private val persistSessions: suspend (toolId: String) -> Unit,
) {
    private val reminderJobs = mutableMapOf<String, Job>()

    fun schedule(record: StoredRuntimeSession) {
        cancel(record.sessionId)
        reminderJobs[record.sessionId] = scope.launch {
            var nextAt = RuntimeReminderPolicy.nextReminderAt(record.startedAt, record.lastReminderAt)
            while (isActive) {
                delay((nextAt - nowMillis()).coerceAtLeast(1))
                val owner = sessionsByTool.entries.firstOrNull { record.sessionId in it.value }?.key ?: return@launch
                notifications.postRuntimeReminder(owner, record.sessionId)
                val updated = sessionsByTool[owner]?.get(record.sessionId)?.copy(lastReminderAt = nowMillis())
                    ?: return@launch
                sessionsByTool[owner]?.set(record.sessionId, updated)
                persistSessions(owner)
                nextAt = RuntimeReminderPolicy.nextReminderAt(updated.startedAt, updated.lastReminderAt)
            }
        }
    }

    fun cancel(sessionId: String) {
        reminderJobs.remove(sessionId)?.cancel()
    }
}
