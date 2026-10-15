package io.toolbox.host.browser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The host as the address capsule shows it, shortened from the left like Safari: "www." and "m."
 * go first, then everything before the label in front of the registrable domain is replaced by
 * "…". One label beyond the registrable domain always stays, so a shared host such as
 * "evil.github.io" never reads as plain "github.io". The registrable domain comes from the public
 * suffix list, so multi-label suffixes ("s3.amazonaws.com") keep their registrant; when the list
 * gives no answer the host is not shortened at all. IP addresses and single-label hosts are shown
 * as they are. Tapping the capsule shows the full address.
 */
internal fun browserDisplayHost(host: String, registrableDomain: (String) -> String? = ::publicSuffixRegistrable): String {
    if (host.isEmpty() || ':' in host || host.all { it.isDigit() || it == '.' }) return host
    var labels = host.trimEnd('.').split('.')
    val registrable = runCatching { registrableDomain(labels.joinToString(".")) }.getOrNull()
        ?.split('.')?.size?.takeIf { it in 2..labels.size }
        ?: return labels.joinToString(".")
    while (labels.size > registrable && labels.first() in GenericPrefixes) labels = labels.drop(1)
    if (labels.size <= registrable + 1) return labels.joinToString(".")
    return "…" + labels.takeLast(registrable + 1).joinToString(".")
}

private fun publicSuffixRegistrable(host: String): String? = "https://$host/".toHttpUrlOrNull()?.topPrivateDomain()

private val GenericPrefixes = setOf("www", "m")
