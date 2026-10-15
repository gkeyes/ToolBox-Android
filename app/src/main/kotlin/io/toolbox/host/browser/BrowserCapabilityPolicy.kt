package io.toolbox.host.browser

import java.net.IDN
import java.util.Locale

/** One-shot identities survive queue cancellation, so late Activity results cannot target a successor. */
internal class BrowserCapabilityQueue<T> {
    data class Entry<T>(val id: Long, val page: Any, val value: T)
    private var sequence = 0L
    private val entries = mutableListOf<Entry<T>>()
    val first: Entry<T>? get() = entries.firstOrNull()
    fun add(page: Any, value: T): Entry<T> = Entry(++sequence, page, value).also(entries::add)
    fun peek(id: Long): Entry<T>? = entries.firstOrNull { it.id == id }
    fun take(id: Long): Entry<T>? {
        val index = entries.indexOfFirst { it.id == id }
        return if (index < 0) null else entries.removeAt(index)
    }
    fun cancelPage(page: Any): List<Entry<T>> = remove { it.page === page }
    fun remove(predicate: (Entry<T>) -> Boolean): List<Entry<T>> {
        val removed = entries.filter(predicate)
        entries.removeAll(removed.toSet())
        return removed
    }
}

internal object BrowserCapabilityPolicy {
    /** Same address grammar as navigation, so pages the browser opens (underscore hosts, `|^{}`) keep their capabilities. */
    fun origin(value: String): String? = runCatching {
        val parts = BrowserWebUrl.parse(value) ?: return null
        val host = if (parts.host.startsWith('[')) parts.host else IDN.toASCII(parts.host).lowercase(Locale.ROOT)
        "${parts.scheme}://$host" + (parts.port?.let { ":$it" } ?: "")
    }.getOrNull()

    fun mimeTypes(values: Array<String>): List<String> = values.flatMap { it.split(',') }
        .map { it.trim().lowercase() }
        .filter { it.matches(Regex("(?:[a-z0-9!#$&^_.+-]+|\\*)/(?:[a-z0-9!#$&^_.+-]+|\\*)")) }
        .distinct().ifEmpty { listOf("*/*") }

    /** Picker results are all-or-nothing: one item outside the content boundary rejects the whole selection. */
    fun <T> acceptedUploads(returned: List<T>, multiple: Boolean, isSafe: (T) -> Boolean): List<T>? =
        returned.distinct().takeIf { it.isNotEmpty() && it.all(isSafe) }?.let { if (multiple) it else it.take(1) }

    fun acceptsContentUri(scheme: String?, authority: String?, ownedByHost: Boolean): Boolean =
        scheme == "content" && !authority.isNullOrBlank() && '@' !in authority && !ownedByHost
}
