package io.toolbox.host.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserDisplayHostTest {
    @Test fun shortensFromTheLeftButKeepsOneLabelBeforeTheDomain() {
        assertEquals("newsmth.net", browserDisplayHost("www.newsmth.net"))
        assertEquals("newsmth.net", browserDisplayHost("m.newsmth.net"))
        assertEquals("…b.example.com", browserDisplayHost("a.b.example.com"))
        assertEquals("example.com", browserDisplayHost("example.com"))
        assertEquals("example.co.uk", browserDisplayHost("www.example.co.uk"))
        assertEquals("news.example.com.cn", browserDisplayHost("news.example.com.cn"))
        assertEquals("evil.github.io", browserDisplayHost("evil.github.io"))
    }

    @Test fun keepsAddressesAndSingleLabelsAsTheyAre() {
        assertEquals("192.168.1.10", browserDisplayHost("192.168.1.10"))
        assertEquals("[::1]", browserDisplayHost("[::1]"))
        assertEquals("localhost", browserDisplayHost("localhost"))
        assertEquals("", browserDisplayHost(""))
    }
}
