package io.toolbox.host.background

import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolNetworkProxyTest {
    @Test
    fun privateDnsAddressIsRejectedBeforeConnection() = runTest {
        val proxy = ToolNetworkProxy(
            dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) },
        )

        assertEquals(
            NetworkExecution.TerminalFailure("NETWORK_ADDRESS_BLOCKED"),
            proxy.httpGet(
                url = "https://api.example.com/path",
                allowedHosts = setOf("api.example.com"),
            ),
        )
    }

    @Test
    fun redirectSecondHopIsRevalidatedBeforeTransport() = runTest {
        val requests = mutableListOf<String>()
        val transport = ToolNetworkTransport { request, _ ->
            requests += request.url.toString()
            response(
                request = request,
                code = 302,
                message = "Found",
                body = "",
                location = "https://evil.example.com/final",
            )
        }
        val proxy = ToolNetworkProxy(transport, Dns.SYSTEM, 5)

        assertEquals(
            NetworkExecution.TerminalFailure("NETWORK_HOST_NOT_ALLOWED"),
            proxy.httpGet(
                url = "https://api.example.com/start",
                allowedHosts = setOf("api.example.com"),
                allowRedirects = true,
            ),
        )
        assertEquals(listOf("https://api.example.com/start"), requests)
    }

    @Test
    fun responseOverLimitReturnsResultTooLarge() = runTest {
        val transport = ToolNetworkTransport { request, _ ->
            response(
                request = request,
                code = 200,
                message = "OK",
                body = "x".repeat(MAX_RESULT_BYTES + 1),
            )
        }
        val proxy = ToolNetworkProxy(transport, Dns.SYSTEM, 5)

        assertEquals(
            NetworkExecution.TerminalFailure("RESULT_TOO_LARGE"),
            proxy.httpGet(
                url = "https://api.example.com/result",
                allowedHosts = setOf("api.example.com"),
            ),
        )
    }

    private fun response(
        request: Request,
        code: Int,
        message: String,
        body: String,
        location: String? = null,
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(message)
        .apply { if (location != null) header("Location", location) }
        .body(body.toResponseBody("text/plain".toMediaType()))
        .build()
}
