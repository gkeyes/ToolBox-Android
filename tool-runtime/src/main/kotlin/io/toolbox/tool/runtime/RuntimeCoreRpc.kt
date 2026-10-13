package io.toolbox.tool.runtime

import java.security.MessageDigest

fun interface RuntimeToastHandler {
    suspend fun show(message: String)
}

fun interface RuntimeDeviceBasicHandler {
    suspend fun getBasicInfo(): RuntimeBasicDeviceInfo
}

data class RuntimeBasicDeviceInfo(
    val apiLevel: Int,
    val locale: String,
    val timeZone: String,
    val screenClass: String,
)

fun interface RuntimeHapticsHandler {
    suspend fun perform(effect: String)
}

internal suspend fun RuntimeRpcDispatcher.invokeCore(
    method: String,
    params: RpcValue.ObjectValue,
): RpcValue = when (method) {
    "ui.toast" -> {
        requireHandler(handlers.toast).show(params.requiredString("message", rpcMaxResponseBytes))
        RpcValue.Null
    }
    "crypto.sha256" -> RpcValue.ObjectValue(
        mapOf("hex" to RpcValue.StringValue(sha256(params.requiredBytes("value", rpcMaxResponseBytes)))),
    )
    "device.getBasicInfo" -> requireHandler(handlers.deviceBasic).getBasicInfo().toRpcValue()
    "haptics.perform" -> {
        val effect = params.requiredString("effect", 16)
        require(effect in setOf("click", "confirm", "reject"))
        requireHandler(handlers.haptics).perform(effect)
        RpcValue.Null
    }
    else -> noNativeHandler()
}

private fun RuntimeBasicDeviceInfo.toRpcValue(): RpcValue.ObjectValue {
    require(apiLevel >= 33)
    require(locale.isNotBlank())
    require(timeZone.isNotBlank())
    require(screenClass in setOf("compact", "medium", "expanded"))
    return RpcValue.ObjectValue(
        mapOf(
            "apiLevel" to RpcValue.Number(apiLevel.toDouble()),
            "locale" to RpcValue.StringValue(locale),
            "timeZone" to RpcValue.StringValue(timeZone),
            "screenClass" to RpcValue.StringValue(screenClass),
        ),
    )
}

private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString("") { "%02x".format(it) }
