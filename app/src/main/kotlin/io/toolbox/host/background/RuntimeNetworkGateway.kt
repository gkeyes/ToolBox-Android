package io.toolbox.host.background

import io.toolbox.host.runtime.NetworkDomainInvalidation
import io.toolbox.tool.packagekit.InstalledManifestNetwork
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeNetworkBodyEncoding
import io.toolbox.tool.runtime.RuntimeNetworkHandler
import io.toolbox.tool.runtime.RuntimeNetworkRequest
import io.toolbox.tool.runtime.RuntimeNetworkResponse
import io.toolbox.tool.runtime.RuntimeNetworkStreamResponse
import io.toolbox.tool.runtime.RuntimeNetworkStreamChunk
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import java.io.IOException
import kotlinx.coroutines.CancellationException

internal class RuntimeNetworkGateway(
    private val proxy: ToolNetworkProxy,
    private val policy: InstalledManifestNetwork?,
    private val validateNetworkAccess: suspend () -> Unit = {},
    private val toolId: String? = null,
) : RuntimeNetworkHandler {
    private val streams = RuntimeNetworkStreams()
    init { toolId?.let { NetworkDomainInvalidation.register(it, this, streams::clear) } }

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

    override fun cancelStreams() = streams.clear()

    override fun close() {
        toolId?.let { NetworkDomainInvalidation.unregister(it, this) }
        streams.close()
    }

    override suspend fun request(request: RuntimeNetworkRequest): RuntimeNetworkResponse {
        validateNetworkAccess()
        val responseLimit = request.maxResponseBytes ?: policy?.maxResponseBytes
        val timeout = request.timeoutMillis ?: policy?.timeoutMs?.toLong() ?: DEFAULT_TIMEOUT_MILLIS
        val requestId = "request-${java.util.UUID.randomUUID()}"
        val control = streams.reserve(requestId, timeout)
        val result = try {
            proxy.requestWithControl(ToolNetworkRequest(
                request.url, NetworkRequestMethod.valueOf(request.method.name), request.headers,
                request.body, request.bodyIsJson, timeout, responseLimit,
                resourceOwner = toolId ?: "foreground",
            ), control)
        } finally { streams.release(requestId, control) }
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
                "NETWORK_TIMEOUT" -> RuntimeHandlerException(RuntimeRpcErrorCode.NETWORK_TIMEOUT, "网络请求超时，请稍后重试。"),
                "RESOURCE_UNAVAILABLE" -> RuntimeHandlerException(RuntimeRpcErrorCode.NETWORK_UNAVAILABLE, "网络执行环境暂不可用，请稍后重试。"),
                "CANCELLED" -> RuntimeHandlerException(RuntimeRpcErrorCode.CANCELLED, "网络请求已取消。"),
                "PROXY_AUTHENTICATION_REQUIRED" -> RuntimeHandlerException(RuntimeRpcErrorCode.NETWORK_UNAVAILABLE, "系统代理要求认证（407），请在系统或代理端配置。"),
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
