package io.toolbox.tool.runtime

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** CSS pixels relative to the local tool document's viewport. Insets clip the element itself. */
data class RuntimeEmbeddedBrowserBounds(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val viewportWidth: Double,
    val clipTop: Double,
    val clipRight: Double,
    val clipBottom: Double,
    val clipLeft: Double,
) {
    init {
        require(listOf(x, y, width, height, viewportWidth, clipTop, clipRight, clipBottom, clipLeft).all(Double::isFinite))
        require(width >= 0 && height >= 0 && viewportWidth > 0)
        require(clipTop >= 0 && clipRight >= 0 && clipBottom >= 0 && clipLeft >= 0)
    }
}

/** A clipped native surface never extends beyond the owning tool WebView. */
internal data class RuntimeEmbeddedBrowserLayout(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val playerLeft: Int,
    val playerTop: Int,
    val playerWidth: Int,
    val playerHeight: Int,
)

internal fun RuntimeEmbeddedBrowserBounds.layout(parentWidth: Int, parentHeight: Int): RuntimeEmbeddedBrowserLayout? {
    if (parentWidth <= 0 || parentHeight <= 0 || width == 0.0 || height == 0.0) return null
    val scale = parentWidth / viewportWidth
    val playerWidth = ceil(width * scale)
    val playerHeight = ceil(height * scale)
    // The page controls CSS, but cannot allocate an arbitrarily large native surface.
    val largestSurface = max(parentWidth, parentHeight).toDouble() * 4
    require(scale.isFinite() && playerWidth.isFinite() && playerHeight.isFinite())
    require(playerWidth in 1.0..largestSurface && playerHeight in 1.0..largestSurface)
    val rawLeft = x * scale
    val rawTop = y * scale
    val clippedLeft = (x + clipLeft) * scale
    val clippedTop = (y + clipTop) * scale
    val clippedRight = (x + width - clipRight) * scale
    val clippedBottom = (y + height - clipBottom) * scale
    require(listOf(rawLeft, rawTop, clippedLeft, clippedTop, clippedRight, clippedBottom).all(Double::isFinite))
    val left = ceil(clippedLeft.coerceIn(0.0, parentWidth.toDouble())).toInt()
    val top = ceil(clippedTop.coerceIn(0.0, parentHeight.toDouble())).toInt()
    val right = floor(clippedRight.coerceIn(0.0, parentWidth.toDouble())).toInt()
    val bottom = floor(clippedBottom.coerceIn(0.0, parentHeight.toDouble())).toInt()
    if (right <= left || bottom <= top) return null
    return RuntimeEmbeddedBrowserLayout(
        left, top, right - left, bottom - top,
        floor(rawLeft - left).toInt(), floor(rawTop - top).toInt(),
        playerWidth.toInt(), playerHeight.toInt(),
    )
}

/** Remote content lives in a separate WebView with no tool bridge or local asset loader. */
interface RuntimeEmbeddedBrowserHandler {
    suspend fun open(url: String, bounds: RuntimeEmbeddedBrowserBounds, beforePresent: suspend () -> Unit): String
    suspend fun update(id: String, bounds: RuntimeEmbeddedBrowserBounds, beforePresent: suspend () -> Unit)
    suspend fun close(id: String)
    fun resetForNavigation() = Unit
    fun setForeground(foreground: Boolean) = Unit
    fun release() = Unit
}

internal fun isEmbeddedBrowserId(id: String): Boolean =
    id.length == 41 && id.startsWith("embedded-") && id.drop(9).all { it in '0'..'9' || it in 'a'..'f' }

/** Accept only a provider player, never a caller-supplied page, origin, script or redirect endpoint. */
internal fun normalizedYouTubeEmbedUrl(url: String): String {
    require(url.none { it.isISOControl() || it.isWhitespace() })
    val parsed = runCatching { URI(url) }.getOrNull() ?: throw IllegalArgumentException("url")
    require(parsed.scheme == "https" && parsed.rawUserInfo == null && parsed.port == -1 && parsed.rawFragment == null)
    val host = parsed.host?.lowercase() ?: throw IllegalArgumentException("url")
    require(host in setOf("youtube.com", "www.youtube.com", "youtube-nocookie.com", "www.youtube-nocookie.com"))
    val match = Regex("^/embed/([A-Za-z0-9_-]{11})$").matchEntire(parsed.rawPath.orEmpty())
        ?: throw IllegalArgumentException("url")
    val parameters = parsed.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).map { field ->
        URLDecoder.decode(field.substringBefore('='), StandardCharsets.UTF_8) to
            URLDecoder.decode(field.substringAfter('=', ""), StandardCharsets.UTF_8)
    }
    fun seconds(name: String): Long? {
        val values = parameters.filter { it.first == name }.map { it.second }
        require(values.size <= 1)
        return values.singleOrNull()?.let { raw ->
            require(raw.matches(Regex("[0-9]{1,10}")))
            raw.toLong().also { require(it <= Int.MAX_VALUE) }
        }
    }
    val start = seconds("start")
    val end = seconds("end")
    require(end == null || end > (start ?: 0))
    // Rebuild from validated pieces; arbitrary source query parameters cannot change the player identity.
    return buildString {
        append("https://www.youtube-nocookie.com/embed/").append(match.groupValues[1])
        append("?autoplay=1&playsinline=1")
        start?.let { append("&start=").append(it) }
        end?.let { append("&end=").append(it) }
    }
}

internal val EMBEDDED_BROWSER_BOUND_FIELDS = arrayOf(
    "x", "y", "width", "height", "viewportWidth", "clipTop", "clipRight", "clipBottom", "clipLeft",
)

internal fun RpcValue.ObjectValue.embeddedBrowserBounds(): RuntimeEmbeddedBrowserBounds {
    fun number(name: String): Double = (required(name) as? RpcValue.Number)?.value ?: throw IllegalArgumentException(name)
    return RuntimeEmbeddedBrowserBounds(
        number("x"), number("y"), number("width"), number("height"), number("viewportWidth"),
        number("clipTop"), number("clipRight"), number("clipBottom"), number("clipLeft"),
    )
}
