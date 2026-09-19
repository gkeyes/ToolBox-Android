package io.toolbox.host.background

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ToolNetworkTransportTest {
    @Test
    fun liveSystemProxyConnectDirectTlsAnd407RemainDistinct() = runBlocking {
        val original = ProxySelector.getDefault()
        try {
            ControlledNetworkFixture().use { fixture ->
                val connects = CopyOnWriteArrayList<String>()
                val port = fixture.connectProxy(connects)
                val endpoint = fixture.https(persistent = true) { _, socket -> fixture.respond(socket, close = false) }
                val proxy = fixture.proxy()
                ProxySelector.setDefault(selector(Proxy.NO_PROXY))
                assertTrue(proxy.httpGet(endpoint) is NetworkExecution.Success)
                assertTrue(connects.isEmpty())
                ProxySelector.setDefault(selector(Proxy(Proxy.Type.HTTP, InetSocketAddress("localhost", port))))
                assertTrue(proxy.httpGet(endpoint) is NetworkExecution.Success)
                assertEquals(1, connects.size)
                // Default trust must reject the fixture's self-signed leaf even over CONNECT.
                assertTrue(ToolNetworkProxy().httpGet(endpoint, timeoutMillis = 5_000) is NetworkExecution.RetryableFailure)
                val protectedPort = fixture.connectProxy(CopyOnWriteArrayList(), authenticate = true)
                ProxySelector.setDefault(selector(Proxy(Proxy.Type.HTTP, InetSocketAddress("localhost", protectedPort))))
                assertEquals(NetworkExecution.TerminalFailure("PROXY_AUTHENTICATION_REQUIRED"), proxy.httpGet(endpoint))
                assertTrue(fixture.failures.isEmpty())
            }
        } finally { ProxySelector.setDefault(original) }
    }

    @Test
    fun redirectsKeepRangeAndValidatorsWithoutRestoringSecretsOnReturn() = runBlocking {
        ControlledNetworkFixture().use { fixture ->
            val observed = CopyOnWriteArrayList<ControlledNetworkFixture.Request>()
            lateinit var first: String
            lateinit var second: String
            first = fixture.https { request, socket ->
                observed += request
                if (request.target == "/start") fixture.respond(socket, 307, mapOf("Location" to "$second/cdn"))
                else fixture.respond(socket, 206, body = "partial")
            }
            second = fixture.https { request, socket ->
                observed += request
                fixture.respond(socket, 307, mapOf("Location" to "$first/end"))
            }
            val result = fixture.proxy().request("$first/start", NetworkRequestMethod.GET, headers = mapOf(
                "Range" to "bytes=5-11", "If-None-Match" to "fixture-etag", "If-Range" to "fixture-if-range",
                "Accept" to "text/plain", "Cache-Control" to "no-cache", "Authorization" to "Bearer test",
                "Cookie" to "test=1", "X-API-Key" to "test-key", "X-Custom" to "test-value",
            ))
            assertEquals("partial", (result as NetworkExecution.Success).body)
            assertEquals(3, observed.size)
            assertEquals("Bearer test", observed[0].headers["authorization"])
            observed.drop(1).forEach { request ->
                assertEquals("bytes=5-11", request.headers["range"])
                assertEquals("fixture-etag", request.headers["if-none-match"])
                assertEquals("fixture-if-range", request.headers["if-range"])
                assertEquals("text/plain", request.headers["accept"])
                assertEquals("no-cache", request.headers["cache-control"])
                listOf("authorization", "cookie", "x-api-key", "x-custom").forEach { assertNull(request.headers[it]) }
            }
        }
    }

    @Test
    fun methodRewriteDropsBodyHeadersAndHopByHopHeaders() = runBlocking {
        ControlledNetworkFixture().use { fixture ->
            val observed = CopyOnWriteArrayList<ControlledNetworkFixture.Request>()
            val endpoint = fixture.https { request, socket ->
                observed += request
                if (request.target == "/start") fixture.respond(socket, 303, mapOf("Location" to "/end"))
                else fixture.respond(socket)
            }
            val result = fixture.proxy().request("$endpoint/start", NetworkRequestMethod.POST,
                headers = mapOf("Content-Type" to "text/plain", "Content-Language" to "en", "Connection" to "X-Hop", "X-Hop" to "hidden"),
                body = "payload".toByteArray())
            assertTrue(result is NetworkExecution.Success)
            assertEquals(2, observed.size)
            assertNull(observed[0].headers["x-hop"])
            listOf("content-type", "content-language", "content-length", "x-hop").forEach { assertNull(observed[1].headers[it]) }
        }
    }

    @Test
    fun moreThan64SameHostBodiesCanWaitAndCancellationAfterHeadersInterruptsAllReads() = runBlocking {
        ControlledNetworkFixture().use { fixture ->
            val count = 70
            val headers = CountDownLatch(count)
            val bodiesMayFinish = CountDownLatch(1)
            val resources = NetworkResources(availableHeap = { 512L * 1024 * 1024 })
            val endpoint = fixture.https { _, socket ->
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                headers.countDown()
                bodiesMayFinish.await(30, TimeUnit.SECONDS)
                socket.getOutputStream().write("ok".toByteArray())
            }
            val proxy = fixture.proxy(resources)
            val started = System.nanoTime()
            val jobs = (0 until count).map { async { proxy.httpGet(endpoint, resourceOwner = "owner-${it % 3}") } }
            try {
                withTimeout(20_000) { while (headers.count > 0) delay(10) }
                val firstByteMillis = (System.nanoTime() - started) / 1_000_000
                assertTrue(resources.reservedBytes >= count * NetworkResources.OPERATION_BYTES)
                // Header callbacks have returned although each request is blocked waiting for body bytes.
                withTimeout(5_000) { while (proxy.clientForRequest(0).dispatcher.runningCallsCount() != 0) delay(10) }
                withTimeout(5_000) { jobs.forEach { it.cancel() }; jobs.forEach { it.join() } }
                withTimeout(5_000) { while (resources.reservedBytes != 0L) delay(10) }
                println("network >5/>64 headersMs=$firstByteMillis completionMs=${(System.nanoTime() - started) / 1_000_000} reservedBytes=${resources.reservedBytes} cancellations=${jobs.count { it.isCancelled }}")
                assertTrue(jobs.all { it.isCancelled })
                bodiesMayFinish.countDown()
                val resumed = withTimeout(5_000) { proxy.httpGet(endpoint, resourceOwner = "new-owner") }
                assertTrue(resumed is NetworkExecution.Success)
                (resumed as NetworkExecution.Success).release()
                assertEquals(0L, resources.reservedBytes)
            } finally {
                bodiesMayFinish.countDown()
                jobs.forEach { it.cancel() }
            }
        }
    }

    @Test
    fun streamCancellationAfterHeadersAndGatewayRevocationReleaseReservations() = runBlocking {
        ControlledNetworkFixture().use { fixture ->
            val bodyWait = CountDownLatch(1)
            val arrivals = java.util.concurrent.atomic.AtomicInteger()
            val endpoint = fixture.https { _, socket ->
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 8\r\nConnection: close\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                arrivals.incrementAndGet()
                bodyWait.await(20, TimeUnit.SECONDS)
            }
            val resources = NetworkResources(availableHeap = { 64L * 1024 * 1024 })
            val gateway = RuntimeNetworkGateway(fixture.proxy(resources), null)
            val streamId = "stream-" + "1".repeat(32)
            try {
                gateway.openStream(streamId, io.toolbox.tool.runtime.RuntimeNetworkRequest(endpoint, io.toolbox.tool.runtime.RuntimeNetworkMethod.GET))
                val read = async { runCatching { gateway.readStream(streamId, 128 * 1024) } }
                delay(50)
                gateway.cancelStreams()
                withTimeout(5_000) { assertTrue(read.await().isFailure) }
                withTimeout(5_000) { while (resources.reservedBytes != 0L) delay(10) }
                val full = async { runCatching { gateway.request(io.toolbox.tool.runtime.RuntimeNetworkRequest(endpoint, io.toolbox.tool.runtime.RuntimeNetworkMethod.GET)) } }
                withTimeout(5_000) { while (arrivals.get() < 2) delay(10) }
                gateway.cancelStreams()
                withTimeout(5_000) { assertTrue(full.await().isFailure) }
                withTimeout(5_000) { while (resources.reservedBytes != 0L) delay(10) }
            } finally { gateway.close(); bodyWait.countDown() }
        }
    }

    private fun selector(proxy: Proxy) = object : ProxySelector() {
        override fun select(uri: URI) = listOf(proxy)
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
    }
}
