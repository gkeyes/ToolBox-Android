package io.toolbox.host.browser

import java.net.URI
import java.util.Locale

internal object BrowserMediaDiagnostics {
    private val mediaExtensions = Regex(
        """\.(?:m3u8|m3u|mp4|m4s|ts|webm|mpd|mov|mkv|aac|m4a)(?:$|[?#])""",
        RegexOption.IGNORE_CASE,
    )
    private val mediaKeywords = listOf(
        "dplayer", "hls", "video", "media", "player", "sourcebuffer", "mediasource",
        "codec", "m3u8", "mp4", "autoplay", "notallowed", "cors", "mixed content",
    )
    private val urlPattern = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    fun isRelevantResource(url: String, mimeType: String? = null): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        val mime = mimeType.orEmpty().lowercase(Locale.ROOT)
        return mediaExtensions.containsMatchIn(lower) ||
            mediaKeywords.any { lower.contains(it) } ||
            mime.startsWith("video/") ||
            mime.startsWith("audio/") ||
            mime.contains("mpegurl") ||
            mime.contains("dash+xml")
    }

    fun sanitizeUrl(raw: String): String = runCatching {
        val uri = URI(raw)
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            raw.take(180)
        } else {
            buildString {
                append(uri.scheme.lowercase(Locale.ROOT))
                append("://")
                append(uri.host)
                if (uri.port >= 0) append(':').append(uri.port)
                append(uri.rawPath.orEmpty().ifBlank { "/" })
            }.take(220)
        }
    }.getOrElse { raw.substringBefore('?').substringBefore('#').take(180) }

    fun networkEvent(
        label: String,
        url: String,
        detail: String = "",
        mimeType: String? = null,
    ): String? {
        if (!isRelevantResource(url, mimeType)) return null
        val suffix = detail.trim().take(160).takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
        return "$label · ${sanitizeUrl(url)}$suffix"
    }

    fun consoleEvent(
        level: String,
        message: String,
        source: String?,
        line: Int,
    ): String? {
        val normalizedLevel = level.uppercase(Locale.ROOT)
        val rawMessage = message.trim()
        if (rawMessage.isEmpty()) return null
        val relevant = normalizedLevel == "ERROR" ||
            mediaKeywords.any { rawMessage.lowercase(Locale.ROOT).contains(it) }
        if (!relevant) return null
        val sanitized = urlPattern.replace(rawMessage.take(420)) { sanitizeUrl(it.value) }
        val location = source?.takeIf { it.isNotBlank() }?.let {
            " · ${sanitizeUrl(it)}:${line.coerceAtLeast(0)}"
        }.orEmpty()
        return "Console $normalizedLevel · $sanitized$location"
    }

    fun buildReport(
        domReport: String,
        events: List<String>,
        webViewProvider: String,
        userAgentMode: String,
        mixedContentMode: Int,
    ): String = buildString {
        appendLine("ToolBox 媒体诊断")
        appendLine("UA 模式: $userAgentMode")
        appendLine("WebView: ${webViewProvider.ifBlank { "未知" }}")
        appendLine(
            "Mixed content: " + when (mixedContentMode) {
                0 -> "ALWAYS_ALLOW"
                1 -> "NEVER_ALLOW"
                2 -> "COMPATIBILITY"
                else -> mixedContentMode.toString()
            },
        )
        appendLine()
        appendLine("=== DOM / 播放能力 ===")
        appendLine(domReport.trim().ifBlank { "未返回 DOM 诊断结果" })
        appendLine()
        appendLine("=== 加载 / Console 错误 ===")
        if (events.isEmpty()) appendLine("未捕获到媒体相关错误")
        else events.forEachIndexed { index, event -> appendLine("${index + 1}. $event") }
    }.trimEnd()
}
