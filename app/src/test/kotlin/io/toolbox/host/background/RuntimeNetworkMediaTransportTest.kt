package io.toolbox.host.background

import io.toolbox.host.runtime.NetworkStreamCancellation
import io.toolbox.tool.runtime.RuntimeIdentity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RuntimeNetworkMediaTransportTest {
    @Test
    fun realTlsRangeAndHeadResponsesKeepSeekMetadata() = runBlocking {
        ControlledNetworkFixture().use { fixture ->
            val requests = CopyOnWriteArrayList<ControlledNetworkFixture.Request>()
            val endpoint = fixture.https { request, socket ->
                requests += request
                if (request.headers["range"] == "bytes=4-7") fixture.respond(socket, 206,
                    mapOf("Content-Type" to "video/mp4", "Content-Range" to "bytes 4-7/10", "Accept-Ranges" to "bytes"), "4567")
                else fixture.respond(socket, 200, mapOf("Content-Type" to "video/mp4", "Accept-Ranges" to "bytes"), "0123456789")
            }
            val resources = NetworkResources(availableHeap = { 64L * 1024 * 1024 })
            val gateway = RuntimeNetworkGateway(fixture.proxy(resources), null, origin = RuntimeIdentity.origin("tls.media"))
            try {
                val media = gateway.openMedia("media-" + "1".repeat(32), "$endpoint/movie", "video")
                val full = requireNotNull(gateway.interceptMedia(media.url, "GET", emptyMap()))
                full.body.use { assertEquals("0123456789", String(it.readBytes())) }
                val partial = requireNotNull(gateway.interceptMedia(media.url, "GET", mapOf("Range" to "bytes=4-7")))
                assertEquals(206, partial.status)
                assertEquals("bytes 4-7/10", partial.headers["content-range"])
                partial.body.use { assertEquals("4567", String(it.readBytes())) }
                val head = requireNotNull(gateway.interceptMedia(media.url, "HEAD", emptyMap()))
                assertEquals("10", head.headers["content-length"])
                assertEquals(-1, head.body.read())
                assertEquals(3, requests.size)
                assertEquals(0L, resources.reservedBytes)
                assertTrue(fixture.failures.isEmpty())
            } finally { gateway.close() }
        }
    }

    @Test
    fun revocationAndExplicitCloseInterruptBlockedNativeSocketReads() = runBlocking {
        ControlledNetworkFixture().use { fixture ->
            val mayFinish = CountDownLatch(1)
            val endpoint = fixture.https { _, socket ->
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: 1000000000\r\nConnection: close\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                mayFinish.await(10, TimeUnit.SECONDS)
            }
            val resources = NetworkResources(availableHeap = { 64L * 1024 * 1024 })
            val toolId = "tls.cancel.media"
            val gateway = RuntimeNetworkGateway(fixture.proxy(resources), null, toolId = toolId, origin = RuntimeIdentity.origin(toolId))
            try {
                for ((index, stop) in listOf<() -> Unit>({ NetworkStreamCancellation.cancel(toolId) }, { gateway.cancelStreams() }, { gateway.close() }).withIndex()) {
                    val session = gateway.openMedia("media-" + (index + 1).toString().repeat(32), "$endpoint/movie", "video")
                    val response = withTimeout(5_000) { requireNotNull(gateway.interceptMedia(session.url, "GET", emptyMap())) }
                    val reading = CompletableDeferred<Unit>()
                    val bodyRead = async(Dispatchers.IO) {
                        reading.complete(Unit)
                        runCatching { response.body.read(ByteArray(8)) }
                    }
                    reading.await()
                    delay(20)
                    assertFalse(bodyRead.isCompleted)
                    if (index == 1) gateway.closeMedia(session.sessionId) else stop()
                    withTimeout(5_000) { assertTrue(bodyRead.await().isFailure) }
                    assertEquals(0L, resources.reservedBytes)
                    assertNull(gateway.interceptMedia(session.url, "GET", emptyMap()))
                }
                assertTrue(fixture.failures.isEmpty())
            } finally { mayFinish.countDown(); gateway.close() }
        }
    }
}
