package io.toolbox.host.background

import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeNetworkMethod
import io.toolbox.tool.runtime.RuntimeNetworkRequest
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class NetworkResourceAdmissionTest {
    @Test
    fun completeRequestsLeaveNoRetiredStreamIdsAndClearRevokesActiveCalls() {
        val streams = RuntimeNetworkStreams()
        repeat(1_000) { streams.releaseRequest(streams.registerRequest()) }
        assertEquals(0, streams.activeRequestCount)
        assertEquals(0, streams.retiredStreamCount)
        val active = streams.registerRequest()
        streams.clear()
        assertEquals(0, streams.activeRequestCount)
        assertEquals("CANCELLED", (runCatching { active.requireActive() }.exceptionOrNull() as ToolNetworkFailure).code)
        streams.close()
    }

    @Test
    fun requestRegistrationRacingWithCloseCannotLeaveAnActiveCall() = runBlocking {
        val streams = RuntimeNetworkStreams()
        val controls = java.util.Collections.synchronizedList(mutableListOf<ToolNetworkStreamControl>())
        val registration = async(Dispatchers.Default) {
            repeat(1_000) {
                runCatching { streams.registerRequest() }.getOrNull()?.let(controls::add)
            }
        }
        streams.close()
        registration.await()
        assertEquals(0, streams.activeRequestCount)
        controls.forEach { control ->
            assertEquals("CANCELLED", (runCatching { control.requireActive() }.exceptionOrNull() as ToolNetworkFailure).code)
        }
    }

    @Test
    fun oneSharedProbeServesWaitingOwnersAndStopsWhenQueueEmpties() = runBlocking {
        val probes = AtomicInteger()
        val resources = NetworkResources(availableHeap = { probes.incrementAndGet(); 0 })
        val controls = List(40) { ToolNetworkStreamControl() }
        val waiters = controls.mapIndexed { index, control ->
            async { runCatching { resources.admit("owner-$index", 100, control) } }
        }
        withTimeout(2_000) { while (resources.waitingCount != controls.size) delay(1) }
        val before = probes.get()
        delay(250)
        assertTrue("one 50 ms timer should probe all waiters", probes.get() - before <= 8)
        controls.forEach { it.cancel() }
        waiters.forEach { assertTrue(withTimeout(2_000) { it.await() }.isFailure) }
        assertEquals(0, resources.waitingCount)
        val after = probes.get()
        delay(120)
        assertEquals(after, probes.get())
    }

    @Test
    fun fittingOwnerBypassesOversizedHeadWithoutOvertakingItsOwnQueue() = runBlocking {
        val heap = AtomicLong(0)
        val resources = NetworkResources(availableHeap = heap::get)
        val largeControl = ToolNetworkStreamControl()
        val large = async { runCatching { resources.admit("large", 120, largeControl) } }
        val small = async { resources.admit("small", 80, ToolNetworkStreamControl()) }
        withTimeout(2_000) { while (resources.waitingCount != 2) delay(1) }
        heap.set(200)
        withTimeout(2_000) { small.await() }.close()
        assertFalse(large.isCompleted)
        largeControl.cancel()
        assertTrue(withTimeout(2_000) { large.await() }.isFailure)
        assertEquals(0L, resources.reservedBytes)
    }

    @Test
    fun lowResourceQueueIsCancellableAndRoundRobinOwnersMakeProgress() = runBlocking {
        val heap = AtomicLong(0)
        val resources = NetworkResources(availableHeap = heap::get)
        val first = async { resources.admit("a", 100, ToolNetworkStreamControl()) }
        val second = async { resources.admit("a", 100, ToolNetworkStreamControl()) }
        val other = async { resources.admit("b", 100, ToolNetworkStreamControl()) }
        val cancelled = async { resources.admit("cancel", 100, ToolNetworkStreamControl()) }
        withTimeout(2_000) { while (resources.waitingCount != 4) delay(1) }
        cancelled.cancelAndJoin()
        assertEquals(3, resources.waitingCount)
        heap.set(200)
        val firstLease = withTimeout(2_000) { first.await() }
        assertFalse(second.isCompleted)
        assertFalse(other.isCompleted)
        firstLease.close()
        val otherLease = withTimeout(2_000) { other.await() }
        assertFalse(second.isCompleted)
        otherLease.close()
        withTimeout(2_000) { second.await() }.close()
        assertEquals(0L, resources.reservedBytes)
        assertEquals(0, resources.waitingCount)
    }

    @Test
    fun infrastructureRecoveryAndControlCancellationDoNotConsumeReservations() = runBlocking {
        var available = false
        val resources = NetworkResources(availableHeap = { 1_000_000 }, infrastructureAvailable = { available })
        val control = ToolNetworkStreamControl()
        val cancelled = async { runCatching { resources.admit("a", 100, control) } }
        withTimeout(2_000) { while (resources.waitingCount == 0) delay(1) }
        control.cancel()
        assertTrue(withTimeout(2_000) { cancelled.await() }.isFailure)
        val resumed = async { resources.admit("b", 100, ToolNetworkStreamControl()) }
        delay(20)
        assertFalse(resumed.isCompleted)
        available = true
        withTimeout(2_000) { resumed.await() }.close()
        assertEquals(0L, resources.reservedBytes)
    }

    @Test
    fun bufferReservationsAreSharedUntilConsumerReleasesAndLargeChunksAreNegotiable() = runBlocking {
        val resources = NetworkResources(availableHeap = { 2L * 1024 * 1024 })
        val proxy = ToolNetworkProxy(ToolNetworkTransport { request, _ ->
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ByteArray(512 * 1024) { 7 }.toResponseBody()).build()
        }, resources = resources)
        val controlA = ToolNetworkStreamControl()
        val controlB = ToolNetworkStreamControl()
        val options = ToolNetworkRequest("https://example.test/data", NetworkRequestMethod.GET, emptyMap(), null, false, 0, 3_000_000_000L)
        val first = proxy.openStream(options, controlA)
        val second = proxy.openStream(options.copy(resourceOwner = "other"), controlB)
        try {
            val chunkA = first.read(256 * 1024)
            try {
                assertTrue(chunkA.data.size > 16 * 1024)
                assertTrue(chunkA.data.size < 256 * 1024)
                assertEquals(2 * NetworkResources.OPERATION_BYTES + chunkA.data.size * 8L, resources.reservedBytes)
                val attempted = runCatching { second.read(256 * 1024) }
                attempted.getOrNull()?.release?.invoke()
                assertEquals("INSUFFICIENT_MEMORY", (attempted.exceptionOrNull() as ToolNetworkFailure).code)
            } finally { chunkA.release() }
            val chunkB = second.read(256 * 1024)
            try { assertTrue(chunkB.data.size > 16 * 1024) } finally { chunkB.release() }
        } finally { controlA.cancel(); controlB.cancel() }
        assertEquals(0L, resources.reservedBytes)
    }

    @Test
    fun streamBudgetOverIntRemainsCumulativeAndExplicitSmallBudgetFailsWithoutLeaks() = runBlocking {
        val resources = NetworkResources(availableHeap = { 64L * 1024 * 1024 })
        val proxy = ToolNetworkProxy(ToolNetworkTransport { request, _ ->
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ByteArray(128 * 1024).toResponseBody()).build()
        }, resources = resources)
        val options = ToolNetworkRequest("https://example.test/data", NetworkRequestMethod.GET, emptyMap(), null, false, 0, 3_000_000_000L)
        val control = ToolNetworkStreamControl()
        val stream = proxy.openStream(options, control)
        var received = 0L
        try {
            while (true) {
                val chunk = stream.read(48 * 1024)
                try {
                    assertTrue(chunk.data.size <= 48 * 1024)
                    received += chunk.data.size
                    assertEquals(received, chunk.receivedBytes)
                    if (chunk.done) break
                } finally { chunk.release() }
            }
        } finally { control.cancel() }
        assertEquals(128 * 1024L, received)
        assertEquals(NetworkExecution.TerminalFailure("RESULT_TOO_LARGE"), proxy.request(
            options.url, NetworkRequestMethod.GET, maxResponseBytes = 100))
        assertEquals(0L, resources.reservedBytes)
    }

    @Test
    fun shortReadsReturnWithoutWaitingToFillAndCancellationReleasesBlockedRead() = runBlocking {
        val calls = AtomicInteger()
        val blocked = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (calls.incrementAndGet() == 1) {
                    sink.writeByte(42)
                    return 1
                }
                blocked.countDown()
                closed.await()
                throw IOException("Source closed")
            }
            override fun timeout() = Timeout.NONE
            override fun close() { closed.countDown() }
        }.buffer()
        val resources = NetworkResources(availableHeap = { 64L * 1024 * 1024 })
        val proxy = ToolNetworkProxy(ToolNetworkTransport { request, _ ->
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(object : ResponseBody() {
                    override fun contentType() = null
                    override fun contentLength() = -1L
                    override fun source() = source
                }).build()
        }, resources = resources)
        val control = ToolNetworkStreamControl()
        try {
            val stream = proxy.openStream(ToolNetworkRequest("https://example.test/slow", NetworkRequestMethod.GET,
                emptyMap(), null, false, 0, null), control)
            val first = withTimeout(2_000) { stream.read(128 * 1024) }
            try {
                assertArrayEquals(byteArrayOf(42), first.data)
                assertFalse(first.done)
                assertEquals(1, calls.get())
            } finally { first.release() }
            val read = async { runCatching { stream.read(128 * 1024) } }
            withTimeout(2_000) { while (blocked.count > 0) delay(1) }
            control.cancel()
            val result = withTimeout(2_000) { read.await() }
            result.getOrNull()?.release?.invoke()
            assertEquals("CANCELLED", (result.exceptionOrNull() as ToolNetworkFailure).code)
            withTimeout(2_000) { while (resources.reservedBytes != 0L) delay(1) }
            assertEquals(0L, resources.reservedBytes)
        } finally { control.cancel(); closed.countDown() }
    }

    @Test
    fun explicitTimeoutIncludesResourceQueueAndReleasesWaitingEntry() = runBlocking {
        val resources = NetworkResources(availableHeap = { 0 })
        val proxy = ToolNetworkProxy(ToolNetworkTransport { _, _ -> error("Request must remain queued") }, resources = resources)
        val result = withTimeout(2_000) { proxy.httpGet("https://example.test", timeoutMillis = 20) }
        assertEquals(NetworkExecution.RetryableFailure("NETWORK_TIMEOUT"), result)
        assertEquals(0, resources.waitingCount)
        assertEquals(0L, resources.reservedBytes)
    }

    @Test
    fun runtimeGatewayFullRequestTimeoutReleasesQueuedAdmission() = runBlocking {
        val resources = NetworkResources(availableHeap = { 0 })
        val proxy = ToolNetworkProxy(
            ToolNetworkTransport { _, _ -> error("Request must remain queued") },
            resources = resources,
        )
        val gateway = RuntimeNetworkGateway(proxy, null)
        try {
            val failure = withTimeout(2_000) {
                runCatching {
                    gateway.request(RuntimeNetworkRequest(
                        "https://example.test", RuntimeNetworkMethod.GET, timeoutMillis = 20,
                    ))
                }.exceptionOrNull()
            }
            assertTrue(failure is RuntimeHandlerException)
            assertEquals(RuntimeRpcErrorCode.NETWORK_TIMEOUT, (failure as RuntimeHandlerException).errorCode)
            assertEquals(0, resources.waitingCount)
            assertEquals(0L, resources.reservedBytes)
        } finally {
            gateway.close()
        }
    }

    @Test
    fun fullResponseUsesSharedCapacityEvenWithAnUnboundedCumulativeBudget() = runBlocking {
        val resources = NetworkResources(availableHeap = { 512L * 1024 })
        val proxy = ToolNetworkProxy(ToolNetworkTransport { request, _ ->
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ByteArray(512 * 1024).toResponseBody()).build()
        }, resources = resources)
        assertEquals(NetworkExecution.TerminalFailure("INSUFFICIENT_MEMORY"), proxy.httpGet("https://example.test/data"))
        assertEquals(0L, resources.reservedBytes)
    }
}
