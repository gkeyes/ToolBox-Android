package io.toolbox.tool.runtime

/** The public SDK transfers a blob over several bounded calls within one foreground session. */
interface RuntimeImageShareHandler : RuntimeSessionCleanupHandler {
    suspend fun begin(id: String, format: RuntimeImageShareFormat, length: Int)
    suspend fun write(id: String, offset: Int, bytes: ByteArray)
    suspend fun finish(id: String, beforeLaunch: suspend () -> Unit)
    suspend fun cancel(id: String)
    fun resetForNavigation()
}

const val RUNTIME_IMAGE_SHARE_MAX_BYTES = 32 * 1024 * 1024
internal const val IMAGE_SHARE_CHUNK_BYTES = 256 * 1024

enum class RuntimeImageShareFormat(val mimeType: String, val extension: String) {
    JPEG("image/jpeg", ".jpg"),
    PNG("image/png", ".png"),
    WEBP("image/webp", ".webp"),
    GIF("image/gif", ".gif");

    /** The original image is transferred byte for byte. Only recognized raster signatures are shared. */
    fun matchesHeader(header: ByteArray, length: Long): Boolean = when (this) {
        JPEG -> length >= 4 && header.prefix(0, 0xff, 0xd8, 0xff)
        PNG -> length >= 33 && header.prefix(0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) &&
            header.prefix(8, 0, 0, 0, 13) && header.asciiAt(12, "IHDR")
        WEBP -> length >= 20 && header.asciiAt(0, "RIFF") && header.asciiAt(8, "WEBP") &&
            listOf("VP8 ", "VP8L", "VP8X").any { header.asciiAt(12, it) } &&
            header.size >= 8 && (4..7).fold(0L) { size, index ->
                size or ((header[index].toLong() and 0xffL) shl ((index - 4) * 8))
            } + 8 == length
        GIF -> length >= 14 && (header.asciiAt(0, "GIF87a") || header.asciiAt(0, "GIF89a"))
    }

    private fun ByteArray.prefix(offset: Int, vararg values: Int): Boolean =
        size >= offset + values.size && values.indices.all { index -> (this[offset + index].toInt() and 0xff) == values[index] }

    private fun ByteArray.asciiAt(offset: Int, signature: String): Boolean =
        size >= offset + signature.length && signature.indices.all { index -> this[offset + index].toInt() == signature[index].code }
}
