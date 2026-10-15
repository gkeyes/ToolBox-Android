package io.toolbox.host.browser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The host as the address capsule shows it, shortened from the left like Safari: "www." and "m."
 * go first, then everything before the registrable domain is replaced by "…", so a 320dp capsule
 * still shows who owns the page ("news.example.com" reads "…example.com"). The registrable domain
 * comes from the public suffix list, which already treats shared hosts as their own registrants
 * ("evil.github.io" stays whole, never "github.io") and keeps multi-label suffixes
 * ("s3.amazonaws.com") with their registrant; when the list gives no answer the host is not
 * shortened at all. IP addresses and single-label hosts are shown as they are. Tapping the capsule
 * shows the full address.
 */
internal fun browserDisplayHost(host: String, registrableDomain: (String) -> String? = ::publicSuffixRegistrable): String {
    if (host.isEmpty() || ':' in host || host.all { it.isDigit() || it == '.' }) return host
    var labels = host.trimEnd('.').split('.')
    val registrable = runCatching { registrableDomain(labels.joinToString(".")) }.getOrNull()
        ?.split('.')?.size?.takeIf { it in 2..labels.size }
        ?: return labels.joinToString(".")
    while (labels.size > registrable && labels.first() in GenericPrefixes) labels = labels.drop(1)
    if (labels.size <= registrable) return labels.joinToString(".")
    return "…" + labels.takeLast(registrable).joinToString(".")
}

private fun publicSuffixRegistrable(host: String): String? = "https://$host/".toHttpUrlOrNull()?.topPrivateDomain()

private val GenericPrefixes = setOf("www", "m")
