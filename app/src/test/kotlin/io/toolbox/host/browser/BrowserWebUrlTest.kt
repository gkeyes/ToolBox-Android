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
            "https://[]/", "https://[not-an-ip]/", "https://[::1]443/", "https://[:::]/", "https://[dead::beef::1]/",
            "https://[1:2:3:4:5:6:7:8:9]/", "https://a..com/", "https://.example.com/", "https://example.com../",
        )) assertNull(url, BrowserWebUrl.host(url))
        assertEquals("example.com.", BrowserWebUrl.host("https://example.com./"))
        assertEquals("[::1]", BrowserWebUrl.host("https://[::1]:8443/"))
    }

    @Test fun capabilityOriginsAndRuleSitesFollowTheSameGrammar() {
        assertEquals("https://a_b.example.com:8443", BrowserCapabilityPolicy.origin("https://A_B.example.com:8443/a|b"))
        assertEquals("https://example.com", BrowserCapabilityPolicy.origin("https://example.com/?q={x}"))
        assertNull(BrowserCapabilityPolicy.origin("https://user@example.com/"))
        assertEquals("https://example.com.", BrowserCapabilityPolicy.origin("https://example.com./"))
        assertEquals("a_b.example.com", BrowserFilterValidation.host("a_b.example.com"))
        assertNull(BrowserFilterValidation.urlHost("https://[::1]/"))
    }

    @Test fun appLinksAndCertificateLabelsAcceptWhatPagesLoad() {
        assertEquals(BrowserLinkKind.External, BrowserLinkPolicy.classify("weixin://dl/scan?a|b"))
        assertEquals(BrowserLinkKind.External, BrowserLinkPolicy.classify("alipays://platformapi/startapp?x={y}"))
        assertEquals(BrowserLinkKind.Unsupported, BrowserLinkPolicy.classify("1bad://x"))
        assertEquals(BrowserLinkKind.Unsupported, BrowserLinkPolicy.classify("weixin:"))
        assertEquals("https://a_b.example.com", BrowserSslPromptPolicy.connectionLabel("https://a_b.example.com/p|q"))
        assertEquals("https://example.test:8443", BrowserSslPromptPolicy.connectionLabel("https://u:p@example.test:8443/"))
        assertEquals("未知地址", BrowserSslPromptPolicy.connectionLabel("not a url"))
    }
}
