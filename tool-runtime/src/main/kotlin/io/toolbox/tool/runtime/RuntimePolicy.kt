package io.toolbox.tool.runtime

import android.webkit.WebResourceResponse
import io.toolbox.core.data.SecurityProfile
import java.io.ByteArrayInputStream

object RuntimePolicy {
    /**
     * [allowWorkers] is false in ORIGIN_ONLY_STATELESS mode: the document-start hardening cannot reach
     * worker globals, and a worker would otherwise get native IndexedDB and Cache Storage.
     */
    fun contentSecurityPolicy(profile: SecurityProfile, allowWorkers: Boolean = true): String {
        val script = when (profile) {
            SecurityProfile.STRICT -> "script-src 'self' 'wasm-unsafe-eval'"
            SecurityProfile.COMPAT -> "script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'"
        }
        return listOf(
            "default-src 'self'",
            script,
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: blob:",
            "font-src 'self' data:",
            "connect-src 'self'",
            "media-src 'self' blob:",
            "frame-src 'none'",
            "object-src 'none'",
            "base-uri 'none'",
            "form-action 'none'",
            if (allowWorkers) "worker-src 'self'" else "worker-src 'none'",
            "upgrade-insecure-requests",
        ).joinToString("; ", postfix = ";")
    }

    fun responseHeaders(profile: SecurityProfile, allowWorkers: Boolean = true): Map<String, String> = mapOf(
        "Content-Security-Policy" to contentSecurityPolicy(profile, allowWorkers),
        "X-Content-Type-Options" to "nosniff",
        "Referrer-Policy" to "no-referrer",
        "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
        "Cache-Control" to "no-store",
    )

    fun blockedResponse(statusCode: Int = 403, reason: String = "Blocked"): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            statusCode,
            reason,
            mapOf(
                "Cache-Control" to "no-store",
                "X-Content-Type-Options" to "nosniff",
            ),
            ByteArrayInputStream(ByteArray(0)),
        )
}
