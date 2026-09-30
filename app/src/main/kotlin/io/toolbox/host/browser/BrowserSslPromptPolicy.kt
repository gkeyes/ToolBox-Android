package io.toolbox.host.browser

import java.net.URI
import java.util.Locale

internal object BrowserSslPromptPolicy {
    fun shouldOfferPrompt(pageUrl: String, failureUrl: String): Boolean {
        val page = httpsHost(pageUrl) ?: return false
        val failed = httpsHost(failureUrl) ?: return false
        return page == failed
    }

    fun describe(
        hasUntrusted: Boolean,
        hasIdMismatch: Boolean,
        hasExpired: Boolean,
        hasNotYetValid: Boolean,
        hasDateInvalid: Boolean,
        hasInvalid: Boolean,
    ): String {
        val reasons = buildList {
            if (hasIdMismatch) add("证书与当前网站域名不匹配")
            if (hasExpired) add("证书已经过期")
            if (hasNotYetValid) add("证书尚未生效")
            if (hasDateInvalid) add("证书日期无效")
            if (hasUntrusted) add("证书链无法被系统完整信任")
            if (hasInvalid) add("证书无效")
        }
        return if (reasons.isEmpty()) "系统无法验证此网站的安全证书。"
        else reasons.joinToString("；") + "。"
    }

    private fun httpsHost(url: String): String? = runCatching {
        val uri = URI(url)
        if (!uri.scheme.equals("https", ignoreCase = true)) return@runCatching null
        uri.host?.lowercase(Locale.ROOT)
    }.getOrNull()
}
