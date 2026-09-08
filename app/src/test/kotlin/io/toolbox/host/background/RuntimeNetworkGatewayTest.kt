package io.toolbox.host.background

import io.toolbox.tool.packagekit.InstalledManifestNetwork
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeNetworkMethod
import io.toolbox.tool.runtime.RuntimeNetworkRequest
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeNetworkGatewayTest {
    @Test
    fun streamOpensWithoutReadingAndReturnsIncrementalBytesBeyondTheBridgeTotal() = runTest {
        val reads = AtomicInteger()
        val bytes = "你abc".repeat(2_000).toByteArray()
        val source = object : Source {
            val data = Buffer().write(bytes)
            override fun read(sink: Buffer, byteCount: Long): Long { reads.incrementAndGet(); return data.read(sink, minOf(1_024, byteCount)) }
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }
        val gateway = RuntimeNetworkGateway(
            ToolNetworkProxy(ToolNetworkTransport { request, _ -> response(request, sourceBody(source)) }),
            InstalledManifestNetwork(setOf("api.github.com"), false, 32_768, 30_000), 4_096,
        )
        try {
            val opened = gateway.openStream(streamId(1), request(32_768))
            assertEquals(200, opened.status)
            assertEquals(0, reads.get())
            val result = Buffer()
            while (true) {
                val chunk = gateway.readStream(opened.streamId, 2_048)
                assertTrue(chunk.data.size <= 2_048)
                result.write(chunk.data)
                assertEquals(result.size, chunk.receivedBytes)
                if (chunk.done) break
            }
            org.junit.Assert.assertArrayEquals(bytes, result.readByteArray())
            gateway.cancelStream(opened.streamId)
            assertEquals(RuntimeRpcErrorCode.NOT_FOUND, failure { gateway.readStream(opened.streamId, 2_048) }.errorCode)
        } finally { gateway.close() }
    }

    @Test
    fun streamLimitsAreCumulativeAndTerminalReadsReleaseTheirSlot() = runTest {
        val gateway = gateway(4_096, ToolNetworkTransport { request, _ -> response(request, "x".repeat(4_097).toResponseBody()) })
        try {
            val first = gateway.openStream(streamId(1), request(4_096))
            gateway.openStream(streamId(2), request(4_096))
            assertEquals(RuntimeRpcErrorCode.QUOTA_EXCEEDED, failure { gateway.openStream(streamId(3), request(4_096)) }.errorCode)
            gateway.readStream(first.streamId, 2_048)
            gateway.readStream(first.streamId, 2_048)
            assertEquals(RuntimeRpcErrorCode.QUOTA_EXCEEDED, failure { gateway.readStream(first.streamId, 2_048) }.errorCode)
            assertEquals(RuntimeRpcErrorCode.NOT_FOUND, failure { gateway.readStream(first.streamId, 2_048) }.errorCode)
            assertEquals(200, gateway.openStream(streamId(3), request(4_096)).status)
        } finally { gateway.close() }
    }

    @Test
    fun cancellationAndSessionCloseInterruptBlockedBodyReads() = runBlocking {
        for (closeSession in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val source = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    entered.countDown()
                    check(closed.await(5, TimeUnit.SECONDS))
                    throw IOException("not-for-logs")
                }
                override fun timeout() = Timeout.NONE
                override fun close() { closed.countDown() }
            }
            val gateway = gateway(4_096, ToolNetworkTransport { request, _ -> response(request, sourceBody(source)) })
            try {
                gateway.openStream(streamId(1), request(4_096))
                val reading = async(Dispatchers.IO) { failure { gateway.readStream(streamId(1), 1_024) } }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                if (closeSession) gateway.close() else gateway.cancelStream(streamId(1))
                val error = withTimeout(5_000) { reading.await() }
                assertEquals(RuntimeRpcErrorCode.CANCELLED, error.errorCode)
                assertFalse(error.message.contains("not-for-logs"))
            } finally { gateway.close() }
        }
    }

    @Test
    fun cancellationBeforeOpenAndClosingDuringHeadersNeverLeakAResponse() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        var calls = 0
        val gateway = gateway(4_096, ToolNetworkTransport { request, _ ->
            calls += 1
            entered.complete(Unit)
            release.await()
            response(request, sourceBody(object : Source {
                override fun read(sink: Buffer, byteCount: Long) = -1L
                override fun timeout() = Timeout.NONE
                override fun close() { closed.complete(Unit) }
            }))
        })
        gateway.cancelStream(streamId(1))
        assertEquals(RuntimeRpcErrorCode.CANCELLED, failure { gateway.openStream(streamId(1), request(4_096)) }.errorCode)
        assertEquals(0, calls)
        val opening = async { failure { gateway.openStream(streamId(2), request(4_096)) } }
        entered.await()
        gateway.close()
        release.complete(Unit)
        assertEquals(RuntimeRpcErrorCode.CANCELLED, opening.await().errorCode)
        closed.await()
    }

    @Test
    fun ordinaryEofAndFinallyCancellationDoNotExhaustTheEarlyCancellationBudget() = runTest {
        val gateway = gateway(4_096, ToolNetworkTransport { request, _ -> response(request, "".toResponseBody()) })
        try {
            repeat(260) { number ->
                val id = gateway.openStream(streamId(number), request(4_096)).streamId
                assertTrue(gateway.readStream(id, 1_024).done)
                gateway.cancelStream(id)
                gateway.cancelStream(id)
            }
        } finally { gateway.close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun streamDeadlineReleasesIdleSlotsAndSessionRegistriesAreIsolated() = runTest {
        val first = RuntimeNetworkStreams(backgroundScope)
        val second = RuntimeNetworkStreams()
        try {
            val firstControl = first.reserve(streamId(1), 1_000)
            first.reserve(streamId(2), 1_000)
            second.cancel(streamId(1))
            firstControl.requireActive()
            runCurrent()
            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(RuntimeRpcErrorCode.NOT_FOUND, failure { first.get(streamId(1)) }.errorCode)
            assertEquals("NETWORK_TIMEOUT", org.junit.Assert.assertThrows(ToolNetworkFailure::class.java) { firstControl.requireActive() }.code)
            first.reserve(streamId(3), 1_000)
            first.reserve(streamId(4), 1_000)
        } finally { first.close(); second.close() }
    }

    @Test
    fun streamingRetainsEndpointTimeoutAndHeaderBoundaries() = runTest {
        var calls = 0
        val gateway = gateway(4_096, ToolNetworkTransport { request, timeout ->
            calls += 1
            assertEquals(1_000L, timeout)
            assertEquals("test-token", request.header("X-API-Key"))
            response(request, "unauthorized".toResponseBody()).newBuilder().code(401).header("Set-Cookie", "private").build()
        })
        try {
            for ((index, url) in listOf("http://api.github.com/", "https://user:pass@api.github.com/").withIndex()) {
                assertEquals(RuntimeRpcErrorCode.NETWORK_BLOCKED,
                    failure { gateway.openStream(streamId(index), request(4_096).copy(url = url)) }.errorCode)
            }
            assertEquals(0, calls)
            val opened = gateway.openStream(streamId(9), request(4_096).copy(timeoutMillis = 1_000, headers = mapOf("X-API-Key" to "test-token")))
            assertEquals(401, opened.status)
            assertFalse(opened.headers.keys.any { it.equals("Set-Cookie", true) })
        } finally { gateway.close() }
    }

    @Test
    fun longWaitUsesTheSmallerRequestAndManifestBudgetAndKeepsTheDefault() = runTest {
        for ((declared, requested, expected) in listOf(
            Triple(3_600_000, 3_600_000L, 3_600_000L),
            Triple(900_000, 3_600_000L, 900_000L),
            Triple(300_000, 300_000L, 300_000L),
            Triple(90_000, 300_000L, 90_000L),
            Triple(300_000, 1_000L, 1_000L),
            Triple(300_000, null, 30_000L),
        )) {
            var actualTimeout = 0L
            val gateway = RuntimeNetworkGateway(
                proxy = ToolNetworkProxy(ToolNetworkTransport { request, timeout ->
                    actualTimeout = timeout
                    response(request, "{}".toResponseBody("application/json".toMediaType()))
                }),
                policy = InstalledManifestNetwork(setOf("api.github.com"), false, 4_096, declared),
                bridgePayloadBytes = 4_096,
            )
            try {
                val options = request(4_096).copy(timeoutMillis = requested)
                assertEquals(200, gateway.request(options).status)
                assertEquals(expected, actualTimeout)
                actualTimeout = 0L
                assertEquals(200, gateway.openStream(streamId(1), options).status)
                assertEquals(expected, actualTimeout)
                gateway.cancelStream(streamId(1))
            } finally {
                gateway.close()
            }
        }
    }

    @Test
    fun jsonBetweenTheOldBase64BudgetAndTheDeclaredLimitIsReturnedIntact() = runTest {
        for (size in listOf(800_000, 1_500_000)) {
            val body = "{\"data\":\"${"x".repeat(size)}\"}"
            val limit = if (size < 1_000_000) 1_048_576 else 4_194_304
            val gateway = gateway(limit, ToolNetworkTransport { request, _ ->
                response(request, body.toResponseBody("application/json".toMediaType()))
            })

            val actual = gateway.request(request(limit))

            assertEquals(200, actual.status)
            assertEquals(body, actual.body)
        }
    }

    @Test
    fun responseQuotaAndInvalidHttpsEndpointHaveDifferentErrors() = runTest {
        var connections = 0
        val gateway = gateway(4_096, ToolNetworkTransport { request, _ ->
            connections += 1
            response(request, "x".repeat(4_097).toResponseBody())
        })
        val quota = failure { gateway.request(request(4_096)) }
        assertEquals(RuntimeRpcErrorCode.QUOTA_EXCEEDED, quota.errorCode)
        assertTrue(quota.message.contains("4096"))

        val blocked = failure {
            gateway.request(request(4_096).copy(url = "http://api.github.com/"))
        }
        assertEquals(RuntimeRpcErrorCode.NETWORK_BLOCKED, blocked.errorCode)
        assertTrue(blocked.message.contains("HTTPS"))
        assertEquals(1, connections)
    }

    @Test
    fun connectionAndBodyReadFailuresKeepTypedErrorsWithoutTransportDetails() = runTest {
        for (duringBody in listOf(false, true)) {
            for (timeout in listOf(false, true)) {
                val error = if (timeout) SocketTimeoutException("secret-in-transport-error")
                    else IOException("secret-in-transport-error")
                val gateway = gateway(4_096, ToolNetworkTransport { request, _ ->
                    if (!duringBody) throw error
                    response(request, failingBody(error))
                })

                val actual = failure { gateway.request(request(4_096)) }

                assertEquals(
                    if (timeout) RuntimeRpcErrorCode.NETWORK_TIMEOUT else RuntimeRpcErrorCode.NETWORK_UNAVAILABLE,
                    actual.errorCode,
                )
                assertFalse(actual.message.contains("secret-in-transport-error"))
            }
        }
    }

    @Test
    fun networkPermissionRevocationStopsRequestsAndActiveStreams() = runTest {
        var granted = true
        var connections = 0
        val gateway = RuntimeNetworkGateway(
            ToolNetworkProxy(ToolNetworkTransport { request, _ -> connections++; response(request, "data: hi\n\n".toResponseBody()) }),
            null, 4_096,
            validateNetworkAccess = {
                if (!granted) throw RuntimeHandlerException(RuntimeRpcErrorCode.PERMISSION_DENIED, "网络权限已关闭。")
            },
            toolId = "gateway-permission-test",
        )
        try {
            assertEquals(200, gateway.request(request(4_096)).status)
            val stream = gateway.openStream(streamId(77), request(4_096))
            assertEquals(200, stream.status)
            granted = false
            io.toolbox.host.runtime.NetworkDomainInvalidation.cancel("gateway-permission-test")
            assertEquals(RuntimeRpcErrorCode.PERMISSION_DENIED, failure { gateway.readStream(stream.streamId, 256) }.errorCode)
            assertEquals(RuntimeRpcErrorCode.PERMISSION_DENIED, failure { gateway.request(request(4_096)) }.errorCode)
            assertEquals(RuntimeRpcErrorCode.PERMISSION_DENIED, failure { gateway.openStream(streamId(78), request(4_096)) }.errorCode)
            granted = true
            assertEquals(RuntimeRpcErrorCode.NOT_FOUND, failure { gateway.readStream(stream.streamId, 256) }.errorCode)
            assertEquals(2, connections)
        } finally { gateway.close() }
    }

    @Test
    fun requestsAndStreamsAllowUnlistedAndIpHostsWithOrWithoutLegacyPolicy() = runTest {
        for (policy in listOf(null, InstalledManifestNetwork(setOf("example.com"), false, 4_096, 30_000))) {
            var connections = 0
            val gateway = RuntimeNetworkGateway(
                ToolNetworkProxy(ToolNetworkTransport { request, timeout ->
                    connections += 1
                    assertEquals(30_000L, timeout)
                    response(request, "ok".toResponseBody())
                }),
                policy, 4_096,
            )
            try {
                for ((index, url) in listOf(
                    "https://unknown.example.com/", "https://127.0.0.1/", "https://198.18.7.58/", "https://192.168.1.1/", "https://[::1]/",
                ).withIndex()) {
                    val options = request(4_096).copy(url = url)
                    assertEquals(200, gateway.request(options).status)
                    val opened = gateway.openStream(streamId(index), options)
                    assertEquals(200, opened.status)
                    gateway.cancelStream(opened.streamId)
                }
                assertEquals(10, connections)
            } finally { gateway.close() }
        }
    }

    @Test
    fun streamsFollowHttpsRedirectsWithoutDomainApprovalAndKeepCredentialBoundary() = runTest {
        val requests = mutableListOf<Request>()
        val gateway = gateway(4_096, ToolNetworkTransport { request, _ ->
            requests += request
            response(request, "ok".toResponseBody()).newBuilder().apply {
                if (requests.size == 1) code(307).header("Location", "https://other.example.com/stream")
            }.build()
        })
        try {
            val opened = gateway.openStream(streamId(1), request(4_096).copy(headers = mapOf("Authorization" to "Bearer test-token")))
            assertEquals(200, opened.status)
            assertEquals("https://other.example.com/stream", opened.headers["x-toolbox-final-url"])
            assertEquals("Bearer test-token", requests.first().header("Authorization"))
            org.junit.Assert.assertNull(requests.last().header("Authorization"))
            assertEquals(2, requests.size)
        } finally { gateway.close() }
    }

    @Test
    fun streamingRedirectsStillRejectHttpDowngradesAndBoundLoops() = runTest {
        for ((destination, expectedRequests) in listOf(
            "http://api.github.com/final" to 1,
            "https://api.github.com/loop" to 6,
        )) {
            var connections = 0
            val gateway = gateway(4_096, ToolNetworkTransport { request, _ ->
                connections += 1
                response(request, "".toResponseBody()).newBuilder().code(302).header("Location", destination).build()
            })
            try {
                assertEquals(RuntimeRpcErrorCode.NETWORK_BLOCKED, failure { gateway.openStream(streamId(1), request(4_096)) }.errorCode)
                assertEquals(expectedRequests, connections)
                assertEquals(RuntimeRpcErrorCode.NOT_FOUND, failure { gateway.readStream(streamId(1), 256) }.errorCode)
            } finally { gateway.close() }
        }
    }

    private fun gateway(limit: Int, transport: ToolNetworkTransport) = RuntimeNetworkGateway(
        proxy = ToolNetworkProxy(transport),
        policy = InstalledManifestNetwork(setOf("api.github.com"), false, limit, 30_000),
        bridgePayloadBytes = limit,
    )

    private fun request(limit: Int) = RuntimeNetworkRequest(
        url = "https://api.github.com/repos/example/repo/actions/runs?per_page=100",
        method = RuntimeNetworkMethod.GET,
        maxResponseBytes = limit,
    )

    private fun response(request: Request, body: ResponseBody) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()

    private fun failingBody(error: IOException) = object : ResponseBody() {
        private val failingSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw error
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        override fun contentLength(): Long = -1L
        override fun contentType() = "application/json".toMediaType()
        override fun source() = failingSource
    }

    private fun streamId(number: Int) = "stream-" + number.toString(16).padStart(32, '0')

    private fun sourceBody(source: Source) = object : ResponseBody() {
        private val buffered = source.buffer()
        override fun contentLength() = -1L
        override fun contentType() = "text/event-stream".toMediaType()
        override fun source() = buffered
    }

    private suspend fun failure(action: suspend () -> Unit): RuntimeHandlerException {
        try {
            action()
        } catch (error: RuntimeHandlerException) {
            return error
        }
        error("Expected a typed network failure")
    }
}
