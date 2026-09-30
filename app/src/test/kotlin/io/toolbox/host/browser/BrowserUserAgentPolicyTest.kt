package io.toolbox.host.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUserAgentPolicyTest {
    private val defaultWebView =
        "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/125.0.6422.147 Mobile Safari/537.36"

    @Test fun defaultModeIsChromeMobile() {
        assertEquals(BrowserUserAgentMode.ChromeMobile, BrowserUserAgentMode.fromStored(null))
        assertEquals(BrowserUserAgentMode.ChromeMobile, BrowserUserAgentMode.fromStored("unknown"))
    }

    @Test fun chromeMobileRemovesWebViewMarkersWithoutLosingEngineVersion() {
        val profile = BrowserUserAgentPolicy.profile(BrowserUserAgentMode.ChromeMobile, defaultWebView)

        assertFalse(profile.userAgent.contains("; wv"))
        assertFalse(profile.userAgent.contains("Version/4.0"))
        assertTrue(profile.userAgent.contains("Chrome/125.0.6422.147"))
        assertTrue(profile.userAgent.contains("Mobile Safari/537.36"))
        assertEquals("125.0.6422.147", profile.chromeVersion)
        assertTrue(profile.mobile)
        assertTrue(profile.overrideMetadata)
    }

    @Test fun webViewModePreservesSystemIdentity() {
        val profile = BrowserUserAgentPolicy.profile(BrowserUserAgentMode.AndroidWebView, defaultWebView)

        assertEquals(defaultWebView, profile.userAgent)
        assertFalse(profile.overrideMetadata)
    }

    @Test fun desktopModeUsesCurrentChromiumVersionAndDropsMobileIdentity() {
        val profile = BrowserUserAgentPolicy.profile(BrowserUserAgentMode.DesktopChrome, defaultWebView)

        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.6422.147 Safari/537.36",
            profile.userAgent,
        )
        assertFalse(profile.mobile)
        assertEquals("Linux", profile.platform)
        assertTrue(profile.overrideMetadata)
    }
}
