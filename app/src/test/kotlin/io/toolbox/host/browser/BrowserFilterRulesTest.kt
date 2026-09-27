package io.toolbox.host.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserFilterRulesTest {
    @Test fun defaultBlockingRespectsDomainBoundariesAndNeverBlocksNavigation() {
        val state = BrowserFilterSnapshot()
        assertTrue(state.blocks("news.example", "https://ad.doubleclick.net/banner", false))
        assertFalse(state.blocks("news.example", "https://notdoubleclick.net/banner", false))
        assertFalse(state.blocks("news.example", "https://doubleclick.net.evil.example/banner", false))
        assertFalse(state.blocks("news.example", "https://doubleclick.net/", true))
        assertFalse(state.blocks("news.example", "data:text/plain,doubleclick.net", false))
    }

    @Test fun exceptionsAndGlobalSwitchDisableBothKindsOfFiltering() {
        val rule = BrowserFilterRule(site = "news.example", value = ".banner")
        for (state in listOf(
            BrowserFilterSnapshot(enabled = false, rules = listOf(rule)),
            BrowserFilterSnapshot(exceptions = setOf("news.example"), rules = listOf(rule)),
        )) {
            assertTrue(state.selectors("news.example").isEmpty())
            assertFalse(state.blocks("news.example", "https://doubleclick.net/banner", false))
        }
    }

    @Test fun customNetworkRuleIsScopedToExactWebsiteButIncludesResourceSubdomains() {
        val rule = BrowserFilterRule(site = "news.example", value = "ads.example", kind = BrowserFilterKind.NetworkHost)
        val state = BrowserFilterSnapshot(builtIn = false, rules = listOf(rule))
        assertTrue(state.blocks("news.example", "https://cdn.ads.example/banner", false))
        assertFalse(state.blocks("other.example", "https://cdn.ads.example/banner", false))
        assertFalse(state.blocks("sub.news.example", "https://cdn.ads.example/banner", false))
        assertFalse(state.blocks("news.example", "https://notads.example/banner", false))
        assertFalse(state.copy(rules = listOf(rule.copy(enabled = false))).blocks("news.example", "https://ads.example/", false))
    }

    @Test fun cosmeticRulesAreScopedAndDisablingDefaultListKeepsCustomRules() {
        val rule = BrowserFilterRule(site = "news.example", value = ".banner")
        val state = BrowserFilterSnapshot(builtIn = false, rules = listOf(rule, rule.copy(id = "off", value = ".off", enabled = false)))
        assertEquals(listOf(".banner"), state.selectors("news.example"))
        assertTrue(state.selectors("other.example").isEmpty())
        assertFalse(state.blocks("news.example", "https://doubleclick.net/", false))
    }

    @Test fun malformedOrInjectedInputsAreRejected() {
        for (host in listOf("https://news.example", "*.example", "news.example/path", "example:80", "user@example.com", "")) {
            assertNull(host, BrowserFilterValidation.host(host))
        }
        for (selector in listOf("body", "html", "*", ":root", ".ad, body", ".ad{display:none}", ".ad;foo", "@import 'x'", ".ad/*x*/", ".ad\nbody")) {
            assertFalse(selector, BrowserFilterValidation.selectorAllowed(selector))
        }
        assertTrue(BrowserFilterValidation.selectorAllowed("article > .banner:nth-of-type(2)"))
        assertTrue(BrowserFilterValidation.selectorAllowed("[data-kind=\"ad\"]"))
        assertFalse(BrowserFilterValidation.selectorAllowed("a".repeat(1025)))
    }

    @Test fun hostsNormalizeCaseIdnAndTrailingDot() {
        assertEquals("news.example", BrowserFilterValidation.host(" NEWS.Example. "))
        assertEquals("xn--fiqs8s.example", BrowserFilterValidation.host("中国.example"))
        assertEquals("news.example", BrowserFilterValidation.urlHost("https://NEWS.Example.:8443/a"))
        assertNull(BrowserFilterValidation.urlHost("file:///news.example"))
    }
}
