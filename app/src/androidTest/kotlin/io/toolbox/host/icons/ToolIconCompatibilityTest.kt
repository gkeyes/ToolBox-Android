package io.toolbox.host.icons

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.toolbox.core.data.*
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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

    @Test
    fun hotCacheReadDoesNotReadTheCatalogOrInstalledFiles() = runBlocking {
        InstalledIconFixture().use { fixture ->
            var fileRootReads = 0
            var decodes = 0
            val loader = ToolIconLoader(fixture.catalog, decode = {
                decodes++
                ToolIconDecoder.decode(it)
            }, privateFilesRoot = { fileRootReads++; fixture.root })
            val bitmap = requireNotNull(loader.load(fixture.id, 1))
            val catalogReads = fixture.catalogReads.get()
            val rootReads = fileRootReads
            repeat(3) { assertSame(bitmap, loader.cached(fixture.id, 1)) }
            assertNull(loader.cached(fixture.id, 2))
            assertNull(loader.cached("io.toolbox.other", 1))
            assertEquals(catalogReads, fixture.catalogReads.get())
            assertEquals(rootReads, fileRootReads)
            assertEquals(1, decodes)
        }
    }

    @Test
    fun duplicateLoadsShareCatalogReadsWhenTheInitiatingConsumerIsCancelled() = runBlocking {
        InstalledIconFixture().use { fixture ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val decodes = AtomicInteger()
            val loader = ToolIconLoader(fixture.catalog, decode = { source ->
                decodes.incrementAndGet()
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                ToolIconDecoder.decode(source)
            }, privateFilesRoot = { fixture.root })
            val first = async(start = CoroutineStart.UNDISPATCHED) { loader.load(fixture.id, 1) }
            try {
                withContext(Dispatchers.IO) { assertTrue(started.await(10, TimeUnit.SECONDS)) }
                val favorite = async(start = CoroutineStart.UNDISPATCHED) { loader.load(fixture.id, 1) }
                val grouped = async(start = CoroutineStart.UNDISPATCHED) { loader.load(fixture.id, 1) }
                first.cancelAndJoin()
                release.countDown()
                val bitmap = requireNotNull(withTimeout(10_000) { favorite.await() })
                assertSame(bitmap, withTimeout(10_000) { grouped.await() })
                assertEquals(1, decodes.get())
                assertEquals("One catalog lookup and one full-version recheck for the shared batch", 2, fixture.catalogReads.get())
                assertSame(bitmap, loader.load(fixture.id, 1))
                assertEquals("The next batch must revalidate even a hot cache entry", 3, fixture.catalogReads.get())
            } finally {
                release.countDown()
                first.cancelAndJoin()
            }
        }
    }

    @Test
    fun sameIdentityInvalidationDoesNotReuseACompletedSharedResult() = runBlocking {
        InstalledIconFixture().use { fixture ->
            val loader = ToolIconLoader(fixture.catalog, privateFilesRoot = { fixture.root })
            val original = requireNotNull(loader.load(fixture.id, 1))
            fixture.writeIcon("blue")
            // Restore/overwrite may preserve every ToolVersion field. Explicit invalidation
            // must still retire both the bitmap and any completed shared request.
            loader.invalidate(fixture.id)
            assertNull(loader.cached(fixture.id, 1))
            val replacement = requireNotNull(loader.load(fixture.id, 1))
            assertNotSame(original, replacement)
            assertEquals(Color.BLUE, replacement.getPixel(128, 128))
        }
    }

    @Test
    fun invalidationClearsNegativeCacheAndRefreshesACollectorThatSubscribesLater() = runBlocking {
        InstalledIconFixture().use { fixture ->
            var decodes = 0
            val loader = ToolIconLoader(fixture.catalog, decode = {
                decodes++
                if (decodes == 1) null else ToolIconDecoder.decode(it)
            }, privateFilesRoot = { fixture.root })
            assertNull(loader.load(fixture.id, 1))
            assertNull(loader.load(fixture.id, 1))
            assertEquals(1, decodes)
            val refreshes = loader.invalidations(fixture.id)
            loader.invalidate(fixture.id)
            withTimeout(10_000) { refreshes.first() }
            assertNotNull(loader.load(fixture.id, 1))
            assertEquals(2, decodes)
        }
    }

    @Test
    fun sameVersionNumberStillUsesEveryInstalledIdentityField() = runBlocking {
        InstalledIconFixture().use { fixture ->
            val original = fixture.initialTool.currentVersion
            val replacements = listOf(
                original.copy(version = "1.0.0+replacement"),
                original.copy(bundleLocator = BundleLocator("miniapps/${fixture.id}/unexpected/bundle")),
                original.copy(bundleBytes = original.bundleBytes + 1),
                original.copy(integrityHash = "replacement"),
                original.copy(installedAt = original.installedAt + 1),
            )
            replacements.forEach { replacement ->
                fixture.current.value = fixture.initialTool
                fixture.writeIcon("red")
                val loader = ToolIconLoader(fixture.catalog, privateFilesRoot = { fixture.root })
                val oldBitmap = requireNotNull(loader.load(fixture.id, 1))
                fixture.writeIcon("blue")
                fixture.current.value = fixture.initialTool.copy(currentVersion = replacement)
                val updated = loader.load(fixture.id, 1)
                assertNotSame("A changed full identity cannot reuse the old bitmap", oldBitmap, updated)
                if (replacement.bundleLocator != original.bundleLocator) {
                    assertNull("Unexpected bundle locations remain blocked", updated)
                    assertNull(loader.cached(fixture.id, 1))
                } else {
                    assertEquals(Color.BLUE, requireNotNull(updated).getPixel(128, 128))
                    assertSame(updated, loader.cached(fixture.id, 1))
                }
            }
        }
    }

    @Test
    fun replacementDuringDecodeCannotRepopulateTheInvalidatedCache() = runBlocking {
        InstalledIconFixture().use { fixture ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val attempts = AtomicInteger()
            val loader = ToolIconLoader(fixture.catalog, decode = { source ->
                if (attempts.incrementAndGet() == 1) {
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                ToolIconDecoder.decode(source)
            }, privateFilesRoot = { fixture.root })
            val oldLoad = async { loader.load(fixture.id, 1) }
            try {
                // The test coroutine must yield so the load can enter its IO dispatcher.
                withContext(Dispatchers.IO) { assertTrue(started.await(10, TimeUnit.SECONDS)) }
                fixture.writeIcon("blue")
                fixture.current.value = fixture.initialTool.copy(currentVersion = fixture.initialTool.currentVersion.copy(
                    integrityHash = "replacement", installedAt = 2,
                ))
                val invalidation = async(start = CoroutineStart.UNDISPATCHED) { loader.invalidate(fixture.id) }
                release.countDown()
                assertNull("The post-decode check rejects the old full identity", withTimeout(10_000) { oldLoad.await() })
                withTimeout(10_000) { invalidation.await() }
                assertNull(loader.cached(fixture.id, 1))
                val bitmap = requireNotNull(loader.load(fixture.id, 1))
                assertEquals(Color.BLUE, bitmap.getPixel(128, 128))
                assertSame(bitmap, loader.cached(fixture.id, 1))
                assertEquals(2, attempts.get())
            } finally {
                release.countDown()
                oldLoad.cancelAndJoin()
            }
        }
    }

    private suspend fun withInstalledIcon(action: suspend (Path, InstalledTool, CatalogRepository) -> Unit) {
        InstalledIconFixture().use { fixture -> action(fixture.root, fixture.initialTool, fixture.catalog) }
    }

    private fun svg(content: String) = ToolIconSource(("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 100 100">""" + content + "</svg>").toByteArray(), true)
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
