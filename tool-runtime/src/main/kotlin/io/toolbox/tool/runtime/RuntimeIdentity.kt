package io.toolbox.tool.runtime

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object RuntimeIdentity {
    private const val BASE32_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    fun origin(toolId: String): String = "https://${originHost(toolId)}/"

    fun originHost(toolId: String): String = base32(digest(toolId)).take(26) + ".toolbox.invalid"

    fun profileName(toolId: String): String = "tbx_${digest(toolId).toHex().take(24)}"

    fun expectedBundleLocator(toolId: String, versionCode: Int): String =
        "miniapps/$toolId/versions/$versionCode/bundle"

    fun isExactLocalUrl(url: String, expectedOrigin: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val origin = runCatching { URI(expectedOrigin) }.getOrNull() ?: return false
        return uri.scheme == "https" &&
            uri.rawUserInfo == null &&
            uri.host == origin.host &&
            uri.port == -1 &&
            uri.rawFragment == null
    }

    private fun digest(toolId: String): ByteArray {
        require(toolId.isNotBlank()) { "Tool ID must not be blank" }
        return MessageDigest.getInstance("SHA-256").digest(toolId.toByteArray(StandardCharsets.UTF_8))
    }

    private fun base32(bytes: ByteArray): String = buildString((bytes.size * 8 + 4) / 5) {
        var buffer = 0
        var bits = 0
        bytes.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                append(BASE32_ALPHABET[(buffer shr bits) and 31])
            }
        }
        if (bits > 0) append(BASE32_ALPHABET[(buffer shl (5 - bits)) and 31])
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
