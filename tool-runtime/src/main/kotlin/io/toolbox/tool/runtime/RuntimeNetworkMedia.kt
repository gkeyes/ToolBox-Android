package io.toolbox.tool.runtime

import java.io.InputStream
import java.net.URI

/** Local, runtime-owned URL; the remote source never appears in the player URL. */
data class RuntimeNetworkMediaSession(val sessionId: String, val url: String)

data class RuntimeNetworkMediaResponse(
    val status: Int,
    val reason: String,
    val mimeType: String,
    val headers: Map<String, String>,
    val body: InputStream,
)

object RuntimeNetworkMediaRoute {
    const val PREFIX = "/.toolbox/media/"

    fun token(url: String, origin: String): String? {
        if (!RuntimeIdentity.isExactLocalUrl(url, origin)) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.rawQuery != null || !uri.rawPath.startsWith(PREFIX)) return null
        return uri.rawPath.removePrefix(PREFIX).takeIf {
            it.length == 32 && it.all { char -> char in '0'..'9' || char in 'a'..'f' }
        }
    }
}

internal fun isNetworkMediaId(value: String): Boolean =
    value.length == 38 && value.startsWith("media-") && value.drop(6).all { it in '0'..'9' || it in 'a'..'f' }
