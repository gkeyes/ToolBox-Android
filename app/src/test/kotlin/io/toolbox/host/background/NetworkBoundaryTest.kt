package io.toolbox.host.background

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkBoundaryTest {
    @Test
    fun endpointPolicyAllowsHttpsWithoutDestinationRestrictions() {
        for (url in listOf(
            "https://api.example.com/path",
            "https://other.example.com:9443/path",
            "https://127.0.0.1/path",
            "https://192.168.1.1/path",
            "https://198.18.7.58/path",
            "https://[::1]/path",
            "https://[fc00::1]/path",
        )) {
            assertNull(url, NetworkPolicy.validateEndpoint(url.toHttpUrl()))
        }
    }

    @Test
    fun endpointPolicyStillRequiresHttpsAndExplicitAuthenticationHeaders() {
        assertEquals(
            "HTTPS_REQUIRED",
            NetworkPolicy.validateEndpoint("http://api.example.com/path".toHttpUrl()),
        )
        assertEquals(
            "URL_CREDENTIALS_FORBIDDEN",
            NetworkPolicy.validateEndpoint("https://user:pass@api.example.com/path".toHttpUrl()),
        )
    }
}
