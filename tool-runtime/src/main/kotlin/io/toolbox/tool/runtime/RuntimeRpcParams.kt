package io.toolbox.tool.runtime

import io.toolbox.core.data.ResourceCapacity
import java.nio.charset.StandardCharsets

/** Bridge message capacity: UTF-16 chars that fit in currently available heap. Re-read on every access. */
internal val rpcMaxResponseBytes: Int
    get() = (ResourceCapacity.availableHeapBytes() / Char.SIZE_BYTES).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()

internal fun noNativeHandler(): Nothing =
    throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "Method has no native handler")

internal fun <T> requireHandler(handler: T?): T = handler ?: throw RuntimeHandlerException(
    RuntimeRpcErrorCode.UNSUPPORTED,
    "This native capability is unavailable",
)

internal fun RpcValue.ObjectValue.required(name: String): RpcValue =
    value[name] ?: throw IllegalArgumentException("Missing $name")

internal fun RpcValue.ObjectValue.requireOnly(vararg allowed: String) {
    require(value.keys.all { it in allowed })
}

internal fun RpcValue.ObjectValue.requiredString(name: String, maxChars: Int = Int.MAX_VALUE): String {
    val result = (required(name) as? RpcValue.StringValue)?.value ?: throw IllegalArgumentException(name)
    require(result.length in 1..maxChars)
    return result
}

internal fun RpcValue.ObjectValue.requiredText(name: String, maxChars: Int = Int.MAX_VALUE): String {
    val text = (required(name) as? RpcValue.StringValue)?.value ?: throw IllegalArgumentException(name)
    require(text.length <= maxChars)
    return text
}

internal fun RpcValue.ObjectValue.requiredIdentifier(name: String): String {
    val identifier = requiredString(name)
    require(identifier == identifier.trim())
    require(identifier.none(Char::isISOControl))
    return identifier
}

internal fun RpcValue.ObjectValue.requiredTaskId(): String =
    requiredIdentifier("taskId")

internal fun RpcValue.ObjectValue.requiredSessionId(): String =
    requiredIdentifier("sessionId")

internal fun requireTaskId(taskId: String) {
    requireIdentifier(taskId)
}

internal fun requireIdentifier(value: String) {
    require(value.isNotEmpty())
    require(value == value.trim())
    require(value.none(Char::isISOControl))
}

internal fun RpcValue.ObjectValue.optionalString(name: String, maxChars: Int = Int.MAX_VALUE): String? {
    val raw = value[name] ?: return null
    val string = (raw as? RpcValue.StringValue)?.value ?: throw IllegalArgumentException(name)
    require(string.length <= maxChars)
    return string
}

internal fun RpcValue.ObjectValue.requiredLong(name: String, min: Long, max: Long): Long {
    val number = (required(name) as? RpcValue.Number)?.value ?: throw IllegalArgumentException(name)
    require(number.isFinite() && number % 1.0 == 0.0)
    require(number >= min.toDouble() && number <= max.toDouble())
    return number.toLong()
}

internal fun RpcValue.ObjectValue.optionalLong(name: String, min: Long, max: Long): Long? =
    if (name in value) requiredLong(name, min, max) else null

internal fun RpcValue.ObjectValue.optionalInt(name: String, min: Int, max: Int): Int? =
    optionalLong(name, min.toLong(), max.toLong())?.toInt()

internal fun RpcValue.ObjectValue.optionalBoolean(name: String): Boolean? = when (val raw = value[name]) {
    null -> null
    is RpcValue.Bool -> raw.value
    else -> throw IllegalArgumentException(name)
}

internal fun RpcValue.ObjectValue.optionalFloat(name: String, min: Float): Float? {
    val number = (value[name] ?: return null) as? RpcValue.Number ?: throw IllegalArgumentException(name)
    require(number.value.isFinite() && number.value >= min.toDouble() && number.value <= Float.MAX_VALUE)
    return number.value.toFloat()
}

internal fun RpcValue.ObjectValue.requiredObject(name: String): RpcValue.ObjectValue =
    required(name) as? RpcValue.ObjectValue ?: throw IllegalArgumentException(name)

internal fun RpcValue.ObjectValue.optionalDisplayName(name: String): String? {
    val result = optionalString(name)?.trim()
    require(result == null || result.isNotEmpty())
    return result
}

internal fun RpcValue.ObjectValue.requiredDisplayText(name: String): String =
    requiredString(name).also { require(it.isNotBlank() && it.none(Char::isISOControl)) }

internal fun RpcValue.ObjectValue.optionalDisplayText(name: String): String? =
    value[name]?.let { requiredText(name).also { text -> require(text.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) } }

internal fun RpcValue.ObjectValue.requiredBytes(name: String, maxBytes: Int): ByteArray = when (val raw = required(name)) {
    is RpcValue.StringValue -> {
        require(raw.value.length <= maxBytes)
        raw.value.toByteArray(StandardCharsets.UTF_8)
    }
    // Current SDK: { base64 } decoded once.
    is RpcValue.ObjectValue -> decodeRuntimeBase64Bytes(raw, maxBytes) ?: throw IllegalArgumentException(name)
    // Older SDK copies sent one JSON number per byte; still accepted.
    is RpcValue.ArrayValue -> {
        require(raw.value.size <= maxBytes)
        ByteArray(raw.value.size) { index ->
            val number = (raw.value[index] as? RpcValue.Number)?.value ?: throw IllegalArgumentException(name)
            require(number % 1.0 == 0.0 && number in 0.0..255.0)
            number.toInt().toByte()
        }
    }
    else -> throw IllegalArgumentException(name)
}.also { require(it.size <= maxBytes) }

internal const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
