package io.toolbox.host.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.core.data.BundleLocator
import io.toolbox.core.data.CatalogEntry
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.InstalledTool
import io.toolbox.core.data.SecurityProfile
import io.toolbox.core.data.ToolMetadata
import io.toolbox.core.data.ToolVersion
import io.toolbox.tool.runtime.RuntimeIdentity
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolIconLoadingTest {
    @get:Rule val temporary = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

    @Test
    fun decodesRasterAndStaticSvgAtOneBoundedSizeWithoutCroppingOrTinting() {
        val raster = requireNotNull(ToolIconDecoder.decode(ToolIconSource(png(Color.BLUE, 64, 32), false)))
        assertEquals(256, raster.width)
        assertEquals(256, raster.height)
        assertEquals(0, Color.alpha(raster.getPixel(128, 0)))
        assertEquals(Color.BLUE, raster.getPixel(128, 128))
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" fill="#ff0000"/></svg>"""
        val vector = requireNotNull(ToolIconDecoder.decode(ToolIconSource(svg.toByteArray(), true)))
        assertEquals(Color.RED, vector.getPixel(128, 128))
        assertNull(ToolIconDecoder.decode(ToolIconSource(byteArrayOf(0, 1, 2), false)))
    }

    @Test
    fun whiteTransparentMarksHaveALegibleBackingOnLightAndDarkSurfaces() {
        val mark = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(mark).drawCircle(32f, 32f, 16f, Paint().apply { color = Color.WHITE })
        val bytes = ByteArrayOutputStream().also { mark.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val decoded = requireNotNull(ToolIconDecoder.decode(ToolIconSource(bytes, false)))
        assertEquals(Color.WHITE, decoded.getPixel(128, 128))
        assertEquals(Color.rgb(36, 41, 47), decoded.getPixel(128, 12))
    }

    @Test
    fun cacheCoalescesLoadsOffMainAndCannotReturnAReplacedOrDeletedToolsIcon() = runBlocking {
        val repository = IconCatalog()
        val reads = AtomicInteger()
        val loader = ToolIconLoader(repository) {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            reads.incrementAndGet()
            temporary.root.toPath()
        }
        val first = install(1, Color.RED)
        repository.tool.value = first
        val concurrent = List(6) { async { loader.load(first.metadata.id, 1) } }.awaitAll()
        assertEquals(1, reads.get())
        concurrent.forEach { assertSame(concurrent.first(), it) }

        val second = install(2, Color.BLUE)
        repository.tool.value = second
        assertNull(loader.load(second.metadata.id, 1))
        assertEquals(Color.BLUE, requireNotNull(loader.load(second.metadata.id, 2)).getPixel(128, 128))
        assertEquals(2, reads.get())
        loader.invalidate(second.metadata.id)
        loader.load(second.metadata.id, 2)
        assertEquals(3, reads.get())
        repository.tool.value = null
        assertNull(loader.load(first.metadata.id))
    }

    private fun install(version: Int, color: Int): InstalledTool {
        val id = "io.example.icon"
        val locator = RuntimeIdentity.expectedBundleLocator(id, version)
        val directory = Files.createDirectories(temporary.root.toPath().resolve(locator))
        Files.write(directory.resolve("icon.png"), png(color))
        Files.writeString(directory.resolve("manifest.json"), """
            {"schemaVersion":1,"id":"$id","name":"图标测试","version":"1.0.$version","versionCode":$version,
             "entry":"index.html","icon":"icon.png","apiVersion":"1.0","minHostVersion":"0.3.2",
             "permissions":[],"securityProfile":"strict"}
        """.trimIndent())
        return InstalledTool(
            ToolMetadata(id, "图标测试", SecurityProfile.STRICT, version.toLong()),
            ToolVersion(id, version, "1.0.$version", BundleLocator(locator), 100, "a".repeat(64), version.toLong()), null,
        )
    }

    private fun png(color: Int, width: Int = 64, height: Int = 64): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private class IconCatalog : CatalogRepository {
        val tool = MutableStateFlow<InstalledTool?>(null)
        override fun observeTool(toolId: String): Flow<InstalledTool?> = tool.map { it?.takeIf { value -> value.metadata.id == toolId } }
        override fun observeTools(): Flow<List<InstalledTool>> = tool.map { listOfNotNull(it) }
        override fun observeCatalogProjection(): Flow<List<CatalogEntry>> = flowOf(emptyList())
    }
}
