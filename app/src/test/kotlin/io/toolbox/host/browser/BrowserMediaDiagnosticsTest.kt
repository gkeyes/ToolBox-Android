package io.toolbox.host.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserMediaDiagnosticsTest {
    @Test fun recognizesMediaResourcesWithoutLoggingOrdinaryAssets() {
        assertTrue(BrowserMediaDiagnostics.isRelevantResource("https://cdn.example/video/master.m3u8"))
        assertTrue(BrowserMediaDiagnostics.isRelevantResource("https://cdn.example/api/play", "video/mp4"))
        assertTrue(BrowserMediaDiagnostics.isRelevantResource("https://cdn.example/hls.min.js"))
        assertFalse(BrowserMediaDiagnostics.isRelevantResource("https://cdn.example/site.css", "text/css"))
    }

    @Test fun stripsQueryAndFragmentFromUrls() {
        assertEquals(
            "https://cdn.example/path/video.m3u8",
            BrowserMediaDiagnostics.sanitizeUrl("https://cdn.example/path/video.m3u8?token=secret#frag"),
        )
    }

    @Test fun consoleCaptureKeepsErrorsAndMediaWarningsOnly() {
        assertNotNull(
            BrowserMediaDiagnostics.consoleEvent(
                "ERROR",
                "Failed to load https://cdn.example/v.m3u8?token=secret",
                "https://heiliao.com/player.js?x=1",
                42,
            ),
        )
        assertNotNull(BrowserMediaDiagnostics.consoleEvent("WARNING", "Hls media error", null, 0))
        assertNull(BrowserMediaDiagnostics.consoleEvent("LOG", "ordinary page message", null, 0))
    }
}
