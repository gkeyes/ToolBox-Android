package io.toolbox.host.background

import java.util.Locale

internal object RuntimeMediaHttpPolicy {
    private val range = Regex("^bytes=([0-9]*)-([0-9]*)$")
    private val contentRange = Regex("^bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)$")

    fun requestHeaders(method: String, headers: Map<String, String>): Map<String, String> {
        if (method !in setOf("GET", "HEAD")) throw ToolNetworkFailure("MEDIA_METHOD")
        val requested = headers.entries.filter { it.key.equals("Range", true) }
        if (requested.size > 1) throw ToolNetworkFailure("MEDIA_RANGE")
        val selected = requested.singleOrNull()?.value
        if (selected != null) {
            val parts = range.matchEntire(selected)?.groupValues ?: throw ToolNetworkFailure("MEDIA_RANGE")
            val first = parts[1].takeIf(String::isNotEmpty)?.toLongOrNull()
            val last = parts[2].takeIf(String::isNotEmpty)?.toLongOrNull()
            if ((parts[1].isNotEmpty() && first == null) || (parts[2].isNotEmpty() && last == null) ||
                (first == null && (last == null || last <= 0)) || (first != null && last != null && last < first)) {
                throw ToolNetworkFailure("MEDIA_RANGE")
            }
        }
        return buildMap {
            put("Accept", "audio/*, video/*, application/octet-stream;q=0.8")
            // Byte ranges refer to encoded representation bytes, so transparent gzip is disabled.
            put("Accept-Encoding", "identity")
            if (selected != null) put("Range", selected)
        }
    }

    fun mimeType(type: String?, kind: String?, prefix: ByteArray? = null): String {
        val mime = type?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val subtype = mime.substringAfter('/', "")
        if ((mime.startsWith("audio/") || mime.startsWith("video/")) &&
            subtype.matches(Regex("[a-z0-9!#$&^_.+-]+")) &&
            !subtype.contains("xml") && !subtype.contains("html") && !subtype.contains("mpegurl")) return mime
        if (mime !in setOf("application/octet-stream", "binary/octet-stream", "application/mp4", "application/ogg")) {
            throw ToolNetworkFailure("MEDIA_MIME")
        }
        // HEAD has no representation bytes. GET verifies a small signature before exposing binary aliases.
        if (prefix == null) return "application/octet-stream"
        fun has(offset: Int, text: String) = prefix.size >= offset + text.length &&
            text.indices.all { prefix[offset + it].toInt() and 255 == text[it].code }
        return when {
            has(4, "ftyp") -> if (kind == "audio") "audio/mp4" else "video/mp4"
            prefix.take(4).map { it.toInt() and 255 } == listOf(0x1a, 0x45, 0xdf, 0xa3) -> if (kind == "audio") "audio/webm" else "video/webm"
            has(0, "OggS") -> if (kind == "video") "video/ogg" else "audio/ogg"
            has(0, "RIFF") && has(8, "WAVE") -> "audio/wav"
            has(0, "fLaC") -> "audio/flac"
            has(0, "ID3") -> "audio/mpeg"
            prefix.size >= 2 && prefix[0].toInt() and 255 == 255 && prefix[1].toInt() and 0xe0 == 0xe0 ->
                if (prefix[1].toInt() and 0xf6 == 0xf0) "audio/aac" else "audio/mpeg"
            else -> throw ToolNetworkFailure("MEDIA_MIME")
        }
    }

    fun responseHeaders(status: Int, headers: Map<String, String>): Map<String, String> {
        val selected = headers.entries.filter { it.key.lowercase(Locale.ROOT) in setOf("content-length", "content-range", "accept-ranges") }
            .associate { it.key.lowercase(Locale.ROOT) to it.value }
        val length = selected["content-length"]?.also {
            if (it.toLongOrNull()?.let { size -> size >= 0 } != true) throw ToolNetworkFailure("MEDIA_RESPONSE")
        }?.toLong()
        if (status == 206) {
            val parts = selected["content-range"]?.let(contentRange::matchEntire)?.groupValues ?: throw ToolNetworkFailure("MEDIA_RESPONSE")
            val first = parts[1].toLongOrNull() ?: throw ToolNetworkFailure("MEDIA_RESPONSE")
            val last = parts[2].toLongOrNull() ?: throw ToolNetworkFailure("MEDIA_RESPONSE")
            val total = parts[3].takeUnless { it == "*" }?.toLongOrNull()
            if (last < first || last == Long.MAX_VALUE || (parts[3] != "*" && (total == null || total <= last)) ||
                (length != null && length != last - first + 1)) throw ToolNetworkFailure("MEDIA_RESPONSE")
        }
        if (selected["accept-ranges"]?.let { it !in setOf("bytes", "none") } == true) throw ToolNetworkFailure("MEDIA_RESPONSE")
        return selected + ("Cache-Control" to "no-store") + ("X-Content-Type-Options" to "nosniff")
    }
}
