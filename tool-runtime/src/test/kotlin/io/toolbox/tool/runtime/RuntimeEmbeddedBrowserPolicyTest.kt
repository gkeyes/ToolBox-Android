package io.toolbox.tool.runtime

import org.junit.Assert.*
import org.junit.Test

class RuntimeEmbeddedBrowserPolicyTest {
    @Test
    fun acceptsOnlyOfficialVideoEmbedsAndOwnsTheirPlaybackParameters() {
        val expected = "https://www.youtube-nocookie.com/embed/y8-kOYgmNdA?autoplay=1&playsinline=1&start=12&end=50"
        for (host in listOf("youtube.com", "www.youtube.com", "youtube-nocookie.com", "www.youtube-nocookie.com")) {
            assertEquals(expected, normalizedYouTubeEmbedUrl(
                "https://$host/embed/y8-kOYgmNdA?origin=https%3A%2F%2Fevil.test&autoplay=0&start=12&end=50&enablejsapi=1",
            ))
        }
    }

    @Test
    fun rejectsLookalikeHostsCredentialsNavigationEndpointsAndEncodedPaths() {
        val invalid = listOf(
            "https://www.youtube-nocookie.com.evil.test/embed/y8-kOYgmNdA",
            "https://evil.test@www.youtube.com/embed/y8-kOYgmNdA",
            "http://www.youtube.com/embed/y8-kOYgmNdA",
            "https://www.youtube.com:443/embed/y8-kOYgmNdA",
            "https://www.youtube.com/watch?v=y8-kOYgmNdA",
            "https://youtu.be/y8-kOYgmNdA",
            "https://www.youtube.com/embed/y8-kOYgmNdA/other",
            "https://www.youtube.com/%65mbed/y8-kOYgmNdA",
            "https://www.youtube.com/embed/too-short",
            "https://www.youtube.com/embed/y8-kOYgmNdA#fragment",
            "https://www.youtube.com/embed/y8-kOYgmNdA?start=-1",
            "https://www.youtube.com/embed/y8-kOYgmNdA?start=1&start=2",
            "https://www.youtube.com/embed/y8-kOYgmNdA?start=20&end=20",
            "https://www.youtube.com/embed/y8-kOYgmNdA?end=0",
            "https://www.youtube.com/embed/y8-kOYgmNdA?start=9999999999",
            "https://www.youtube.com/embed/y8-kOYgmNdA?bad=%zz",
            "https://www.youtube.com/embed/y8-kOYgmNdA\n",
            "javascript:alert(1)",
        )
        invalid.forEach { url -> assertThrows(url, IllegalArgumentException::class.java) { normalizedYouTubeEmbedUrl(url) } }
    }

    @Test
    fun clipsEveryEdgeAndKeepsTheOriginalPlayerAspectRatioAtDeviceScale() {
        val bounds = RuntimeEmbeddedBrowserBounds(-20.0, -30.0, 400.0, 225.0, 400.0, 40.0, 10.0, 5.0, 30.0)
        assertEquals(
            RuntimeEmbeddedBrowserLayout(20, 20, 720, 360, -60, -80, 800, 450),
            bounds.layout(800, 380),
        )
        val clippedByViewport = bounds.copy(clipTop = 0.0, clipLeft = 0.0, clipBottom = 0.0, clipRight = 0.0).layout(800, 300)!!
        assertEquals(0, clippedByViewport.left)
        assertEquals(0, clippedByViewport.top)
        assertEquals(760, clippedByViewport.width)
        assertEquals(300, clippedByViewport.height)
        assertEquals(-40, clippedByViewport.playerLeft)
        assertEquals(-60, clippedByViewport.playerTop)
        assertEquals(800, clippedByViewport.playerWidth)
        assertEquals(450, clippedByViewport.playerHeight)
    }

    @Test
    fun hiddenOrDetachedSlotsDoNotCreateAVisibleNativeSurface() {
        val bounds = RuntimeEmbeddedBrowserBounds(0.0, 0.0, 400.0, 225.0, 400.0, 0.0, 0.0, 0.0, 0.0)
        assertNull(bounds.copy(y = 900.0).layout(400, 800))
        assertNull(bounds.copy(x = -400.0).layout(400, 800))
        assertNull(bounds.copy(clipTop = 225.0).layout(400, 800))
        assertNull(bounds.copy(width = 0.0).layout(400, 800))
        assertNull(bounds.layout(0, 0))
    }

    @Test
    fun rejectsNonFiniteGeometryAndUnboundedNativeAllocations() {
        val bounds = RuntimeEmbeddedBrowserBounds(0.0, 0.0, 400.0, 225.0, 400.0, 0.0, 0.0, 0.0, 0.0)
        assertThrows(IllegalArgumentException::class.java) { bounds.copy(x = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { bounds.copy(clipTop = -1.0) }
        assertThrows(IllegalArgumentException::class.java) { bounds.copy(viewportWidth = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { bounds.copy(height = 1e20).layout(400, 800) }
        assertThrows(IllegalArgumentException::class.java) { bounds.copy(viewportWidth = Double.MIN_VALUE).layout(400, 800) }
    }
}
