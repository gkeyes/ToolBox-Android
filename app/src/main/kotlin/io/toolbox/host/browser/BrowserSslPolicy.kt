package io.toolbox.host.browser

import java.net.URI
import java.util.Locale

/**
 * Narrow compatibility bridge for legacy HTTPS servers that present an incomplete/untrusted
 * certificate chain. This deliberately does not bypass hostname or certificate date failures.
 */
internal object BrowserSslPolicy {
    fun allowLegacyUntrustedChain(
        pageUrl: String,
        failureUrl: String,
        hasUntrusted: Boolean,
        hasIdMismatch: Boolean,
        hasExpired: Boolean,
        hasNotYetValid: Boolean,
        hasDateInvalid: Boolean,
        hasInvalid: Boolean,
    ): Boolean {
        if (!hasUntrusted) return false
        if (hasIdMismatch || hasExpired || hasNotYetValid || hasDateInvalid || hasInvalid) return false

        val page = httpsHost(pageUrl) ?: return false
        val failed = httpsHost(failureUrl) ?: return false
        return page == failed
    }

    private fun httpsHost(url: String): String? = runCatching {
        val uri = URI(url)
        if (!uri.scheme.equals("https", ignoreCase = true)) return@runCatching null
        uri.host?.lowercase(Locale.ROOT)
    }.getOrNull()
}
