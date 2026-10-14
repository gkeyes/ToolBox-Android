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
    data class Parts(val scheme: String, val host: String, val port: Int?)

    fun parse(url: String): Parts? {
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
        val host: String
        val portText: String?
        if (authority.startsWith('[')) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            val literal = authority.substring(1, close)
            // A bracketed host must be an IPv6 literal; anything after ']' must be a ":port".
            if (':' !in literal || literal.any { !(it.isHexDigitAscii() || it == ':' || it == '.') }) return null
            val suffix = authority.substring(close + 1)
            if (suffix.isNotEmpty() && !suffix.startsWith(':')) return null
            host = authority.substring(0, close + 1).lowercase(Locale.ROOT)
            portText = suffix.takeIf { it.isNotEmpty() }?.substring(1)
        } else {
            val colon = authority.lastIndexOf(':')
            val name = if (colon < 0) authority else authority.substring(0, colon)
            if (name.any { !(it.isLetterOrDigit() || it == '-' || it == '.' || it == '_') }) return null
            host = name.trimEnd('.').lowercase(Locale.ROOT)
            portText = if (colon < 0) null else authority.substring(colon + 1)
        }
        if (host.isEmpty()) return null
        val port = portText?.let { text ->
            if (text.isEmpty() || text.length > 5 || text.any { it !in '0'..'9' }) return null
            text.toInt().takeIf { it in 1..65_535 } ?: return null
        }
        return Parts(scheme, host, port)
    }

    /** Returns the lowercase host when [url] is a navigable web address, otherwise null. */
    fun host(url: String): String? = parse(url)?.host

    fun valid(url: String): String? = url.takeIf { parse(it) != null }

    private fun Char.isHexDigitAscii() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    // %00-%1F, %7F and UTF-8 C1 controls (%C2%80-%C2%9F) decode to control characters.
    private val ESCAPED_CONTROL = Regex("%(?:[01][0-9a-fA-F]|7[fF])|%[cC]2%[89][0-9a-fA-F]")
}
