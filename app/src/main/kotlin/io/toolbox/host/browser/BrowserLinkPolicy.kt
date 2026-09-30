package io.toolbox.host.browser

import io.toolbox.tool.runtime.validateRuntimeBrowserUrl
import java.net.URI
import java.util.Locale

internal enum class BrowserLinkKind { Web, External, Unsupported }

/** Web documents stay in WebView; app links require the user's explicit choice. */
internal object BrowserLinkPolicy {
    fun classify(url: String): BrowserLinkKind {
        if (url.any { it.isISOControl() }) return BrowserLinkKind.Unsupported
        val scheme = url.substringBefore(':', "").lowercase(Locale.ROOT)
        return when (scheme) {
            "http", "https" -> if (runCatching { validateRuntimeBrowserUrl(url) }.isSuccess) {
                BrowserLinkKind.Web
            } else BrowserLinkKind.Unsupported
            "about" -> if (url == "about:blank") BrowserLinkKind.Web else BrowserLinkKind.Unsupported
            "blob", "data" -> BrowserLinkKind.Web
            "file", "content", "javascript", "" -> BrowserLinkKind.Unsupported
            "intent" -> BrowserLinkKind.External
            else -> if (runCatching { URI(url).isAbsolute }.getOrDefault(false)) {
                BrowserLinkKind.External
            } else BrowserLinkKind.Unsupported
        }
    }
}
