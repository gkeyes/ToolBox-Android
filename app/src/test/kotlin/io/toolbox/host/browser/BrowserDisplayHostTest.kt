package io.toolbox.host.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserDisplayHostTest {
    /** A small stand-in for the public suffix list. */
    private val suffixes = setOf("com", "net", "uk", "co.uk", "cn", "com.cn", "github.io", "s3.amazonaws.com")
    private val psl: (String) -> String? = { host ->
        val labels = host.split('.')
        // A host that is itself a public suffix has no registrable domain, like OkHttp's topPrivateDomain().
        if (host in suffixes) null else (1 until labels.size).firstNotNullOfOrNull { i ->
            labels.drop(i).joinToString(".").takeIf { it in suffixes }?.let { labels.drop(i - 1).joinToString(".") }
        }
    }
    private fun show(host: String) = browserDisplayHost(host, psl)

    @Test fun showsOnlyTheRegistrableDomain() {
        assertEquals("newsmth.net", show("www.newsmth.net"))
        assertEquals("newsmth.net", show("m.newsmth.net"))
        assertEquals("example.com", show("a.b.example.com"))
        assertEquals("example.com", show("news.example.com"))
        assertEquals("example.com", show("example.com"))
        assertEquals("example.co.uk", show("www.example.co.uk"))
        assertEquals("example.com.cn", show("news.example.com.cn"))
        assertEquals("evil.github.io", show("evil.github.io"))
    }

    @Test fun keepsTheRegistrantOnMultiLabelSuffixes() {
        assertEquals("attacker.s3.amazonaws.com", show("signin.attacker.s3.amazonaws.com"))
        assertEquals("attacker.s3.amazonaws.com", show("attacker.s3.amazonaws.com"))
        assertEquals("attacker.s3.amazonaws.com", show("a.signin.attacker.s3.amazonaws.com"))
    }

    @Test fun doesNotShortenWithoutAnAnswerFromTheList() {
        assertEquals("a.b.c.unknown", browserDisplayHost("a.b.c.unknown") { null })
        assertEquals("a.b.c.unknown", browserDisplayHost("a.b.c.unknown") { error("no list") })
        assertEquals("s3.amazonaws.com", show("s3.amazonaws.com"))
    }

    @Test fun keepsAddressesAndSingleLabelsAsTheyAre() {
        assertEquals("192.168.1.10", show("192.168.1.10"))
        assertEquals("[::1]", show("[::1]"))
        assertEquals("localhost", show("localhost"))
        assertEquals("", show(""))
    }
}
