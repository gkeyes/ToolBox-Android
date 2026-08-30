package io.toolbox.host.background

import java.net.InetAddress
import java.net.Inet6Address
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkBoundaryTest {
    @Test
    fun privateAndReservedAddressesAreRejected() {
        listOf(
            "127.0.0.1",
            "10.0.0.1",
            "100.64.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.168.1.1",
            "198.18.0.1",
            "203.0.113.1",
            "::1",
            "100::1",
            "64:ff9b:1::1",
            "fc00::1",
            "2001:db8::1",
        ).forEach { address ->
            assertTrue(address, AddressPolicy.isForbidden(InetAddress.getByName(address)))
        }
        assertTrue(AddressPolicy.isForbidden(ipv6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 10, 0, 0, 1)))
        assertTrue(AddressPolicy.isForbidden(ipv6(0, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0, 192, 168, 1, 1)))
        assertFalse(AddressPolicy.isForbidden(ipv6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 93, 184, 216, 34)))
        assertFalse(AddressPolicy.isForbidden(InetAddress.getByName("93.184.216.34")))
    }

    @Test
    fun allowlistMatchesExactHostOrTrueWildcardChildOnly() {
        assertTrue(hostMatches("api.example.com", "api.example.com"))
        assertFalse(hostMatches("other.example.com", "api.example.com"))
        assertTrue(hostMatches("api.example.com", "*.example.com"))
        assertFalse(hostMatches("example.com", "*.example.com"))
        assertFalse(hostMatches("example.com.attacker.test", "*.example.com"))
    }

    @Test
    fun endpointPolicyAllowsDeclaredPublicHttpsPorts() {
        val allowlist = setOf("api.example.com")
        assertNull(NetworkPolicy.validateEndpoint("https://api.example.com/path".toHttpUrl(), allowlist))
        assertNull(NetworkPolicy.validateEndpoint("https://api.example.com:8443/path".toHttpUrl(), allowlist))
        assertNull(
            NetworkPolicy.validateEndpoint(
                "https://other.example.com:9443/path".toHttpUrl(),
                setOf("other.example.com"),
            ),
        )
        assertEquals(
            "HTTPS_REQUIRED",
            NetworkPolicy.validateEndpoint("http://api.example.com/path".toHttpUrl(), allowlist),
        )
        assertEquals(
            "NETWORK_HOST_NOT_ALLOWED",
            NetworkPolicy.validateEndpoint("https://other.example.com/path".toHttpUrl(), allowlist),
        )
        assertEquals(
            "IP_LITERAL_FORBIDDEN",
            NetworkPolicy.validateEndpoint("https://127.0.0.1/path".toHttpUrl(), setOf("127.0.0.1")),
        )
        assertEquals(
            "URL_CREDENTIALS_FORBIDDEN",
            NetworkPolicy.validateEndpoint("https://user:pass@api.example.com/path".toHttpUrl(), allowlist),
        )
    }

    @Test
    fun redirectsAreOptIn() {
        assertEquals("REDIRECTS_DISABLED", NetworkPolicy.redirectError(false))
        assertNull(NetworkPolicy.redirectError(true))
    }

    private fun ipv6(vararg values: Int): Inet6Address =
        Inet6Address.getByAddress(null, values.map(Int::toByte).toByteArray(), -1)
}
