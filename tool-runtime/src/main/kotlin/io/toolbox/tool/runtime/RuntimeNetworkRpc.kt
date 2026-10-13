package io.toolbox.tool.runtime

import io.toolbox.tool.api.ToolBoxApiV1
import io.toolbox.tool.api.ToolBoxCapabilityId
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

fun interface RuntimeNetworkHandler {
    suspend fun request(request: RuntimeNetworkRequest): RuntimeNetworkResponse
    suspend fun openStream(streamId: String, request: RuntimeNetworkRequest): RuntimeNetworkStreamResponse =
        throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "Network streaming is unavailable")
    suspend fun readStream(streamId: String, maxChunkBytes: Int): RuntimeNetworkStreamChunk =
        throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "Network streaming is unavailable")
    suspend fun cancelStream(streamId: String): Unit =
        throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "Network streaming is unavailable")
    suspend fun openMedia(sessionId: String, url: String, kind: String? = null): RuntimeNetworkMediaSession =
        throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "Network media is unavailable")
    suspend fun closeMedia(sessionId: String): Unit =
        throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "Network media is unavailable")
    suspend fun interceptMedia(url: String, method: String, headers: Map<String, String>): RuntimeNetworkMediaResponse? = null
    fun cancelStreams() = Unit
    fun close() = Unit
}

enum class RuntimeNetworkMethod { GET, POST, PUT, PATCH, DELETE, HEAD }

data class RuntimeNetworkRequest(
    val url: String,
    val method: RuntimeNetworkMethod,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val bodyIsJson: Boolean = false,
    val timeoutMillis: Long? = null,
    val maxResponseBytes: Long? = null,
)

data class RuntimeNetworkResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
    val bodyEncoding: RuntimeNetworkBodyEncoding = RuntimeNetworkBodyEncoding.TEXT,
    val release: () -> Unit = {},
)

enum class RuntimeNetworkBodyEncoding { TEXT, BASE64 }

internal suspend fun RuntimeRpcDispatcher.invokeNetwork(
    method: String,
    params: RpcValue.ObjectValue,
    requestId: String,
    retained: MutableList<() -> Unit>,
    onEncodedResponse: (EncodedRuntimeResponse) -> Unit,
): RpcValue = when (method) {
    "network.request" -> {
        val response = requireHandler(serviceHandlers.network).request(params.toNetworkRequest())
        retained += response.release
        requireNetworkStreamAuthorization()
        response.toRpcValue()
    }
    "network.openStream" -> {
        params.requireOnly("streamId", "request")
        val streamId = params.requiredNetworkStreamId()
        val networkRequest = (params.required("request") as? RpcValue.ObjectValue
            ?: throw IllegalArgumentException("request")).toNetworkRequest()
        val network = requireHandler(serviceHandlers.network)
        val response = network.openStream(streamId, networkRequest)
        try {
            require(response.streamId == streamId)
            requireNetworkStreamAuthorization()
            val headers = RuntimeNetworkResponse(response.status, response.headers, "").toRpcValue().value.getValue("headers")
            streamResponseWithinBudget(requestId, RpcValue.ObjectValue(mapOf(
                "streamId" to RpcValue.StringValue(streamId),
                "status" to RpcValue.Number(response.status.toDouble()),
                "headers" to headers,
            )), onEncodedResponse)
        } catch (error: Exception) {
            runCatching { network.cancelStream(streamId) }
            throw error
        }
    }
    "network.readStream" -> {
        params.requireOnly("streamId", "expectedChunkBytes")
        val streamId = params.requiredNetworkStreamId()
        val expected = params.optionalLong("expectedChunkBytes", 1, MAX_SAFE_INTEGER)
        val rawBudget = runtimeNetworkStreamRawBudget(requestId, rpcMaxResponseBytes, expected)
        val network = requireHandler(serviceHandlers.network)
        val chunk = network.readStream(streamId, rawBudget)
        retained += chunk.release
        run {
            requireNetworkStreamAuthorization()
            require(chunk.receivedBytes in 0..MAX_SAFE_INTEGER)
            require(chunk.data.size <= rawBudget)
            streamResponseWithinBudget(requestId, RpcValue.ObjectValue(mapOf(
                "data" to RpcValue.StringValue(Base64.getEncoder().encodeToString(chunk.data)),
                "done" to RpcValue.Bool(chunk.done),
                "receivedBytes" to RpcValue.Number(chunk.receivedBytes.toDouble()),
            )), onEncodedResponse)
        }
    }
    "network.cancelStream" -> {
        params.requireOnly("streamId")
        requireHandler(serviceHandlers.network).cancelStream(params.requiredNetworkStreamId())
        RpcValue.Null
    }
    "network.openMedia" -> {
        params.requireOnly("sessionId", "url", "kind")
        val sessionId = params.requiredNetworkMediaSessionId()
        val url = params.requiredNetworkMediaUrl()
        val kind = params.optionalString("kind", 5).also { require(it == null || it in setOf("audio", "video")) }
        val network = requireHandler(serviceHandlers.network)
        try {
            currentCoroutineContext().ensureActive()
            val session = network.openMedia(sessionId, url, kind)
            currentCoroutineContext().ensureActive()
            require(session.sessionId == sessionId)
            require(RuntimeNetworkMediaRoute.token(session.url, identity.exactOrigin) != null)
            requireNetworkStreamAuthorization()
            RpcValue.ObjectValue(mapOf(
                "sessionId" to RpcValue.StringValue(sessionId),
                "url" to RpcValue.StringValue(session.url),
            ))
        } catch (error: Exception) {
            // A duplicate ID did not create a session; cleanup must not close its existing owner.
            if (error !is RuntimeHandlerException || error.errorCode != RuntimeRpcErrorCode.BUSY) {
                withContext(NonCancellable) { runCatching { network.closeMedia(sessionId) } }
            }
            throw error
        }
    }
    "network.closeMedia" -> {
        params.requireOnly("sessionId")
        requireHandler(serviceHandlers.network).closeMedia(params.requiredNetworkMediaSessionId())
        RpcValue.Null
    }
    else -> noNativeHandler()
}

