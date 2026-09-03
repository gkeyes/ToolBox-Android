package io.toolbox.host.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import com.caverock.androidsvg.SVG
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.ToolVersion
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class ToolIconLoader(
    private val catalog: CatalogRepository,
    privateFilesRoot: () -> Path,
) {
    private val reader = InstalledToolIconReader(privateFilesRoot)
    private val lock = Mutex()
    private data class Cached(val bitmap: Bitmap?)
    private val cache = object : LruCache<ToolVersion, Cached>(4 * 1024 * 1024) {
        override fun sizeOf(key: ToolVersion, value: Cached): Int = value.bitmap?.allocationByteCount ?: 64 * 1024
    }

    suspend fun load(toolId: String, expectedVersionCode: Int? = null): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                val tool = catalog.observeTool(toolId).first() ?: return@withLock null
                val version = tool.currentVersion
                if (expectedVersionCode != null && version.versionCode != expectedVersionCode) return@withLock null
                cache.get(version)?.let { return@withLock it.bitmap }
                val bitmap = reader.read(tool)?.let(ToolIconDecoder::decode)
                ensureActive()
                if (catalog.observeTool(toolId).first()?.currentVersion != version) return@withLock null
                cache.put(version, Cached(bitmap))
                bitmap
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun invalidate(toolId: String) = withContext(Dispatchers.IO) {
        lock.withLock { cache.snapshot().keys.filter { it.toolId == toolId }.forEach(cache::remove) }
    }
}

internal object ToolIconDecoder {
    const val SIZE = 256

    fun decode(source: ToolIconSource): Bitmap? = try {
        val decoded = if (source.isSvg) decodeSvg(source.bytes) else decodeRaster(source.bytes)
        normalize(decoded)
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun decodeRaster(bytes: ByteArray): Bitmap = ImageDecoder.decodeBitmap(
        ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
    ) { decoder, info, _ ->
        require(info.mimeType in setOf("image/png", "image/jpeg", "image/webp"))
        val width = info.size.width
        val height = info.size.height
        require(width > 0 && height > 0 && width.toLong() * height <= 64_000_000)
        val scale = SIZE.toDouble() / max(width, height)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetSize(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
    }

    private fun decodeSvg(bytes: ByteArray): Bitmap {
        val xml = StaticSvgPolicy.validate(bytes)
        SVG.setInternalEntitiesEnabled(false)
        SVG.deregisterExternalFileResolver()
        val svg = SVG.getFromString(xml)
        if (svg.documentViewBox == null) {
            val width = svg.documentWidth.takeIf { it.isFinite() && it > 0 } ?: SIZE.toFloat()
            val height = svg.documentHeight.takeIf { it.isFinite() && it > 0 } ?: SIZE.toFloat()
            svg.setDocumentViewBox(0f, 0f, width, height)
        }
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        return Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(Canvas(it)) }
    }

    private fun normalize(source: Bitmap): Bitmap {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val visible = pixels.filter { Color.alpha(it) >= 128 }
        // White transparent marks (e.g. GitHub's logo) need the same legible backing on every surface.
        val needsBacking = pixels.any { Color.alpha(it) < 128 } && visible.isNotEmpty() &&
            visible.count { Color.red(it) > 220 && Color.green(it) > 220 && Color.blue(it) > 220 } > visible.size * 0.9
        return Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { result ->
            val canvas = Canvas(result)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (needsBacking) {
                paint.color = Color.rgb(36, 41, 47)
                canvas.drawRoundRect(RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat()), 56f, 56f, paint)
            }
            canvas.drawBitmap(source, (SIZE - source.width) / 2f, (SIZE - source.height) / 2f, paint)
        }
    }
}
