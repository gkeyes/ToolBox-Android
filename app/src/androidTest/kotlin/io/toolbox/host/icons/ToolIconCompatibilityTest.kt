package io.toolbox.host.icons

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.core.data.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolIconCompatibilityTest {
    @Test
    fun androidRasterFormatsAndAnimatedGifFirstFrameDecodeAsThumbnails() {
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.WEBP_LOSSLESS).forEach { format ->
                val bytes = ByteArrayOutputStream().also { assertTrue(bitmap.compress(format, 100, it)) }.toByteArray()
                val result = requireNotNull(ToolIconDecoder.decode(ToolIconSource(bytes, false)))
                assertEquals(ToolIconDecoder.SIZE, result.width)
                assertTrue(Color.red(result.getPixel(128, 128)) > 240)
                result.recycle()
            }
            val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            val embedded = java.util.Base64.getEncoder().encodeToString(png)
            val localImage = requireNotNull(ToolIconDecoder.decode(svg("<image xlink:href='data:image/png;base64,$embedded' width='100' height='100'/>")))
            assertEquals(Color.RED, localImage.getPixel(128, 128))
            localImage.recycle()
            // Two 1x1 frames: red then blue. decodeBitmap must return a static first frame.
            val gif = hex("47494638396101000100800000ff00000000ff" +
                "21f90400010000002c0000000001000100000202440100" +
                "21f90400010000002c00000000010001000002024c01003b")
            val result = requireNotNull(ToolIconDecoder.decode(ToolIconSource(gif, false)))
            assertEquals(Color.RED, result.getPixel(128, 128))
            result.recycle()
        } finally { bitmap.recycle() }
    }

    @Test
    fun localUseClipMaskAndCssRenderActualColoredPixels() {
        val source = svg("""
            <style>.paint { fill: #ff0000; clip-path: url(#clip); mask: url(#mask); }</style>
            <defs>
                <clipPath id="clip"><circle cx="50" cy="50" r="35"/></clipPath>
                <mask id="mask"><rect width="100" height="100" fill="white"/></mask>
                <rect id="shape" width="100" height="100"/>
            </defs>
            <use xlink:href="#shape" class="paint"/>
        """)
        val bitmap = requireNotNull(ToolIconDecoder.decode(source))
        try {
            assertEquals(Color.RED, bitmap.getPixel(128, 128))
            assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
        } finally { bitmap.recycle() }
    }

    @Test
    fun gradientInheritanceAndCssReferencedPatternsUseRendererCapabilities() {
        val bitmap = requireNotNull(ToolIconDecoder.decode(svg("""
            <style>rect.paint { fill: url(#pattern); }</style>
            <defs>
                <linearGradient id="base"><stop stop-color="red"/></linearGradient>
                <linearGradient id="copy" xlink:href="#base"/>
                <pattern id="pattern" width="1" height="1" patternContentUnits="objectBoundingBox">
                    <rect width="1" height="1" fill="url(#copy)"/>
                </pattern>
            </defs>
            <rect class="paint" width="100" height="100"/>
        """)))
        try { assertTrue(Color.red(bitmap.getPixel(128, 128)) > 240) }
        finally { bitmap.recycle() }
    }

    @Test
    fun externalResourcesScriptsEntitiesAndReferenceCyclesFallBack() {
        val blocked = listOf(
            "<script>alert(1)</script>",
            "<rect width='100' height='100' onload='alert(1)'/>",
            "<use xlink:href='https://example.invalid/external.svg#shape'/>",
            "<image href='file:///data/local/private.png' width='100' height='100'/>",
            "<rect style='fill:url(https://example.invalid/pattern.svg#p)'/>",
            "<style>@import 'https://example.invalid/style.css';</style><rect/>",
            "<style>.paint { fill: u\\72l(https://example.invalid/p); }</style><rect class='paint'/>",
            "<defs><g id='a'><use xlink:href='#b'/></g><g id='b'><use xlink:href='#a'/></g></defs><use xlink:href='#a'/>",
            "<defs><linearGradient id='a' xlink:href='#b'/><linearGradient id='b' xlink:href='#a'/></defs><rect fill='url(#a)'/>",
            "<style>.loop { fill:url(#p); }</style><defs><pattern id='p' width='1' height='1'><rect class='loop' width='100' height='100'/></pattern></defs><rect fill='url(#p)'/>",
        )
        blocked.forEach { content -> assertNull(content, ToolIconDecoder.decode(svg(content))) }
        val entity = """<!DOCTYPE svg [<!ENTITY leak SYSTEM "file:///data/local/private.txt">]><svg xmlns="http://www.w3.org/2000/svg"><text>&leak;</text></svg>"""
        assertNull(ToolIconDecoder.decode(ToolIconSource(entity.toByteArray(), true)))
        assertNull(ToolIconDecoder.decode(ToolIconSource(byteArrayOf(1, 2, 3), false)))
    }

    @Test
    fun lowMemoryAndDecodeAllocationFailureRetryButInvalidContentIsCached() = runBlocking {
        withInstalledIcon { root, tool, catalog ->
            var heap = 1L
            var decodes = 0
            val loader = ToolIconLoader(catalog, decode = { decodes++; ToolIconDecoder.decode(it) },
                availableHeapBytes = { heap }, privateFilesRoot = { root })
            assertNull(loader.load(tool.metadata.id))
            assertEquals(0, decodes)
            heap = Long.MAX_VALUE
            assertNotNull(loader.load(tool.metadata.id))
            assertNotNull(loader.load(tool.metadata.id))
            assertEquals("Recovery is triggered by next load, without an internal retry loop", 1, decodes)

            var attempts = 0
            val oom = ToolIconLoader(catalog, decode = {
                attempts++
                if (attempts == 1) throw OutOfMemoryError("synthetic allocation pressure")
                ToolIconDecoder.decode(it)
            }, privateFilesRoot = { root })
            assertNull(oom.load(tool.metadata.id))
            assertNotNull(oom.load(tool.metadata.id))
            assertEquals(2, attempts)

            var invalidAttempts = 0
            val invalid = ToolIconLoader(catalog, decode = {
                invalidAttempts++
                ToolIconDecoder.decode(ToolIconSource(byteArrayOf(1, 2, 3), false))
            }, privateFilesRoot = { root })
            assertNull(invalid.load(tool.metadata.id))
            assertNull(invalid.load(tool.metadata.id))
            assertEquals(1, invalidAttempts)
        }
    }

    @Test
    fun cancelledDecodeDoesNotPoisonTheVersionCache() = runBlocking {
        withInstalledIcon { root, tool, catalog ->
            var attempts = 0
            val loader = ToolIconLoader(catalog, decode = {
                attempts++
                if (attempts == 1) throw CancellationException("synthetic cancellation")
                ToolIconDecoder.decode(it)
            }, privateFilesRoot = { root })
            val cancelled = async { loader.load(tool.metadata.id) }
            try { cancelled.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
            assertNotNull(loader.load(tool.metadata.id))
            assertEquals(2, attempts)
        }
    }

    private suspend fun withInstalledIcon(action: suspend (Path, InstalledTool, CatalogRepository) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "icon-compat-")
        val id = "io.toolbox.iconfixture"
        val locator = BundleLocator("miniapps/$id/versions/1/bundle")
        val bundle = Files.createDirectories(root.resolve(locator.value))
        Files.write(bundle.resolve("manifest.json"), """{"schemaVersion":1,"id":"$id","name":"Icon fixture","version":"1.0.0","versionCode":1,"entry":"index.html","icon":"icon.svg","apiVersion":"1.0","minHostVersion":"0.3.0","permissions":[],"securityProfile":"strict"}""".toByteArray())
        Files.write(bundle.resolve("icon.svg"), svg("<rect width='100' height='100' fill='red'/>").bytes)
        val tool = InstalledTool(ToolMetadata(id, "Icon fixture", SecurityProfile.STRICT, 1),
            ToolVersion(id, 1, "1.0.0", locator, 1, "fixture", 1), null)
        val catalog = object : CatalogRepository {
            override fun observeCatalogProjection() = flowOf(emptyList<CatalogEntry>())
            override fun observeTools() = flowOf(listOf(tool))
            override fun observeTool(toolId: String) = flowOf(tool.takeIf { it.metadata.id == toolId })
        }
        try { action(root, tool, catalog) } finally { assertTrue(root.toFile().deleteRecursively()) }
    }

    private fun svg(content: String) = ToolIconSource(("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 100 100">""" + content + "</svg>").toByteArray(), true)
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
