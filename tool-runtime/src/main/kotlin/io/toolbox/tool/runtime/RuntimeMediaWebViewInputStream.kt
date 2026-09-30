package io.toolbox.tool.runtime

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

internal data class RuntimeMediaWebViewResponse(val body: InputStream, val headers: Map<String, String>)

/**
 * Chromium seeks before appending WebResourceResponse headers, and generates
 * Content-Length from available()/range bounds. Supply a verified bootstrap
 * extent, credit the seek of an already selected 206 body, and omit the duplicate
 * native Content-Length. The extent is never a network buffer's available bytes.
 * After the first read, available() is unknown again. No media body is cached.
 * Range-ignoring 200 responses must still describe the entire representation;
 * reduced ranges and lengths that this int-sized WebView API cannot express fail.
 */
internal class RuntimeMediaWebViewInputStream private constructor(
    private val input: InputStream,
    private var offsetCredit: Long,
    private val bootstrapLength: Int,
) : InputStream() {
    private val closed = AtomicBoolean()
    private var reading = false

    override fun available(): Int = if (closed.get() || reading) 0 else bootstrapLength

    override fun read(): Int {
        requireOpen()
        reading = true
        offsetCredit = 0
        return input.read()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        requireOpen()
        if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
        if (length > 0) { reading = true; offsetCredit = 0 }
        return input.read(buffer, offset, length)
    }

    override fun skip(count: Long): Long {
        requireOpen()
        if (count <= 0) return 0
        val credited = minOf(count, offsetCredit)
        offsetCredit -= credited
        return credited + if (count > credited) input.skip(count - credited) else 0
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            offsetCredit = 0
            input.close()
        }
    }

    private fun requireOpen() {
        if (closed.get()) throw IOException("Media body closed")
    }

    companion object {
        private val requestedRange = Regex("bytes=([0-9]*)-([0-9]*)")
        private val partialRange = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)")

        fun prepare(method: String, requestHeaders: Map<String, String>, status: Int,
            responseHeaders: Map<String, String>, body: InputStream): RuntimeMediaWebViewResponse {
            if (method !in setOf("GET", "HEAD") || status !in setOf(200, 206, 416)) return RuntimeMediaWebViewResponse(body, responseHeaders)
            val range = header(requestHeaders, "range")?.let {
                val parts = requestedRange.matchEntire(it)?.groupValues ?: invalidRange()
                val first = number(parts[1])
                val last = number(parts[2])
                if ((first == null && (last == null || last <= 0)) || (first != null && last != null && last < first)) invalidRange()
                first to last
            }
            val length = header(responseHeaders, "content-length")?.let { number(it) ?: invalidRange() }
            val extent: Long
            val selected: Pair<Long, Long>
            if (status == 416) {
                if (length != null && length != 0L) invalidRange()
                extent = 0
                selected = (range?.first ?: 0L) to 0L
            } else if (status == 206) {
                val parts = header(responseHeaders, "content-range")?.let(partialRange::matchEntire)?.groupValues ?: invalidRange()
                val start = number(parts[1]) ?: invalidRange()
                val end = number(parts[2]) ?: invalidRange()
                val total = if (parts[3] == "*") null else number(parts[3]) ?: invalidRange()
                if (end < start || end == Long.MAX_VALUE || (total != null && total <= end)) invalidRange()
                val span = end - start + 1
                if (length != null && length != span) invalidRange()
                val requestedFirst = range?.first
                val requestedLast = range?.second
                if (range != null) {
                    if (requestedFirst != null) {
                        if (start != requestedFirst || (requestedLast != null && end > requestedLast)) invalidRange()
                    } else {
                        val suffix = requestedLast ?: invalidRange()
                        if (span > suffix || (total != null && (start != maxOf(0, total - suffix) || end != total - 1))) invalidRange()
                    }
                }
                extent = when {
                    range == null -> span
                    requestedFirst != null -> end + 1
                    total != null && total <= Int.MAX_VALUE -> total
                    else -> span // A selected suffix also has a compact virtual view.
                }
                requireExtent(extent)
                selected = bounds(extent, range)
                if (selected.second != span) invalidRange()
            } else {
                extent = length ?: unsupportedLength()
                requireExtent(extent)
                selected = bounds(extent, range)
                if (selected.first != 0L || selected.second != extent) invalidRange()
            }
            val credit = if (status != 200 || method == "HEAD") selected.first else 0
            val input = RuntimeMediaWebViewInputStream(body, credit, extent.toInt())
            return RuntimeMediaWebViewResponse(input, responseHeaders.filterKeys { !it.equals("content-length", true) })
        }

        private fun bounds(extent: Long, range: Pair<Long?, Long?>?): Pair<Long, Long> {
            if (extent == 0L) {
                if (range?.first?.let { it > 0 } == true) invalidRange()
                return 0L to 0L
            }
            val first = range?.first ?: if (range == null) 0 else maxOf(0, extent - (range.second ?: invalidRange()))
            if (first >= extent) invalidRange()
            val last = if (range?.first == null) extent - 1 else minOf(range.second ?: (extent - 1), extent - 1)
            return first to (last - first + 1)
        }

        private fun requireExtent(value: Long) {
            if (value !in 0L..Int.MAX_VALUE.toLong()) unsupportedLength()
        }

        private fun header(headers: Map<String, String>, name: String): String? {
            val matching = headers.entries.filter { it.key.equals(name, true) }
            if (matching.size > 1) invalidRange()
            return matching.singleOrNull()?.value
        }

        private fun number(value: String): Long? = if (value.isEmpty()) null else value.toLongOrNull() ?: invalidRange()
        private fun invalidRange(): Nothing = throw IOException("Invalid media range metadata")
        private fun unsupportedLength(): Nothing = throw IOException("Media response length cannot be represented by WebView")
    }
}
