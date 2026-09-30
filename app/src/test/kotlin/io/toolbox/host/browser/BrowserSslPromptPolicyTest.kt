package io.toolbox.host.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSslPromptPolicyTest {
    @Test fun promptsOnlyForSameHttpsHost() {
        assertTrue(
            BrowserSslPromptPolicy.shouldOfferPrompt(
                "https://www.newsmth.net/nForum/article/AutoWorld/1945318701",
                "https://www.newsmth.net/nForum/article/AutoWorld/1945318701",
            ),
        )
        assertFalse(
            BrowserSslPromptPolicy.shouldOfferPrompt(
                "https://www.newsmth.net/a",
                "https://cdn.example/a",
            ),
        )
        assertFalse(
            BrowserSslPromptPolicy.shouldOfferPrompt(
                "http://example.com/",
                "http://example.com/",
            ),
        )
    }

    @Test fun describesCertificateProblems() {
        assertEquals(
            "证书与当前网站域名不匹配；证书已经过期；证书链无法被系统完整信任。",
            BrowserSslPromptPolicy.describe(
                hasUntrusted = true,
                hasIdMismatch = true,
                hasExpired = true,
                hasNotYetValid = false,
                hasDateInvalid = false,
                hasInvalid = false,
            ),
        )
    }
}
