package io.toolbox.host.background

import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Proxy failure codes outside the NETWORK_* and HTTP_* families that a background httpGet can store. */
private val PROXY_FAILURE_CODES = setOf(
    "HTTPS_REQUIRED", "URL_CREDENTIALS_FORBIDDEN", "INVALID_URL", "INVALID_REDIRECT", "REDIRECT_LOOP",
    "REDIRECTS_DISABLED", "PROXY_AUTHENTICATION_REQUIRED", "RESULT_TOO_LARGE", "INSUFFICIENT_MEMORY",
    "RESOURCE_UNAVAILABLE", "INVALID_TIMEOUT", "INVALID_RESPONSE_LIMIT",
)

// NETWORK_NOT_ALLOWED is the enqueue-time grant check, not a network outcome.
private fun String.isNetworkFailureCode(): Boolean = this != "NETWORK_NOT_ALLOWED" &&
    (startsWith("NETWORK_") || startsWith("HTTP_") || this in PROXY_FAILURE_CODES)

/**
 * Maps a stored background error or enqueue rejection to the runtime error a tool receives. Network codes reuse
 * the foreground network.request mapping, and the raw host code stays in the message.
 */
internal fun backgroundTaskFailure(errorCode: String, fallbackMessage: String): RuntimeHandlerException {
    if (errorCode.isNetworkFailureCode()) {
        val mapped = networkFailureException(errorCode, retryable = false)
        val message = if (errorCode in mapped.message) mapped.message else "${mapped.message}（$errorCode）"
        return RuntimeHandlerException(mapped.errorCode, message)
    }
    val code = when (errorCode) {
        "CANCELLED" -> RuntimeRpcErrorCode.CANCELLED
        "NOT_FOUND" -> RuntimeRpcErrorCode.NOT_FOUND
        "SYSTEM_PERMISSION_DENIED" -> RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED
        "INVALID_INPUT", "INVALID_NOTIFICATION", "INTERVAL_TOO_SHORT", "INVALID_TASK_SPEC" ->
            RuntimeRpcErrorCode.INVALID_REQUEST
        "TASK_QUOTA_EXCEEDED", "PERIODIC_TASK_QUOTA_EXCEEDED" -> RuntimeRpcErrorCode.QUOTA_EXCEEDED
        "BACKGROUND_NOT_ALLOWED", "NOTIFICATIONS_NOT_ALLOWED", "NETWORK_NOT_ALLOWED" -> RuntimeRpcErrorCode.PERMISSION_DENIED
        else -> if (errorCode.startsWith("DUPLICATE")) RuntimeRpcErrorCode.DUPLICATE_TASK else RuntimeRpcErrorCode.INTERNAL_ERROR
    }
    return RuntimeHandlerException(code, "${fallbackMessage.removeSuffix("。")}（$errorCode）。")
}

/** A background httpGet that failed on a non-2xx response keeps that status for the tool (only codes the RPC can carry). */
internal fun backgroundHttpStatus(errorCode: String?): Int? {
    if (errorCode == null || !errorCode.startsWith("HTTP_")) return null
    return errorCode.removePrefix("HTTP_").toIntOrNull()?.takeIf { it in 100..599 }
}

/** The same endpoint check the proxy applies before every request, so an unusable URL fails at enqueue. */
internal fun backgroundHttpEndpointError(url: String): String? {
    val endpoint = url.toHttpUrlOrNull() ?: return "INVALID_URL"
    return NetworkPolicy.validateEndpoint(endpoint)
}
