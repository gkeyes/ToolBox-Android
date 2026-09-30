package io.toolbox.host.browser

import java.net.IDN
import java.net.URI
import java.util.Locale
import java.util.UUID

enum class BrowserFilterKind { Cosmetic, NetworkHost }
enum class BrowserFilterSource { Picker, Manual }

data class BrowserFilterRule(
    val id: String = UUID.randomUUID().toString(),
    val site: String,
    val value: String,
    val kind: BrowserFilterKind = BrowserFilterKind.Cosmetic,
    val source: BrowserFilterSource = BrowserFilterSource.Manual,
    val enabled: Boolean = true,
)

/** Immutable snapshots are read on WebView's request threads without disk I/O. */
data class BrowserFilterSnapshot(
    val enabled: Boolean = true,
    val builtIn: Boolean = true,
    val exceptions: Set<String> = emptySet(),
    val rules: List<BrowserFilterRule> = emptyList(),
) {
    private val networkHostsBySite: Map<String, Set<String>> = rules.asSequence()
        .filter { it.enabled && it.kind == BrowserFilterKind.NetworkHost }
        .groupBy(BrowserFilterRule::site, BrowserFilterRule::value)
        .mapValues { (_, hosts) -> hosts.toSet() }

    fun active(site: String) = site.isNotEmpty() && enabled && site !in exceptions
    fun selectors(site: String): List<String> = if (!active(site)) emptyList() else
        rules.filter { it.enabled && it.site == site && it.kind == BrowserFilterKind.Cosmetic }.map { it.value }

    fun blocks(site: String, requestUrl: String, mainFrame: Boolean): Boolean {
        if (mainFrame || !active(site)) return false
        val host = BrowserFilterValidation.urlHost(requestUrl) ?: return false
        return (builtIn && containsHostOrParent(BUILT_IN_HOSTS, host)) ||
            containsHostOrParent(networkHostsBySite[site].orEmpty(), host)
    }

    companion object {
        // Deliberately small first-party-maintained baseline, not an EasyList compatibility claim.
        private val BUILT_IN_HOSTS = setOf("doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com")

        private fun containsHostOrParent(blocked: Set<String>, host: String): Boolean {
            if (host in blocked) return true
            var dot = host.indexOf('.')
            while (dot >= 0) {
                if (host.substring(dot + 1) in blocked) return true
                dot = host.indexOf('.', dot + 1)
            }
            return false
        }
    }
}

object BrowserFilterValidation {
    const val MAX_RULES = 500
    const val MAX_SELECTOR_LENGTH = 1024

    fun host(value: String): String? = runCatching {
        val raw = value.trim().trimEnd('.')
        if (raw.isEmpty() || raw.any { it in "/:?#@*\\" || it.isWhitespace() }) return null
        val normalized = IDN.toASCII(raw, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        if (normalized.length > 253 || normalized.split('.').any { it.isEmpty() || it.length > 63 }) null else normalized
    }.getOrNull()

    fun urlHost(value: String): String? = runCatching {
        val uri = URI(value)
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) null else uri.host?.let(::host)
    }.getOrNull()

    /** CSS is emitted into a style sheet, so reject declaration/rule injection before DOM validation. */
    fun selectorAllowed(value: String): Boolean = value.isNotBlank() && value.length <= MAX_SELECTOR_LENGTH &&
        value.none { it in "{};@\u0000\r\n" } &&
        !value.contains("/*") && !value.contains("*/") &&
        value.split(',').none { it.trim().lowercase(Locale.ROOT) in setOf("*", "html", "body", ":root") }

    fun normalize(rule: BrowserFilterRule): BrowserFilterRule? {
        val site = host(rule.site) ?: return null
        val value = when (rule.kind) {
            BrowserFilterKind.Cosmetic -> rule.value.trim().takeIf(::selectorAllowed)
            BrowserFilterKind.NetworkHost -> host(rule.value)
        } ?: return null
        return rule.copy(site = site, value = value)
    }
}
