package io.toolbox.host.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Uses OkHttp's real Android public suffix list, loaded the way the :browser process loads it. */
@RunWith(RobolectricTestRunner::class)
class BrowserPublicSuffixTest {
    @Test fun loadedListShortensRealHosts() {
        BrowserPublicSuffix.load(RuntimeEnvironment.getApplication())
        assertTrue(BrowserPublicSuffix.ready)
        assertEquals("…example.com", browserDisplayHost("news.example.com"))
        assertEquals("example.co.uk", browserDisplayHost("www.example.co.uk"))
        assertEquals("evil.github.io", browserDisplayHost("evil.github.io"))
        assertEquals("…evil.com", browserDisplayHost("github.com.evil.com"))
        assertEquals("192.168.1.10", browserDisplayHost("192.168.1.10"))
    }
}
