package io.toolbox.tool.runtime

data class RuntimeLocationResult(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val capturedAt: Long,
)

fun interface RuntimeLocationHandler {
    suspend fun getCurrent(precise: Boolean, timeoutMillis: Long): RuntimeLocationResult
}

data class RuntimeLocationWatchOptions(
    val precise: Boolean,
    val intervalMillis: Long,
    val minDistanceMeters: Float,
    val allowBackground: Boolean,
)

interface RuntimeLocationWatchHandler {
    suspend fun watch(options: RuntimeLocationWatchOptions): String
    suspend fun clearWatch(watchId: String): Boolean
}

internal suspend fun RuntimeRpcDispatcher.invokeLocation(
    method: String,
    params: RpcValue.ObjectValue,
): RpcValue = when (method) {
    "location.getCurrent" -> {
        params.requireOnly("accuracy", "timeoutMs")
        val precise = when (params.optionalString("accuracy", 16) ?: "coarse") {
            "coarse" -> false
            "precise" -> true
            else -> throw IllegalArgumentException("accuracy")
        }
        requireHandler(systemHandlers.location).getCurrent(
            precise = precise,
            timeoutMillis = params.optionalLong("timeoutMs", MIN_LOCATION_TIMEOUT_MS, MAX_SAFE_INTEGER)
                ?: DEFAULT_LOCATION_TIMEOUT_MS,
        ).toRpcValue()
    }
    "location.watch" -> {
        params.requireOnly("accuracy", "intervalMs", "minDistanceMeters", "allowBackground")
        val precise = when (params.optionalString("accuracy", 16) ?: "coarse") {
            "coarse" -> false
            "precise" -> true
            else -> throw IllegalArgumentException("accuracy")
        }
        RpcValue.StringValue(
            requireHandler(systemHandlers.locationWatch).watch(
                RuntimeLocationWatchOptions(
                    precise = precise,
                    intervalMillis = params.optionalLong("intervalMs", 0, MAX_SAFE_INTEGER) ?: 0,
                    minDistanceMeters = params.optionalFloat("minDistanceMeters", 0f) ?: 0f,
                    allowBackground = params.optionalBoolean("allowBackground") ?: false,
                ),
            ).also { requireIdentifier(it) },
        )
    }
    "location.clearWatch" -> {
        params.requireOnly("watchId")
        val removed = requireHandler(systemHandlers.locationWatch).clearWatch(
            params.requiredIdentifier("watchId"),
        )
        if (!removed) throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "Location watch was not found")
        RpcValue.Null
    }
    else -> noNativeHandler()
}

private fun RuntimeLocationResult.toRpcValue(): RpcValue.ObjectValue {
    require(latitude.isFinite() && latitude in -90.0..90.0)
    require(longitude.isFinite() && longitude in -180.0..180.0)
    require(accuracyMeters.isFinite() && accuracyMeters >= 0.0)
    require(capturedAt in 0..MAX_SAFE_INTEGER)
    return RpcValue.ObjectValue(
        mapOf(
            "latitude" to RpcValue.Number(latitude),
            "longitude" to RpcValue.Number(longitude),
            "accuracyMeters" to RpcValue.Number(accuracyMeters),
            "capturedAt" to RpcValue.Number(capturedAt.toDouble()),
        ),
    )
}

private const val MIN_LOCATION_TIMEOUT_MS = 0L
private const val DEFAULT_LOCATION_TIMEOUT_MS = 0L
