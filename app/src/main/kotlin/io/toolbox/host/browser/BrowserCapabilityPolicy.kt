package io.toolbox.host.browser

import java.net.URI

/** One-shot identities survive queue cancellation, so late Activity results cannot target a successor. */
internal class BrowserCapabilityQueue<T> {
    data class Entry<T>(val id: Long, val page: Any, val value: T)
    private var sequence = 0L
    private val entries = mutableListOf<Entry<T>>()
    val first: Entry<T>? get() = entries.firstOrNull()
    fun add(page: Any, value: T): Entry<T> = Entry(++sequence, page, value).also(entries::add)
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
    fun origin(value: String): String? = runCatching {
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("https", "http") || uri.userInfo != null || uri.host.isNullOrBlank()) return null
        URI(scheme, null, uri.host.lowercase(), uri.port, null, null, null).toASCIIString()
    }.getOrNull()

    fun mimeTypes(values: Array<String>): List<String> = values.flatMap { it.split(',') }
        .map { it.trim().lowercase() }
        .filter { it.matches(Regex("(?:[a-z0-9!#$&^_.+-]+|\\*)/(?:[a-z0-9!#$&^_.+-]+|\\*)")) }
        .distinct().ifEmpty { listOf("*/*") }

    fun acceptsContentUri(scheme: String?, authority: String?, ownedByHost: Boolean): Boolean =
        scheme == "content" && !authority.isNullOrBlank() && '@' !in authority && !ownedByHost
}
