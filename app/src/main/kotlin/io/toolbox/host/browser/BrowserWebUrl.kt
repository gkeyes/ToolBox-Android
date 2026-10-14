package io.toolbox.host.browser

import java.util.Locale

/**
 * Validates addresses the WebView itself produced (navigations, downloads, history, fallbacks).
 *
 * Tool-supplied `browser.open` URLs keep the strict `validateRuntimeBrowserUrl` check. Chromium leaves `| ^ { }` and
 * backticks unescaped and allows underscore hosts, which `java.net.URI` rejects, so pages here are parsed by hand:
 * http/https only, a host, no user info, a valid port and no raw or percent-escaped control characters.
 */
internal object BrowserWebUrl {
    /** Returns the lowercase host when [url] is a navigable web address, otherwise null. */
    fun host(url: String): String? {
        if (url.isEmpty() || url.any { it.isISOControl() || it.isWhitespace() || it == '\\' }) return null
        val separator = url.indexOf("://")
        if (separator <= 0) return null
        val scheme = url.substring(0, separator).lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        val rest = url.substring(separator + 3)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        if (authority.isEmpty() || '@' in authority || '%' in authority) return null
        if (ESCAPED_CONTROL.containsMatchIn(rest.substring(authorityEnd))) return null
        val (host, port) = if (authority.startsWith('[')) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            authority.substring(0, close + 1) to authority.substring(close + 1).removePrefix(":").takeIf { authority.length > close + 1 }
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon < 0) authority to null else authority.substring(0, colon) to authority.substring(colon + 1)
        }
        if (port != null && (port.isEmpty() || port.length > 5 || port.any { it !in '0'..'9' } || port.toInt() !in 1..65_535)) return null
        val bare = host.trimEnd('.')
        if (bare.isEmpty()) return null
        if (!host.startsWith('[') && host.any { !(it.isLetterOrDigit() || it == '-' || it == '.' || it == '_') }) return null
        return bare.lowercase(Locale.ROOT)
    }

    fun valid(url: String): String? = url.takeIf { host(it) != null }

    // %00-%1F, %7F and UTF-8 C1 controls (%C2%80-%C2%9F) decode to control characters.
    private val ESCAPED_CONTROL = Regex("%(?:[01][0-9a-fA-F]|7[fF])|%[cC]2%[89][0-9a-fA-F]")
}
