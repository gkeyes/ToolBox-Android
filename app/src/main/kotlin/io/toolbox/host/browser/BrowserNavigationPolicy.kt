package io.toolbox.host.browser

import java.net.URI
import java.util.Locale

/**
 * Small navigation normalizations for legacy endpoints whose HTTP entry is known to be obsolete.
 * Do not upgrade arbitrary HTTP sites: many local/legacy services intentionally remain HTTP-only.
 */
internal object BrowserNavigationPolicy {
    fun normalize(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return url
        val host = uri.host?.lowercase(Locale.ROOT) ?: return url
        if (scheme != "http" || !isNewSmthHost(host)) return url

        return url.replaceRange(0, uri.scheme.length, "https")
    }

    private fun isNewSmthHost(host: String): Boolean =
        host == "newsmth.net" || host.endsWith(".newsmth.net")
}