private suspend fun RuntimeRpcDispatcher.requireNetworkStreamAuthorization() {
    val code = when {
        !authorization.isCurrent(identity) -> RuntimeRpcErrorCode.INVALID_SESSION
        !authorization.isGranted(identity, ToolBoxCapabilityId.NETWORK) -> RuntimeRpcErrorCode.PERMISSION_DENIED
        !authorization.hasSystemPermissions(identity, ToolBoxApiV1.capability(ToolBoxCapabilityId.NETWORK).systemPermissions) -> RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED
        !authorization.isCurrent(identity) -> RuntimeRpcErrorCode.INVALID_SESSION
        else -> return
    }
    serviceHandlers.network?.cancelStreams()
    throw RuntimeHandlerException(code, "Network stream authorization changed before delivery")
}

private fun streamResponseWithinBudget(
    requestId: String,
    value: RpcValue,
    onEncodedResponse: (EncodedRuntimeResponse) -> Unit,
): RpcValue {
    val encoded = RuntimeRpcJson.prepareResponse(RuntimeRpcResponse.Success(requestId, value))
    if (encoded.streamUpperBoundBytes > rpcMaxResponseBytes) {
        throw RuntimeHandlerException(RuntimeRpcErrorCode.QUOTA_EXCEEDED, "Network stream response exceeds the bridge message limit")
    }
    onEncodedResponse(encoded)
    return value
}

private fun RpcValue.ObjectValue.requiredNetworkStreamId(): String =
    requiredString("streamId", 39).also { require(isNetworkStreamId(it)) }

private fun RpcValue.ObjectValue.requiredNetworkMediaSessionId(): String =
    requiredString("sessionId", 38).also { require(isNetworkMediaId(it)) }

private fun RpcValue.ObjectValue.requiredNetworkMediaUrl(): String =
    requiredString("url", rpcMaxResponseBytes).also { url ->
        require(url == url.trim() && url.none(Char::isISOControl))
        val uri = runCatching { URI(url) }.getOrNull() ?: throw IllegalArgumentException("url")
        require(uri.scheme?.lowercase(Locale.ROOT) == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null)
    }

