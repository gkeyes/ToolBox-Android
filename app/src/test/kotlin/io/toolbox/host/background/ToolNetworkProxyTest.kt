package io.toolbox.host.background

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Source
import okio.Timeout
import okio.buffer
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
    fun streamCancellationCancelsTheUnderlyingCallEvenBeforeHeaders() {
        for (cancelBeforeAttach in listOf(false, true)) {
            val control = ToolNetworkStreamControl()
            val call = ToolNetworkProxy().clientForRequest(1_000)
                .newCall(Request.Builder().url("https://api.example.com/").build())
            if (cancelBeforeAttach) {
                control.cancel()
                org.junit.Assert.assertThrows(ToolNetworkFailure::class.java) { control.attach(call) }
            } else {
                control.attach(call)
                control.cancel()
            }
            assertTrue(call.isCanceled())
        }
    }

    @Test
    fun cancellationAfterHeadersCancelsBodyReadAndClosesResponse() = runBlocking {
        val reading = CountDownLatch(1)
        val cancelledRead = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val request = Request.Builder().url("https://api.example.com/slow").build()
        val body = object : ResponseBody() {
            private val stream = object : Source {
                override fun timeout() = Timeout.NONE
                override fun read(sink: Buffer, byteCount: Long): Long {
                    reading.countDown()
                    check(cancelledRead.await(5, TimeUnit.SECONDS))
                    throw IOException("Call cancelled while reading")
                }
                override fun close() { closed.countDown() }
            }.buffer()
            override fun contentType(): MediaType = "text/plain".toMediaType()
            override fun contentLength(): Long = -1L
            override fun source() = stream
        }
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(body).build()
        // Delegate metadata (including OkHttp's tag API); all execution stays in this fake.
        val call = object : Call by OkHttpClient().newCall(request) {
            override fun request() = request
            override fun timeout() = Timeout.NONE
            override fun isExecuted() = true
            override fun isCanceled() = cancelled.get()
            override fun clone(): Call = error("not used")
            override fun execute(): Response = error("Must not block the calling dispatcher")
            override fun cancel() { cancelled.set(true); cancelledRead.countDown() }
            override fun enqueue(responseCallback: Callback) {
                thread(isDaemon = true, name = "test-network-callback") {
                    responseCallback.onResponse(this, response)
                }
            }
        }
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { call.awaitResponse(1024, true) }
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            pending.cancelAndJoin()
            assertTrue(cancelled.get())
            assertTrue(closed.await(5, TimeUnit.SECONDS))
        } finally {
            call.cancel()
            pending.cancelAndJoin()
        }
    }

    @Test
    fun effectiveRequestBudgetControlsReadWriteAndCallWithoutExtendingConnectWait() {
        val proxy = ToolNetworkProxy()
        for (budget in listOf(1_000L, 30_000L, 300_000L, 3_600_000L)) {
            val client = proxy.clientForRequest(budget)
            assertEquals(budget.toInt(), client.callTimeoutMillis)
            assertEquals(budget.toInt(), client.readTimeoutMillis)
            assertEquals(budget.toInt(), client.writeTimeoutMillis)
            assertEquals(10_000, client.connectTimeoutMillis)
        }
    }

    @Test
    fun systemDnsResultsIncludingPrivateAndFakeIpAddressesReachTheTransportUnfiltered() {
        val addresses = listOf("127.0.0.1", "192.168.1.1", "198.18.7.58", "::1", "fc00::1")
            .map(InetAddress::getByName)
        val proxy = ToolNetworkProxy(dns = Dns { addresses })
        assertEquals(addresses, proxy.clientForRequest(1_000).dns.lookup("miniflux.example.com"))
    }

    @Test
    fun redirectsIgnoreLegacyAllowlistAndSwitchButStripCrossOriginCredentials() = runTest {
        val requests = mutableListOf<Request>()
        val proxy = ToolNetworkProxy(ToolNetworkTransport { request, _ ->
            requests += request
            if (requests.size == 1) {
                response(request, 302, "Found", "", location = "https://other.example.com/final")
            } else {
                response(request, 200, "OK", "connected")
            }
        })
        val result = proxy.request(
            url = "https://api.example.com/start",
            method = NetworkRequestMethod.GET,
            headers = mapOf("Authorization" to "Bearer test-token", "Cookie" to "session=test", "X-API-Key" to "test-key"),
            allowedHosts = emptySet(),
            allowRedirects = false,
        )
        assertTrue(result is NetworkExecution.Success)
        assertEquals("https://other.example.com/final", (result as NetworkExecution.Success).finalUrl)
        assertEquals(2, requests.size)
        assertEquals("Bearer test-token", requests.first().header("Authorization"))
        for (header in listOf("Authorization", "Cookie", "X-API-Key")) {
            org.junit.Assert.assertNull(requests.last().header(header))
        }
    }

    @Test
    fun redirectsStillRejectHttpDowngradesAndBoundLoops() = runTest {
        for ((destination, expected, expectedRequests) in listOf(
            Triple("http://api.example.com/final", "HTTPS_REQUIRED", 1),
            Triple("https://api.example.com/loop", "TOO_MANY_REDIRECTS", 6),
        )) {
            var requests = 0
            val proxy = ToolNetworkProxy(ToolNetworkTransport { request, _ ->
                requests += 1
                response(request, 302, "Found", "", location = destination)
            })
            assertEquals(
                NetworkExecution.TerminalFailure(expected),
                proxy.httpGet("https://api.example.com/start"),
            )
            assertEquals(expectedRequests, requests)
        }
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
    fun declaredHttpsPostAllowsCustomPortHeadersHttpErrorResponseAndMaximumTimeout() = runTest {
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
            timeoutMillis = 3_600_000,
            maxResponseBytes = 4 * 1024 * 1024,
        )

        assertTrue(result is NetworkExecution.Success)
        result as NetworkExecution.Success
        assertEquals(401, result.statusCode)
        assertEquals("{\"error\":\"expired\"}", result.body)
        assertEquals(3_600_000L, capturedTimeout)
        val capturedRequest = checkNotNull(captured)
        assertEquals(8443, capturedRequest.url.port)
        assertEquals("Bearer test-token", capturedRequest.header("Authorization"))
        assertEquals("POST", capturedRequest.method)
        assertEquals("{\"symbol\":\"TEST\"}", capturedRequest.body?.let(::readBody))
        assertEquals(
            NetworkExecution.TerminalFailure("INVALID_TIMEOUT"),
            proxy.request(
                url = "https://api.example.com/v1/quote",
                method = NetworkRequestMethod.GET,
                allowedHosts = setOf("api.example.com"),
                timeoutMillis = 3_600_001,
            ),
        )
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
