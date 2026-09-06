package io.toolbox.tool.runtime

data class RuntimeNetworkStreamResponse(val streamId: String, val status: Int, val headers: Map<String, String>)

data class RuntimeNetworkStreamChunk(val data: ByteArray, val done: Boolean, val receivedBytes: Long)

internal fun runtimeNetworkStreamRawBudget(requestId: String, maxResponseBytes: Int): Int {
    require(isSafeRuntimeRequestId(requestId))
    require(maxResponseBytes >= 4 * 1024)
    return minOf(16 * 1024, (maxResponseBytes - 160 - requestId.length) / 4 * 3)
}

internal fun isNetworkStreamId(value: String): Boolean =
    value.length == 39 && value.startsWith("stream-") && value.drop(7).all { it in '0'..'9' || it in 'a'..'f' }
