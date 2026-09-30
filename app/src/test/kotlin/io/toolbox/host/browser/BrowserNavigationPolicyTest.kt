package io.toolbox.host.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserNavigationPolicyTest {
    @Test fun upgradesLegacyNewSmthHttpLinksToHttps() {
        assertEquals(
            "https://www.newsmth.net/nForum/article/FamilyLife/1767729124",
            BrowserNavigationPolicy.normalize(
                "http://www.newsmth.net/nForum/article/FamilyLife/1767729124",
            ),
        )
    }

    @Test fun preservesNewSmthPathQueryAndFragment() {
        assertEquals(
            "https://m.newsmth.net/a/b?p=1#top",
            BrowserNavigationPolicy.normalize("http://m.newsmth.net/a/b?p=1#top"),
        )
    }

    @Test fun doesNotUpgradeUnrelatedHttpSites() {
        assertEquals(
            "http://example.com/path",
            BrowserNavigationPolicy.normalize("http://example.com/path"),
        )
    }
}
