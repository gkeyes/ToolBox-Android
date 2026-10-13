package io.toolbox.tool.runtime

data class RuntimeAlarmSummary(
    val alarmId: String,
    val triggerAt: Long,
    val scheduledAt: Long,
)

interface RuntimeAlarmHandler {
    suspend fun schedule(alarm: RuntimeAlarmSummary): RuntimeAlarmSummary
    suspend fun list(): List<RuntimeAlarmSummary>
    suspend fun cancel(alarmId: String): Boolean
}

internal suspend fun RuntimeRpcDispatcher.invokeAlarms(
    method: String,
    params: RpcValue.ObjectValue,
): RpcValue = when (method) {
    "alarms.schedule" -> {
        params.requireOnly("id", "triggerAt")
        val now = System.currentTimeMillis()
        requireHandler(serviceHandlers.alarms).schedule(
            RuntimeAlarmSummary(
                alarmId = params.requiredIdentifier("id"),
                triggerAt = params.requiredLong("triggerAt", 0, MAX_SAFE_INTEGER),
                scheduledAt = now,
            ),
        ).toRpcValue()
    }
    "alarms.list" -> {
        params.requireOnly()
        RpcValue.ArrayValue(requireHandler(serviceHandlers.alarms).list().map { it.toRpcValue() })
    }
    "alarms.cancel" -> {
        params.requireOnly("id")
        val cancelled = requireHandler(serviceHandlers.alarms).cancel(
            params.requiredIdentifier("id"),
        )
        if (!cancelled) throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "Alarm was not found")
        RpcValue.Null
    }
    else -> noNativeHandler()
}

private fun RuntimeAlarmSummary.toRpcValue(): RpcValue.ObjectValue {
    requireIdentifier(alarmId)
    require(triggerAt in 0..MAX_SAFE_INTEGER)
    require(scheduledAt in 0..MAX_SAFE_INTEGER)
    return RpcValue.ObjectValue(
        mapOf(
            "id" to RpcValue.StringValue(alarmId),
            "triggerAt" to RpcValue.Number(triggerAt.toDouble()),
            "scheduledAt" to RpcValue.Number(scheduledAt.toDouble()),
        ),
    )
}
