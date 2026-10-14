package io.toolbox.host.browser

/**
 * The host as the address capsule shows it: the registrable domain, like Safari. "www." and
 * leading subdomains go first, so "m.news.example.com" reads "example.com" in a narrow capsule.
 * Two-letter country domains that register under a common second level keep three labels
 * ("example.co.uk", "example.com.cn"). IP addresses and single-label hosts are shown as they are.
 * Tapping the capsule still shows the full address.
 */
internal fun browserDisplayHost(host: String): String {
    if (host.isEmpty() || ':' in host || host.all { it.isDigit() || it == '.' }) return host
    val labels = host.trimEnd('.').split('.')
    if (labels.size <= 2) return labels.joinToString(".")
    val keep = if (labels.last().length == 2 && labels[labels.size - 2] in CountrySecondLevelLabels) 3 else 2
    return labels.takeLast(keep).joinToString(".")
}

private val CountrySecondLevelLabels = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go")
