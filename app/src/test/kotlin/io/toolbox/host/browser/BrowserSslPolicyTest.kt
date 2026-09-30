package io.toolbox.host.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSslPolicyTest {
    @Test fun allowsOnlySameHostUntrustedChain() {
        assertTrue(
            BrowserSslPolicy.allowLegacyUntrustedChain(
                pageUrl = "https://www.newsmth.net/nForum/article/AutoWorld/1945318701",
                failureUrl = "https://www.newsmth.net/nForum/article/AutoWorld/1945318701",
                hasUntrusted = true,
                hasIdMismatch = false,
                hasExpired = false,
                hasNotYetValid = false,
                hasDateInvalid = false,
                hasInvalid = false,
            ),
        )
    }

    @Test fun rejectsHostnameMismatchEvenWhenChainIsUntrusted() {
        assertFalse(
            BrowserSslPolicy.allowLegacyUntrustedChain(
                pageUrl = "https://www.newsmth.net/a",
                failureUrl = "https://evil.example/a",
                hasUntrusted = true,
                hasIdMismatch = false,
                hasExpired = false,
                hasNotYetValid = false,
                hasDateInvalid = false,
                hasInvalid = false,
            ),
        )
    }

    @Test fun rejectsExpiredOrIdentityInvalidCertificates() {
        assertFalse(
            BrowserSslPolicy.allowLegacyUntrustedChain(
                pageUrl = "https://example.com/",
                failureUrl = "https://example.com/",
                hasUntrusted = true,
                hasIdMismatch = true,
                hasExpired = false,
                hasNotYetValid = false,
                hasDateInvalid = false,
                hasInvalid = false,
            ),
        )
        assertFalse(
            BrowserSslPolicy.allowLegacyUntrustedChain(
                pageUrl = "https://example.com/",
                failureUrl = "https://example.com/",
                hasUntrusted = true,
                hasIdMismatch = false,
                hasExpired = true,
                hasNotYetValid = false,
                hasDateInvalid = false,
                hasInvalid = false,
            ),
        )
    }

    @Test fun rejectsNonHttpsAndNonUntrustedCases() {
        assertFalse(
            BrowserSslPolicy.allowLegacyUntrustedChain(
                pageUrl = "http://example.com/",
                failureUrl = "http://example.com/",
                hasUntrusted = true,
                hasIdMismatch = false,
                hasExpired = false,
                hasNotYetValid = false,
                hasDateInvalid = false,
                hasInvalid = false,
            ),
        )
        assertFalse(
            BrowserSslPolicy.allowLegacyUntrustedChain(
                pageUrl = "https://example.com/",
                failureUrl = "https://example.com/",
                hasUntrusted = false,
                hasIdMismatch = false,
                hasExpired = false,
                hasNotYetValid = false,
                hasDateInvalid = false,
                hasInvalid = false,
            ),
        )
    }
}
