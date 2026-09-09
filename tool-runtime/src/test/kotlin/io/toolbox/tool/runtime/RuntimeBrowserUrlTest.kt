package io.toolbox.tool.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeBrowserUrlTest {
    @Test
    fun onlyAbsoluteHttpUrlsWithoutCredentialsOrAmbiguousAuthorityAreAccepted() {
        for (url in listOf(
            "https://example.com/article?x=1#section", "http://example.com:8080/article",
            "HTTPS://example.com/中文", "https://[2001:db8::1]/", "https://127.0.0.1/",
            "https://example.com/" + "a".repeat(2_048 - "https://example.com/".length),
        )) assertEquals(url, validateRuntimeBrowserUrl(url))

        for (url in listOf(
            "", "relative/path", "//example.com/path", "https:example.com", "https:///missing-host",
            "javascript:alert(1)", "intent://example.com/#Intent;scheme=https;end", "file:///tmp/page",
            "content://provider/path", "data:text/html,test", "mailto:user@example.com",
            "https://user:password@example.com/", "https://user@example.com/", "https://@example.com/",
            "https://example.com@evil.invalid/", "https://example.com\\@evil.invalid/",
            "https://example.com:0/", "https://example.com:65536/", "https://example.com:abc/", "https://example.com:/",
            "https://exa%6dple.com/", "https://example.com%40evil.invalid/", "https://example.com/%zz",
            " https://example.com/", "https://example.com/ ", "https://example.com/\nmore",
            "https://example.com/\u0000", "https://example.com/\u0085", "https://example.com/%0aheader",
            "https://example.com/?q=%00", "https://example.com/#%0D", "https://[not-ip]/",
            "https://example.com/" + "a".repeat(2_049 - "https://example.com/".length),
        )) assertThrows("Must reject $url", IllegalArgumentException::class.java) { validateRuntimeBrowserUrl(url) }
    }
}
