package io.toolbox.host.background

import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolNetworkProxyTest {
    @Test
    fun effectiveRequestBudgetControlsReadWriteAndCallWithoutExtendingConnectWait() {
        val proxy = ToolNetworkProxy()
        for (budget in listOf(1_000L, 30_000L, 300_000L)) {
            val client = proxy.clientForRequest(budget)
            assertEquals(budget.toInt(), client.callTimeoutMillis)
            assertEquals(budget.toInt(), client.readTimeoutMillis)
            assertEquals(budget.toInt(), client.writeTimeoutMillis)
            assertEquals(10_000, client.connectTimeoutMillis)
        }
    }

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

    @Test
    fun declaredHttpsPostAllowsCustomPortHeadersAndHttpErrorResponse() = runTest {
        var captured: Request? = null
        var capturedTimeout = 0L
        val transport = ToolNetworkTransport { request, timeout ->
            captured = request
            capturedTimeout = timeout
            response(request, 401, "Unauthorized", "{\"error\":\"expired\"}")
        }
        val proxy = ToolNetworkProxy(transport, Dns.SYSTEM, 5)

        val result = proxy.request(
            url = "https://api.example.com:8443/v1/quote",
            method = NetworkRequestMethod.POST,
            headers = mapOf(
                "Authorization" to "Bearer test-token",
                "Content-Type" to "application/json",
            ),
            body = "{\"symbol\":\"TEST\"}".toByteArray(),
            bodyIsJson = true,
            allowedHosts = setOf("api.example.com"),
            timeoutMillis = 120_000,
            maxResponseBytes = 4 * 1024 * 1024,
        )

        assertTrue(result is NetworkExecution.Success)
        result as NetworkExecution.Success
        assertEquals(401, result.statusCode)
        assertEquals("{\"error\":\"expired\"}", result.body)
        assertEquals(120_000L, capturedTimeout)
        val capturedRequest = checkNotNull(captured)
        assertEquals(8443, capturedRequest.url.port)
        assertEquals("Bearer test-token", capturedRequest.header("Authorization"))
        assertEquals("POST", capturedRequest.method)
        assertEquals("{\"symbol\":\"TEST\"}", capturedRequest.body?.let(::readBody))
    }

    @Test
    fun legacyHttpGetKeepsTaskRetryClassification() = runTest {
        val transport = ToolNetworkTransport { request, _ ->
            response(request, 503, "Unavailable", "retry")
        }
        val proxy = ToolNetworkProxy(transport, Dns.SYSTEM, 5)

        assertEquals(
            NetworkExecution.RetryableFailure("HTTP_503"),
            proxy.httpGet(
                url = "https://api.example.com/value",
                allowedHosts = setOf("api.example.com"),
            ),
        )
    }

    private fun readBody(body: okhttp3.RequestBody): String = Buffer().use { buffer ->
        body.writeTo(buffer)
        buffer.readUtf8()
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
