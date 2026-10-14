package io.toolbox.host.browser

/**
 * The host as the address capsule shows it, shortened from the left like Safari: "www." and "m."
 * go first, then everything before the label in front of the registrable domain is replaced by
 * "…". One label beyond the registrable domain always stays, so a shared host such as
 * "evil.github.io" never reads as plain "github.io". Two-letter country domains that register
 * under a common second level count three labels ("example.co.uk", "example.com.cn"). IP
 * addresses and single-label hosts are shown as they are. Tapping the capsule shows the full address.
 */
internal fun browserDisplayHost(host: String): String {
    if (host.isEmpty() || ':' in host || host.all { it.isDigit() || it == '.' }) return host
    var labels = host.trimEnd('.').split('.')
    val registrable = if (labels.size >= 3 && labels.last().length == 2 && labels[labels.size - 2] in CountrySecondLevelLabels) 3 else 2
    while (labels.size > registrable && labels.first() in GenericPrefixes) labels = labels.drop(1)
    if (labels.size <= registrable + 1) return labels.joinToString(".")
    return "…" + labels.takeLast(registrable + 1).joinToString(".")
}

private val GenericPrefixes = setOf("www", "m")
private val CountrySecondLevelLabels = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go")
