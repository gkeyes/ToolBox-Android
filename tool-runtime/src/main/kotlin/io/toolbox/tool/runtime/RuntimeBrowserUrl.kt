package io.toolbox.tool.runtime

import java.net.URI

/** Validates a URL only. Never parses Intent URI syntax, extras, headers or a caller-selected target. */
fun validateRuntimeBrowserUrl(url: String): String {
    require(url.length in 1..2_048 && url.none { it.isISOControl() || it.isWhitespace() || it == '\\' })
    val uri = try { URI(url) } catch (_: Exception) { throw IllegalArgumentException("Invalid browser URL") }
    require(uri.isAbsolute && !uri.isOpaque && uri.scheme.lowercase() in setOf("http", "https"))
    require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && '@' !in uri.rawAuthority)
    require(uri.port == -1 || uri.port in 1..65_535)
    // Reject empty/non-numeric ports and percent-escaped authority ambiguities.
    val authority = uri.rawAuthority
    require('%' !in authority && !authority.endsWith(':'))
    // Escaped control characters must not become a different address when handed to a browser.
    require(listOfNotNull(uri.path, uri.query, uri.fragment).none { part -> part.any(Char::isISOControl) })
    return url
}