private fun RuntimeNetworkResponse.toRpcValue(): RpcValue.ObjectValue {
    require(status in 100..599)
    val rpcHeaders = headers.entries.associate { (name, value) ->
        require(HEADER_NAME.matches(name))
        require(name.lowercase(Locale.ROOT) !in FORBIDDEN_RESPONSE_HEADERS)
        name to RpcValue.StringValue(value)
    }
    return RpcValue.ObjectValue(
        mapOf(
            "status" to RpcValue.Number(status.toDouble()),
            "headers" to RpcValue.ObjectValue(rpcHeaders),
            "body" to RpcValue.StringValue(body),
            "bodyEncoding" to RpcValue.StringValue(bodyEncoding.name.lowercase(Locale.ROOT)),
        ),
    )
}

private fun RpcValue.ObjectValue.toNetworkRequest(): RuntimeNetworkRequest {
    requireOnly("url", "method", "headers", "body", "bodyEncoding", "timeoutMs", "maxResponseBytes")
    val url = requiredString("url", rpcMaxResponseBytes)
    require(url == url.trim() && url.none(Char::isISOControl))
    val method = when (optionalString("method", 6) ?: "GET") {
        "GET" -> RuntimeNetworkMethod.GET
        "POST" -> RuntimeNetworkMethod.POST
        "PUT" -> RuntimeNetworkMethod.PUT
        "PATCH" -> RuntimeNetworkMethod.PATCH
        "DELETE" -> RuntimeNetworkMethod.DELETE
        "HEAD" -> RuntimeNetworkMethod.HEAD
        else -> throw IllegalArgumentException("method")
    }
    val headers = (value["headers"] as? RpcValue.ObjectValue)?.value?.mapValues { (name, raw) ->
        require(REQUEST_HEADER_NAME.matches(name))
        require(name.lowercase(Locale.ROOT) !in FORBIDDEN_REQUEST_HEADERS)
        val headerValue = (raw as? RpcValue.StringValue)?.value ?: throw IllegalArgumentException("headers")
        require(headerValue.none { it == '\r' || it == '\n' || it.isISOControl() })
        headerValue
    }.orEmpty()
    val rawBody = value["body"]
    val bodyEncoding = optionalString("bodyEncoding", 8)
    val body = when {
        bodyEncoding == "bytes" -> requiredBytes("body", rpcMaxResponseBytes)
        bodyEncoding != null -> throw IllegalArgumentException("bodyEncoding")
        rawBody == null -> null
        rawBody is RpcValue.StringValue -> rawBody.value.toByteArray(StandardCharsets.UTF_8)
        else -> RuntimeRpcJson.encodeValue(rawBody).toByteArray(StandardCharsets.UTF_8)
    }
    body?.let { require(it.size <= rpcMaxResponseBytes) }
    require(method !in setOf(RuntimeNetworkMethod.GET, RuntimeNetworkMethod.HEAD) || body == null)
    return RuntimeNetworkRequest(
        url = url,
        method = method,
        headers = headers,
        body = body,
        bodyIsJson = bodyEncoding == null && rawBody != null && rawBody !is RpcValue.StringValue,
        timeoutMillis = optionalLong("timeoutMs", MIN_NETWORK_TIMEOUT_MS, MAX_NETWORK_TIMEOUT_MS),
        maxResponseBytes = optionalLong(
            "maxResponseBytes",
            MIN_NETWORK_RESPONSE_BYTES.toLong(),
            MAX_SAFE_INTEGER,
        ),
    )
}

private val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
private val REQUEST_HEADER_NAME = HEADER_NAME
private val FORBIDDEN_RESPONSE_HEADERS = setOf("set-cookie", "set-cookie2", "proxy-authenticate")
private val FORBIDDEN_REQUEST_HEADERS = setOf(
    "connection", "content-length", "host", "proxy-authorization", "proxy-connection", "te", "trailer",
    "transfer-encoding", "upgrade",
)

private const val MIN_NETWORK_RESPONSE_BYTES = 1
private const val MIN_NETWORK_TIMEOUT_MS = 0L
// OkHttp represents timeout milliseconds as a non-negative Int.
private const val MAX_NETWORK_TIMEOUT_MS = Int.MAX_VALUE.toLong()
