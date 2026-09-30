package io.toolbox.host.browser

import io.toolbox.tool.runtime.validateRuntimeBrowserUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BrowserUrlValidationTest {
    @Test fun preservesOriginalSchemeHostAndRawEncoding() {
        for (url in listOf(
            "http://www.newsmth.net/nForum/article/Memory/187055",
            "https://www.newsmth.net/nForum/article/Memory/187055",
            "http://m.newsmth.net/a/%E4%B8%AD%E6%96%87?p=%2Fraw#top",
            "https://www.mysmth.net/path",
            "http://example.com:8080/legacy",
        )) {
            assertEquals(url, validateRuntimeBrowserUrl(url))
        }
    }

    @Test fun stillRejectsUnsupportedSchemesAndAmbiguousAuthorities() {
        for (url in listOf(
            "javascript:alert(1)",
            "file:///etc/hosts",
            "https://user@www.newsmth.net/path",
            "https://www.newsmth.net:bad/path",
            "https://www.newsmth.net/%0a",
        )) {
            assertThrows(IllegalArgumentException::class.java) { validateRuntimeBrowserUrl(url) }
        }
    }
}
