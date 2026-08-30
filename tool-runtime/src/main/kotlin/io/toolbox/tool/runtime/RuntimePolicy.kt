package io.toolbox.tool.runtime

import android.webkit.WebResourceResponse
import io.toolbox.core.data.SecurityProfile
import java.io.ByteArrayInputStream

object RuntimePolicy {
    fun contentSecurityPolicy(profile: SecurityProfile): String {
        val script = when (profile) {
            SecurityProfile.STRICT -> "script-src 'self'"
            SecurityProfile.COMPAT -> "script-src 'self' 'unsafe-inline'"
        }
        return listOf(
            "default-src 'self'",
            script,
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: blob:",
            "font-src 'self' data:",
            "connect-src 'none'",
            "media-src 'self' blob:",
            "frame-src 'none'",
            "object-src 'none'",
            "base-uri 'none'",
            "form-action 'none'",
            "worker-src 'self'",
            "upgrade-insecure-requests",
        ).joinToString("; ", postfix = ";")
    }

    fun responseHeaders(profile: SecurityProfile): Map<String, String> = mapOf(
        "Content-Security-Policy" to contentSecurityPolicy(profile),
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
