package io.toolbox.host.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserLinkPolicyTest {
    @Test fun letsWebViewHandleOrdinaryAndGeneratedDocuments() {
        for (url in listOf("http://www.newsmth.net/path", "https://example.com", "about:blank", "blob:https://example.com/id", "data:text/html,hello")) {
            assertEquals(url, BrowserLinkKind.Web, BrowserLinkPolicy.classify(url))
        }
    }

    @Test fun offersExternalAppsForTheirOwnProtocols() {
        for (url in listOf("mailto:test@example.com", "tel:12345", "weixin://test", "intent://example/#Intent;scheme=https;end")) {
            assertEquals(url, BrowserLinkKind.External, BrowserLinkPolicy.classify(url))
        }
    }

    @Test fun doesNotDelegateLocalFilesOrExecutableLinksToExternalApps() {
        for (url in listOf("file:///private/file", "content://io.toolbox.host.fileprovider/private", "javascript:alert(1)", "https://user@example.com", "https://example.com/%0a")) {
            assertEquals(url, BrowserLinkKind.Unsupported, BrowserLinkPolicy.classify(url))
        }
    }
}
