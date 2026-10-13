package io.toolbox.host.runtime

import android.app.AlarmManager
import android.content.Context
import io.toolbox.tool.runtime.HardenedRuntimeWebView
import io.toolbox.tool.runtime.RuntimeAlarmHandler
import io.toolbox.tool.runtime.RuntimeAlarmSummary
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Exact alarms: in-memory registry, system scheduling, persistence and delivery. Main thread only. */
internal class RuntimeAlarmService(
    private val appContext: Context,
    private val store: RuntimeSessionStore,
    private val notifications: RuntimeSessionNotifications,
    private val nowMillis: () -> Long,
    private val hostFor: (toolId: String) -> RuntimeHost?,
    private val ensureCurrentRuntime: (toolId: String, versionCode: Int) -> Unit,
) {
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    private val alarmsByTool = mutableMapOf<String, MutableMap<String, StoredAlarm>>()

    val toolIds: Set<String> get() = alarmsByTool.keys

    fun handler(toolId: String, versionCode: Int): RuntimeAlarmHandler = AlarmHandler(toolId, versionCode)

    /** Adds persisted alarms to the registry and registers each with the system again. */
    fun restore(toolId: String, versionCode: Int, alarms: List<StoredAlarm>) {
        alarmsByTool.getOrPut(toolId, ::linkedMapOf).putAll(
            alarms.associateBy(StoredAlarm::alarmId),
        )
        alarms.forEach { scheduleAlarm(toolId, versionCode, it) }
    }

    suspend fun removeAll(toolId: String) {
        alarmsByTool.remove(toolId)?.values?.forEach { cancelScheduledAlarm(toolId, it.alarmId) }
        persistAlarms(toolId)
    }

    suspend fun handleAlarm(toolId: String, versionCode: Int, alarmId: String) {
        val needsLoad = withContext(Dispatchers.Main.immediate) { alarmsByTool[toolId] == null }
        val persisted = if (needsLoad) store.loadAlarms(toolId, versionCode) else emptyList()
        withContext(Dispatchers.Main.immediate) {
            if (persisted.isNotEmpty()) {
                alarmsByTool.getOrPut(toolId, ::linkedMapOf).putAll(persisted.associateBy(StoredAlarm::alarmId))
            }
            val alarm = alarmsByTool[toolId]?.remove(alarmId) ?: return@withContext
            persistAlarms(toolId)
            val payload = alarm.toEvent(nowMillis())
            val delivered = hostFor(toolId)?.let { host ->
                host.versionCode == versionCode && HardenedRuntimeWebView.emitEvent(host.webView, EVENT_ALARM, payload)
            } == true
            if (!delivered) notifications.postAlarmNotification(toolId, alarm)
        }
    }

    private inner class AlarmHandler(
        private val toolId: String,
        private val versionCode: Int,
    ) : RuntimeAlarmHandler {
        override suspend fun schedule(alarm: RuntimeAlarmSummary): RuntimeAlarmSummary =
            withContext(Dispatchers.Main.immediate) {
                ensureCurrentRuntime(toolId, versionCode)
                val manager = alarmManager
                    ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "闹钟服务不可用")
                if (!manager.canScheduleExactAlarms()) {
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED, "请在后台保障中允许闹钟和提醒")
                }
                if (alarm.triggerAt <= nowMillis()) {
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_REQUEST, "闹钟时间必须晚于当前时间")
                }
                val stored = StoredAlarm(
                    alarmId = alarm.alarmId,
                    triggerAt = alarm.triggerAt,
                    scheduledAt = alarm.scheduledAt,
                )
                alarmsByTool.getOrPut(toolId, ::linkedMapOf)[stored.alarmId] = stored
                try {
                    persistAlarms(toolId)
                } catch (failure: Exception) {
                    alarmsByTool[toolId]?.remove(stored.alarmId)
                    throw failure
                }
                if (!scheduleAlarm(toolId, versionCode, stored)) {
                    alarmsByTool[toolId]?.remove(stored.alarmId)
                    persistAlarms(toolId)
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.INTERNAL_ERROR, "精确闹钟未能登记")
                }
                stored.toRuntimeSummary()
            }

        override suspend fun list(): List<RuntimeAlarmSummary> = withContext(Dispatchers.Main.immediate) {
            ensureCurrentRuntime(toolId, versionCode)
            alarmsByTool[toolId].orEmpty().values.map(StoredAlarm::toRuntimeSummary)
        }

        override suspend fun cancel(alarmId: String): Boolean = withContext(Dispatchers.Main.immediate) {
            val removed = alarmsByTool[toolId]?.remove(alarmId) ?: return@withContext false
            cancelScheduledAlarm(toolId, removed.alarmId)
            persistAlarms(toolId)
            true
        }
    }

    private fun scheduleAlarm(toolId: String, versionCode: Int, alarm: StoredAlarm): Boolean {
        val manager = alarmManager ?: return false
        if (!manager.canScheduleExactAlarms()) return false
        return runCatching {
            manager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                alarm.triggerAt,
                RuntimeAlarmReceiver.pendingIntent(appContext, toolId, versionCode, alarm.alarmId),
            )
        }.isSuccess
    }

    private fun cancelScheduledAlarm(toolId: String, alarmId: String) {
        alarmManager?.cancel(RuntimeAlarmReceiver.pendingIntent(appContext, toolId, 0, alarmId))
    }

    private suspend fun persistAlarms(toolId: String) {
        store.persistAlarms(toolId, alarmsByTool[toolId].orEmpty().values.toList())
    }

    private companion object {
        const val EVENT_ALARM = "alarm"
    }
}
