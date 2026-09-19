package io.toolbox.tool.runtime

data class RuntimeNetworkStreamResponse(val streamId: String, val status: Int, val headers: Map<String, String>)

data class RuntimeNetworkStreamChunk(
    val data: ByteArray, val done: Boolean, val receivedBytes: Long,
    /** Returns shared buffer capacity after bridge encoding; idempotent. */
    val release: () -> Unit = {},
)

internal fun runtimeNetworkStreamRawBudget(requestId: String, maxResponseBytes: Int, expectedChunkBytes: Long? = null): Int {
    require(isSafeRuntimeRequestId(requestId))
    require(maxResponseBytes >= 4 * 1024)
    require(expectedChunkBytes == null || expectedChunkBytes in 1..9_007_199_254_740_991L)
    val bridgeRawBytes = (maxResponseBytes - 160 - requestId.length).toLong() / 4 * 3
    return minOf(expectedChunkBytes ?: (64L * 1024), bridgeRawBytes).toInt()
}

internal fun isNetworkStreamId(value: String): Boolean =
    value.length == 39 && value.startsWith("stream-") && value.drop(7).all { it in '0'..'9' || it in 'a'..'f' }
