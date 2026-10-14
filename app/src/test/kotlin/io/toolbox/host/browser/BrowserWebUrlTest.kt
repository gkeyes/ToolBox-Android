package io.toolbox.host.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserWebUrlTest {
    @Test fun acceptsAddressesChromiumLeavesUnescaped() {
        for (url in listOf("https://example.com/a|b", "https://example.com/?q={x}^y", "https://example.com/`code`", "http://example.com:8080")) {
            assertEquals(url, "example.com", BrowserWebUrl.host(url))
            assertEquals(url, BrowserLinkKind.Web, BrowserLinkPolicy.classify(url))
        }
        assertEquals("a_b.example.com", BrowserWebUrl.host("https://A_B.example.com/"))
        assertEquals("a_b.example.com", BrowserFilterValidation.urlHost("https://a_b.example.com/ad.js"))
    }

    @Test fun rejectsUnsafeOrNonWebAddresses() {
        for (url in listOf(
            "", "https://", "https:///path", "ftp://example.com", "https://user@example.com", "https://example.com:0",
            "https://example.com:99999", "https://example.com:", "https://exa%6dple.com", "https://example.com/%0a",
            "https://example.com/%C2%85", "https://example.com/a b", "https://example.com\\@evil.com", "https://ex<ample.com",
            "https://[]/", "https://[not-an-ip]/", "https://[::1]443/",
        )) assertNull(url, BrowserWebUrl.host(url))
        assertEquals("[::1]", BrowserWebUrl.host("https://[::1]:8443/"))
    }

    @Test fun capabilityOriginsAndRuleSitesFollowTheSameGrammar() {
        assertEquals("https://a_b.example.com:8443", BrowserCapabilityPolicy.origin("https://A_B.example.com:8443/a|b"))
        assertEquals("https://example.com", BrowserCapabilityPolicy.origin("https://example.com/?q={x}"))
        assertNull(BrowserCapabilityPolicy.origin("https://user@example.com/"))
        assertEquals("a_b.example.com", BrowserFilterValidation.host("a_b.example.com", site = true))
        assertNull(BrowserFilterValidation.host("a_b.example.com"))
    }
}
