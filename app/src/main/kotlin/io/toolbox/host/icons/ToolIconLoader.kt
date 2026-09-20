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
import io.toolbox.host.HostTrace
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal class ToolIconLoader(
    private val catalog: CatalogRepository,
    private val decode: (ToolIconSource) -> Bitmap? = ToolIconDecoder::decode,
    availableHeapBytes: () -> Long = io.toolbox.core.data.ResourceCapacity::availableHeapBytes,
    privateFilesRoot: () -> Path,
) {
    private val reader = InstalledToolIconReader(privateFilesRoot, availableHeapBytes)
    private val loads = ToolIconLoadCoordinator<Bitmap?>()
    private val cacheLock = Any()
    // This is an index into the LRU, not another bitmap cache or a reduced identity key.
    private val cachedVersions = mutableMapOf<String, ToolVersion>()
    private val observers = mutableMapOf<String, MutableSet<SendChannel<Unit>>>()
    private data class Cached(val bitmap: Bitmap?)
    private val cache = object : LruCache<ToolVersion, Cached>(4 * 1024 * 1024) {
        override fun sizeOf(key: ToolVersion, value: Cached): Int = value.bitmap?.allocationByteCount ?: 64 * 1024

        override fun entryRemoved(evicted: Boolean, key: ToolVersion, oldValue: Cached, newValue: Cached?) {
            synchronized(cacheLock) {
                if (newValue == null && cachedVersions[key.toolId] == key) cachedVersions.remove(key.toolId)
            }
            if (evicted) HostTrace.bestEffortSection("icon.cache.evict") { }
        }
    }

    /** Memory only: Compose can draw a validated hot entry before starting its IO load. */
    fun cached(toolId: String, expectedVersionCode: Int? = null): Bitmap? = synchronized(cacheLock) {
        val version = cachedVersions[toolId] ?: return@synchronized null
        if (expectedVersionCode != null && version.versionCode != expectedVersionCode) return@synchronized null
        cache.get(version)?.bitmap
    }

    /** The initial signal also covers invalidation between the first cache read and subscription. */
    fun invalidations(toolId: String): Flow<Unit> = callbackFlow {
        synchronized(cacheLock) {
            observers.getOrPut(toolId) { mutableSetOf() }.add(channel)
            trySend(Unit)
        }
        awaitClose {
            synchronized(cacheLock) {
                observers[toolId]?.let { channels ->
                    channels.remove(channel)
                    if (channels.isEmpty()) observers.remove(toolId)
                }
            }
        }
    }.conflate()

    suspend fun load(toolId: String, expectedVersionCode: Int? = null): Bitmap? = loads.share(toolId, expectedVersionCode) {
        loads.withTool(toolId) {
            try {
                val tool = HostTrace.bestEffortAsyncSection("icon.catalog.lookup") {
                    catalog.observeTool(toolId).first()
                }
                val version = tool?.currentVersion
                val cached = synchronized(cacheLock) {
                    // Retire a previously hot identity as soon as the catalog changes, including
                    // retryable failures while reading its replacement or an already removed tool.
                    cachedVersions[toolId]?.takeIf { it != version }?.let(cache::remove)
                    version?.let(cache::get)
                }
                if (tool == null || version == null) return@withTool null
                if (expectedVersionCode != null && version.versionCode != expectedVersionCode) return@withTool null
                cached?.let {
                    HostTrace.bestEffortSection("icon.cache.hit") { }
                    return@withTool it.bitmap
                }
                HostTrace.bestEffortSection("icon.cache.miss") { }
                val bitmap = loads.decode {
                    HostTrace.bestEffortSection("icon.decode") { reader.read(tool)?.let(decode) }
                }
                currentCoroutineContext().ensureActive()
                val currentVersion = HostTrace.bestEffortAsyncSection("icon.catalog.recheck") {
                    catalog.observeTool(toolId).first()?.currentVersion
                }
                if (currentVersion != version) return@withTool null
                synchronized(cacheLock) {
                    cachedVersions[toolId] = version
                    cache.put(version, Cached(bitmap))
                }
                bitmap
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // IO, probe/resource and catalog failures remain retryable on the next load.
                null
            } catch (_: OutOfMemoryError) {
                null
            }
        }
    }

    suspend fun invalidate(toolId: String) {
        withContext(Dispatchers.IO) {
            loads.withTool(toolId) {
                loads.forgetShared(toolId)
                val notify = synchronized(cacheLock) {
                    cache.snapshot().keys.filter { it.toolId == toolId }.forEach(cache::remove)
                    observers[toolId]?.toList().orEmpty()
                }
                notify.forEach { it.trySend(Unit) }
            }
        }
    }
}

internal object ToolIconDecoder {
    const val SIZE = 256

    fun decode(source: ToolIconSource): Bitmap? = try {
        val decoded = if (source.isSvg) decodeSvg(source.bytes) else decodeRaster(source.bytes)
        normalize(decoded)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    } catch (_: StackOverflowError) {
        null
    }

    private fun decodeRaster(bytes: ByteArray): Bitmap = ImageDecoder.decodeBitmap(
        ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
    ) { decoder, info, _ ->
        // Let this Android version decide supported formats. decodeBitmap returns the
        // first frame for animated inputs; the host displays only a static thumbnail.
        val width = info.size.width
        val height = info.size.height
        require(width > 0 && height > 0)
        val scale = SIZE.toDouble() / max(width, height)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetSize(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
    }

    private fun decodeSvg(bytes: ByteArray): Bitmap {
        val xml = StaticSvgPolicy.validate(bytes)
        SVG.setInternalEntitiesEnabled(false)
        SVG.deregisterExternalFileResolver()
        val svg = SVG.getFromString(xml)
        com.caverock.androidsvg.ToolBoxSvgReferenceGuard.requireAcyclic(svg)
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
        var visible = 0
        var white = 0
        var transparent = false
        for (pixel in pixels) {
            if (Color.alpha(pixel) < 128) transparent = true else {
                visible += 1
                if (Color.red(pixel) > 220 && Color.green(pixel) > 220 && Color.blue(pixel) > 220) white += 1
            }
        }
        // Count in-place instead of boxing every visible pixel into a temporary List<Int>.
        val needsBacking = transparent && visible > 0 && white > visible * 0.9
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
