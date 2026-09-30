package io.toolbox.tool.runtime

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Chromium's Android stream loader seeks using the original request before it
 * consumes WebResourceResponse headers. A native 206 body already starts at its
 * selected offset; only that bootstrap seek is credited, without discarding it.
 * available() is an unknown length: a network buffer is not the resource size.
 * GET 200 keeps real skip behavior and does not provide HTTP seek compatibility
 * for servers that ignore Range. HEAD and 416 must still deliver their headers.
 */
internal class RuntimeMediaWebViewInputStream private constructor(
    private val input: InputStream,
    private var offsetCredit: Long,
) : InputStream() {
    private val closed = AtomicBoolean()

    override fun available(): Int = 0

    override fun read(): Int {
        requireOpen()
        offsetCredit = 0
        return input.read()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        requireOpen()
        if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
        if (length > 0) offsetCredit = 0
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

        fun wrap(method: String, requestHeaders: Map<String, String>, status: Int,
            responseHeaders: Map<String, String>, body: InputStream): InputStream {
            if (method !in setOf("GET", "HEAD") || status !in setOf(200, 206, 416)) return body
            val range = header(requestHeaders, "range")?.let {
                val parts = requestedRange.matchEntire(it)?.groupValues ?: invalidRange()
                val first = number(parts[1])
                val last = number(parts[2])
                if ((first == null && (last == null || last <= 0)) || (first != null && last != null && last < first)) invalidRange()
                first to last
            }
            val first = if (status == 206) {
                val parts = header(responseHeaders, "content-range")?.let(partialRange::matchEntire)?.groupValues ?: invalidRange()
                val start = number(parts[1]) ?: invalidRange()
                val end = number(parts[2]) ?: invalidRange()
                val total = if (parts[3] == "*") null else number(parts[3]) ?: invalidRange()
                if (end < start || end == Long.MAX_VALUE || (total != null && total <= end)) invalidRange()
                if (range != null) {
                    if (range.first != null) {
                        if (start != range.first || (range.second != null && end > range.second!!)) invalidRange()
                    } else if (total != null && (start != maxOf(0, total - range.second!!) || end != total - 1)) invalidRange()
                }
                start
            } else if (method == "HEAD" || status == 416) range?.first ?: 0 else 0
            return RuntimeMediaWebViewInputStream(body, first)
        }

        private fun header(headers: Map<String, String>, name: String): String? {
            val matching = headers.entries.filter { it.key.equals(name, true) }
            if (matching.size > 1) invalidRange()
            return matching.singleOrNull()?.value
        }

        private fun number(value: String): Long? = if (value.isEmpty()) null else value.toLongOrNull() ?: invalidRange()
        private fun invalidRange(): Nothing = throw IOException("Invalid media range metadata")
    }
}
