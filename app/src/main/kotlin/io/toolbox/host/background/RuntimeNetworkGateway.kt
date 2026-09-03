package io.toolbox.host.background

import io.toolbox.tool.packagekit.InstalledManifestNetwork
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeNetworkBodyEncoding
import io.toolbox.tool.runtime.RuntimeNetworkHandler
import io.toolbox.tool.runtime.RuntimeNetworkRequest
import io.toolbox.tool.runtime.RuntimeNetworkResponse
import io.toolbox.tool.runtime.RuntimeRpcErrorCode

internal class RuntimeNetworkGateway(
    private val proxy: ToolNetworkProxy,
    private val policy: InstalledManifestNetwork?,
    private val bridgePayloadBytes: Int,
) : RuntimeNetworkHandler {
    override suspend fun request(request: RuntimeNetworkRequest): RuntimeNetworkResponse {
        val declared = policy ?: throw RuntimeHandlerException(
            RuntimeRpcErrorCode.NETWORK_BLOCKED,
            "工具未声明网络域名。",
        )
        val responseLimit = minOf(
            request.maxResponseBytes ?: 4 * 1_024 * 1_024,
            declared.maxResponseBytes,
            bridgePayloadBytes,
        )
        return when (val result = proxy.request(
            url = request.url,
            method = NetworkRequestMethod.valueOf(request.method.name),
            headers = request.headers,
            body = request.body,
            bodyIsJson = request.bodyIsJson,
            allowedHosts = declared.allowDomains,
            allowRedirects = declared.allowRedirects,
            timeoutMillis = minOf(request.timeoutMillis ?: 30_000L, declared.timeoutMs.toLong()),
            maxResponseBytes = responseLimit,
        )) {
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
                "RESULT_TOO_LARGE" -> RuntimeHandlerException(
                    RuntimeRpcErrorCode.QUOTA_EXCEEDED,
                    "网络响应超过 $responseLimit 字节上限；请减少单页数据，或提高 manifest 中的网络与消息大小上限。",
                )
                "INVALID_TIMEOUT", "INVALID_RESPONSE_LIMIT", "INVALID_URL" -> RuntimeHandlerException(
                    RuntimeRpcErrorCode.INVALID_REQUEST,
                    "网络请求参数无效（${result.errorCode}）。",
                )
                else -> RuntimeHandlerException(
                    RuntimeRpcErrorCode.NETWORK_BLOCKED,
                    when (result.errorCode) {
                        "NETWORK_HOST_NOT_ALLOWED" -> "目标域名未在工具的 network.allowDomains 中声明。"
                        "NETWORK_ADDRESS_BLOCKED" -> "目标解析到本机、私网或保留地址。"
                        "HTTPS_REQUIRED" -> "仅支持 HTTPS 网络请求。"
                        "REDIRECTS_DISABLED" -> "服务器要求重定向，但工具未允许重定向。"
                        else -> "网络地址或重定向未通过检查（${result.errorCode}）。"
                    },
                )
            }
        }
    }
}
