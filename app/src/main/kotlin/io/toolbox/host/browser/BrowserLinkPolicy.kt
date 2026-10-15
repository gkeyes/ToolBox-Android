package io.toolbox.host.browser

import java.util.Locale

internal enum class BrowserLinkKind { Web, External, Unsupported }

/** Web documents stay in WebView; app links require the user's explicit choice. */
internal object BrowserLinkPolicy {
    fun classify(url: String): BrowserLinkKind {
        if (url.any { it.isISOControl() }) return BrowserLinkKind.Unsupported
        val scheme = url.substringBefore(':', "").lowercase(Locale.ROOT)
        return when (scheme) {
            "http", "https" -> if (BrowserWebUrl.host(url) != null) {
                BrowserLinkKind.Web
            } else BrowserLinkKind.Unsupported
            "about" -> if (url == "about:blank") BrowserLinkKind.Web else BrowserLinkKind.Unsupported
            "blob", "data" -> BrowserLinkKind.Web
            "file", "content", "javascript", "" -> BrowserLinkKind.Unsupported
            "intent" -> BrowserLinkKind.External
            // App schemes follow RFC 3986 scheme syntax; the rest is the app's own business (Chromium passes
            // characters like | through unescaped, which java.net.URI would reject).
            else -> if (APP_SCHEME.matches(scheme) && url.length > scheme.length + 1) {
                BrowserLinkKind.External
            } else BrowserLinkKind.Unsupported
        }
    }

    private val APP_SCHEME = Regex("[a-z][a-z0-9+.-]*")
}
