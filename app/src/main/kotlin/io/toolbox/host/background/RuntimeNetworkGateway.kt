package io.toolbox.host.background

import io.toolbox.host.runtime.NetworkStreamCancellation
import io.toolbox.tool.packagekit.InstalledManifestNetwork
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeNetworkBodyEncoding
import io.toolbox.tool.runtime.RuntimeNetworkHandler
import io.toolbox.tool.runtime.RuntimeNetworkRequest
import io.toolbox.tool.runtime.RuntimeNetworkResponse
import io.toolbox.tool.runtime.RuntimeNetworkStreamResponse
import io.toolbox.tool.runtime.RuntimeNetworkStreamChunk
import io.toolbox.tool.runtime.RuntimeNetworkMediaSession
import io.toolbox.tool.runtime.RuntimeNetworkMediaResponse
import java.io.ByteArrayInputStream
import java.io.PushbackInputStream
import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class RuntimeNetworkGateway(
    private val proxy: ToolNetworkProxy,
    private val policy: InstalledManifestNetwork?,
    private val validateNetworkAccess: suspend () -> Unit = {},
    private val toolId: String? = null,
    private val origin: String? = null,
) : RuntimeNetworkHandler {
    private val streams = RuntimeNetworkStreams()
    private val media = RuntimeNetworkMediaSessions(origin)
    init { toolId?.let { NetworkStreamCancellation.register(it, this, ::cancelStreams) } }

    override suspend fun openStream(streamId: String, request: RuntimeNetworkRequest): RuntimeNetworkStreamResponse {
        val limit = request.maxResponseBytes ?: policy?.maxResponseBytes
        val timeout = request.timeoutMillis ?: policy?.timeoutMs?.toLong() ?: DEFAULT_TIMEOUT_MILLIS
        val control = streams.reserve(streamId, timeout)
        try {
            validateNetworkAccess()
            val stream = proxy.openStream(
                ToolNetworkRequest(request.url, NetworkRequestMethod.valueOf(request.method.name), request.headers,
                    request.body, request.bodyIsJson,
                    timeout, limit, resourceOwner = toolId ?: "foreground"),
                control,
            )
            streams.attach(streamId, control, stream)
            return RuntimeNetworkStreamResponse(streamId, stream.response.code, stream.response.exposedHeaders() +
                ("x-toolbox-final-url" to stream.finalUrl))
        } catch (error: CancellationException) {
            streams.release(streamId, control)
            throw error
        } catch (error: IOException) {
            streams.release(streamId, control)
            throw error.toStreamFailure().toRuntimeStreamFailure()
        } catch (error: Exception) {
            streams.release(streamId, control)
            throw error
        }
    }

    override suspend fun readStream(streamId: String, maxChunkBytes: Int): RuntimeNetworkStreamChunk {
        validateNetworkAccess()
        val stream = streams.get(streamId)
        try {
            val chunk = stream.read(maxChunkBytes)
            if (chunk.done) streams.finish(streamId, stream)
            return RuntimeNetworkStreamChunk(chunk.data, chunk.done, chunk.receivedBytes, chunk.release)
        } catch (error: CancellationException) {
            streams.finish(streamId, stream)
            throw error
        } catch (error: IOException) {
            if (error !is ToolNetworkFailure || error.code != "STREAM_BUSY") streams.finish(streamId, stream)
            throw error.toStreamFailure().toRuntimeStreamFailure()
        }
    }

    override suspend fun cancelStream(streamId: String) = streams.cancel(streamId)

    override fun cancelStreams() {
        streams.clear()
        media.clear()
    }

    override fun close() {
        toolId?.let { NetworkStreamCancellation.unregister(it, this) }
        streams.close()
        media.clear(end = true)
    }

    override suspend fun openMedia(sessionId: String, url: String, kind: String?): RuntimeNetworkMediaSession {
        require(sessionId.matches(Regex("media-[0-9a-f]{32}")))
        require(kind == null || kind in setOf("audio", "video"))
        val endpoint = url.toHttpUrlOrNull() ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_REQUEST, "媒体地址无效。")
        if (runCatching { URI(url).rawUserInfo != null }.getOrDefault(true) || NetworkPolicy.validateEndpoint(endpoint) != null) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.NETWORK_BLOCKED, "媒体地址仅支持不含凭据的 HTTPS。")
        }
        currentCoroutineContext().ensureActive()
        val entry = media.reserve(sessionId, endpoint.toString(), kind)
        return try {
            validateMediaAccess()
            currentCoroutineContext().ensureActive()
            media.requireCurrent(entry)
            media.session(entry)
        } catch (error: Exception) { media.cancel(entry); throw error }
    }

    override suspend fun closeMedia(sessionId: String) {
        require(sessionId.matches(Regex("media-[0-9a-f]{32}")))
        media.cancel(sessionId)
    }

    override suspend fun interceptMedia(url: String, method: String, headers: Map<String, String>): RuntimeNetworkMediaResponse? {
        val entry = media.find(url) ?: return null
        val requestHeaders = RuntimeMediaHttpPolicy.requestHeaders(method, headers)
        val control = media.request(entry)
        try {
            validateMediaAccess()
            media.requireCurrent(entry)
            // Playback has no implicit whole-response timeout or full-body materialisation.
            val stream = proxy.openStream(ToolNetworkRequest(entry.source, NetworkRequestMethod.valueOf(method),
                requestHeaders, null, false, timeoutMillis = 0, maxResponseBytes = policy?.maxResponseBytes,
                resourceOwner = toolId ?: "foreground"), control)
            validateMediaAccess()
            media.requireCurrent(entry)
            control.requireActive()
            val response = stream.response
            if (response.code == 416) {
                val contentRange = response.header("Content-Range")?.takeIf { it.matches(Regex("bytes \\*/[0-9]+")) }
                    ?: throw ToolNetworkFailure("MEDIA_RESPONSE")
                media.finish(entry, control)
                return RuntimeNetworkMediaResponse(416, "Range Not Satisfiable", "application/octet-stream",
                    mapOf("Content-Range" to contentRange, "Content-Length" to "0", "Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
            }
            if (response.code !in setOf(200, 206)) throw ToolNetworkFailure("MEDIA_RESPONSE")
            val exposed = RuntimeMediaHttpPolicy.responseHeaders(response.code, response.headers.toMap())
            val type = response.header("Content-Type")
            // Reject markup before reading, then inspect at most 32 bytes for otherwise untyped binary media.
            var mime = RuntimeMediaHttpPolicy.mimeType(type, entry.kind)
            val input = PushbackInputStream(response.body.byteStream(), 32)
            if (mime == "application/octet-stream") mime = media.mime(entry) ?: mime
            if (method == "GET" && mime == "application/octet-stream") {
                val prefix = ByteArray(32)
                var size = 0
                while (size < prefix.size) {
                    control.requireActive()
                    val count = input.read(prefix, size, prefix.size - size)
                    if (count < 0) break
                    size += count
                }
                control.requireActive()
                mime = RuntimeMediaHttpPolicy.mimeType(type, entry.kind, prefix.copyOf(size))
                input.unread(prefix, 0, size)
                media.rememberMime(entry, mime)
            }
            media.requireCurrent(entry)
            control.requireActive()
            val body = if (method == "HEAD") {
                media.finish(entry, control)
                ByteArrayInputStream(ByteArray(0))
            } else RuntimeMediaInputStream(input, control, policy?.maxResponseBytes) { media.finish(entry, control) }
            return RuntimeNetworkMediaResponse(response.code, if (response.code == 206) "Partial Content" else "OK", mime, exposed, body)
        } catch (error: Exception) {
            media.finish(entry, control)
            throw error
        }
    }

    private suspend fun validateMediaAccess() {
        try { validateNetworkAccess() } catch (error: Exception) { cancelStreams(); throw error }
    }

    override suspend fun request(request: RuntimeNetworkRequest): RuntimeNetworkResponse {
        validateNetworkAccess()
        val responseLimit = request.maxResponseBytes ?: policy?.maxResponseBytes
        val timeout = request.timeoutMillis ?: policy?.timeoutMs?.toLong() ?: DEFAULT_TIMEOUT_MILLIS
        // The proxy owns the deadline. Track the active call for revocation without retiring an internal ID.
        val control = streams.registerRequest()
        val result = try {
            proxy.requestWithControl(ToolNetworkRequest(
                request.url, NetworkRequestMethod.valueOf(request.method.name), request.headers,
                request.body, request.bodyIsJson, timeout, responseLimit,
                resourceOwner = toolId ?: "foreground",
            ), control)
        } finally { streams.releaseRequest(control) }
        return when (result) {
            is NetworkExecution.Success -> RuntimeNetworkResponse(
                status = result.statusCode,
                headers = buildMap {
                    putAll(result.headers)
                    if (keys.none { it.equals("content-type", ignoreCase = true) }) {
                        result.contentType?.let { put("content-type", it) }
                    }
                    put("x-toolbox-final-url", result.finalUrl)
                },
                body = result.body,
                release = result.release,
                bodyEncoding = when (result.bodyEncoding) {
                    NetworkBodyEncoding.TEXT -> RuntimeNetworkBodyEncoding.TEXT
                    NetworkBodyEncoding.BASE64 -> RuntimeNetworkBodyEncoding.BASE64
                },
            )
            is NetworkExecution.RetryableFailure -> throw RuntimeHandlerException(
                if (result.errorCode == "NETWORK_TIMEOUT") RuntimeRpcErrorCode.NETWORK_TIMEOUT
                else RuntimeRpcErrorCode.NETWORK_UNAVAILABLE,
                if (result.errorCode == "NETWORK_TIMEOUT") "网络请求超时，请稍后重试。"
                else "网络连接或响应读取失败，请检查网络后重试。",
            )
            is NetworkExecution.TerminalFailure -> throw when (result.errorCode) {
                "NETWORK_TIMEOUT" -> RuntimeHandlerException(RuntimeRpcErrorCode.NETWORK_TIMEOUT, "网络请求超时，请稍后重试。")
                "RESOURCE_UNAVAILABLE" -> RuntimeHandlerException(RuntimeRpcErrorCode.NETWORK_UNAVAILABLE, "网络执行环境暂不可用，请稍后重试。")
                "CANCELLED" -> RuntimeHandlerException(RuntimeRpcErrorCode.CANCELLED, "网络请求已取消。")
                "PROXY_AUTHENTICATION_REQUIRED" -> RuntimeHandlerException(RuntimeRpcErrorCode.NETWORK_UNAVAILABLE, "系统代理要求认证（407），请在系统或代理端配置。")
                "INSUFFICIENT_MEMORY" -> RuntimeHandlerException(
                    RuntimeRpcErrorCode.QUOTA_EXCEEDED,
                    "当前可用内存不足，请使用分块流读取或释放内存后重试。",
                )
                "RESULT_TOO_LARGE" -> RuntimeHandlerException(
                    RuntimeRpcErrorCode.QUOTA_EXCEEDED,
                    "网络响应超过本次请求或可用内存预算；请使用分块流读取。",
                )
                "INVALID_TIMEOUT", "INVALID_RESPONSE_LIMIT", "INVALID_URL" -> RuntimeHandlerException(
                    RuntimeRpcErrorCode.INVALID_REQUEST,
                    "网络请求参数无效（${result.errorCode}）。",
                )
                else -> RuntimeHandlerException(
                    RuntimeRpcErrorCode.NETWORK_BLOCKED,
                    when (result.errorCode) {
                        "HTTPS_REQUIRED" -> "仅支持 HTTPS 网络请求。"
                        else -> "网络地址或重定向未通过检查（${result.errorCode}）。"
                    },
                )
            }
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 0L
    }
}

private fun ToolNetworkFailure.toRuntimeStreamFailure(): RuntimeHandlerException = when (code) {
    "INSUFFICIENT_MEMORY" -> RuntimeHandlerException(RuntimeRpcErrorCode.QUOTA_EXCEEDED, "当前可用网络缓冲内存不足，请稍后重试。")
    "PROXY_AUTHENTICATION_REQUIRED" -> RuntimeHandlerException(RuntimeRpcErrorCode.NETWORK_UNAVAILABLE, "系统代理要求认证（407），请在系统或代理端配置。")
    "RESULT_TOO_LARGE" -> RuntimeHandlerException(RuntimeRpcErrorCode.QUOTA_EXCEEDED, "网络流累计响应超过请求或 manifest 上限。")
    "CANCELLED" -> RuntimeHandlerException(RuntimeRpcErrorCode.CANCELLED, "网络流已取消。")
    "STREAM_BUSY" -> RuntimeHandlerException(RuntimeRpcErrorCode.BUSY, "同一网络流请按顺序读取。")
    "NETWORK_TIMEOUT" -> RuntimeHandlerException(RuntimeRpcErrorCode.NETWORK_TIMEOUT, "网络流读取超时，请重试。")
    "RESOURCE_UNAVAILABLE" -> RuntimeHandlerException(RuntimeRpcErrorCode.NETWORK_UNAVAILABLE, "网络执行环境暂不可用，请稍后重试。")
    "NETWORK_IO" -> RuntimeHandlerException(RuntimeRpcErrorCode.NETWORK_UNAVAILABLE, "网络流连接或读取失败，请重试。")
    "INVALID_TIMEOUT", "INVALID_RESPONSE_LIMIT", "INVALID_URL" -> RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_REQUEST, "网络流请求参数无效。")
    else -> RuntimeHandlerException(RuntimeRpcErrorCode.NETWORK_BLOCKED, "网络流地址或重定向无效；请检查 HTTPS 地址与服务器配置。")
}
