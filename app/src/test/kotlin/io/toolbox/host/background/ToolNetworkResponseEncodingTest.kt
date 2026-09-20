package io.toolbox.host.background

import java.io.IOException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class ToolNetworkResponseEncodingTest {
    @Test
    fun completeTextPreservesUtf8AcrossReadsAndMalformedInput() = runBlocking {
        val cases = listOf(
            null to ByteArray(0),
            "text/plain" to "雪😀é\u0000</script>".toByteArray(),
            "application/json" to ("x".repeat(65_535) + "雪😀é").toByteArray(),
            "application/problem+json" to byteArrayOf(0xF0.toByte(), 0x28, 0x8C.toByte(), 0x28, 0xE2.toByte(), 0x82.toByte()),
        )
        for ((contentType, bytes) in cases) {
            val resources = NetworkResources(availableHeap = { 64L * 1024 * 1024 })
            val result = proxy(resources, contentType, fragmented(bytes, if (bytes.size < 100) 1 else 8191))
                .httpGet("https://example.test/data") as NetworkExecution.Success
            try {
                assertEquals(NetworkBodyEncoding.TEXT, result.bodyEncoding)
                assertEquals(String(bytes, Charsets.UTF_8), result.body)
                assertEquals(bytes.size * 8L, resources.reservedBytes)
            } finally { result.release(); result.release() }
            assertEquals(0L, resources.reservedBytes)
        }
    }

    @Test
    fun binaryEncodingUsesOnlyReceivedBytesAndKeepsBase64Padding() = runBlocking {
        for (length in listOf(0, 1, 2, 3, 31, 33, 131_073)) {
            val bytes = ByteArray(length) { (it * 37).toByte() }
            val resources = NetworkResources(availableHeap = { 64L * 1024 * 1024 })
            val result = proxy(resources, "application/octet-stream", fragmented(bytes, 8191))
                .httpGet("https://example.test/data") as NetworkExecution.Success
            try {
                assertEquals(NetworkBodyEncoding.BASE64, result.bodyEncoding)
                assertArrayEquals(bytes, Base64.getDecoder().decode(result.body))
                assertEquals(4 * ((length + 2) / 3), result.body.length)
                assertEquals(bytes.size * 8L, resources.reservedBytes)
            } finally { result.release() }
            assertEquals(0L, resources.reservedBytes)
        }
    }

    @Test
    fun cancellationAfterPartialAggregationReleasesAllReservedCapacity() = runBlocking {
        val waiting = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var first = true
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (first) {
                    first = false
                    sink.writeUtf8("partial body")
                    return 12
                }
                waiting.countDown()
                closed.await()
                throw IOException("Source closed")
            }
            override fun timeout() = Timeout.NONE
            override fun close() { closed.countDown() }
        }
        val resources = NetworkResources(availableHeap = { 64L * 1024 * 1024 })
        val request = async { proxy(resources, "text/plain", source).httpGet("https://example.test/data") }
        try {
            withTimeout(2_000) { while (waiting.count > 0) delay(1) }
            assertTrue(resources.reservedBytes >= NetworkResources.OPERATION_BYTES + 12 * 8)
            withTimeout(2_000) { request.cancelAndJoin() }
            assertEquals(0L, resources.reservedBytes)
            withTimeout(2_000) { while (closed.count > 0) delay(1) }
        } finally { closed.countDown(); request.cancelAndJoin() }
    }

    private fun fragmented(bytes: ByteArray, fragmentBytes: Int) = object : Source {
        private var offset = 0
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (offset == bytes.size) return -1
            val count = minOf(byteCount, fragmentBytes.toLong(), (bytes.size - offset).toLong()).toInt()
            sink.write(bytes, offset, count)
            offset += count
            return count.toLong()
        }
        override fun timeout() = Timeout.NONE
        override fun close() = Unit
    }

    private fun proxy(resources: NetworkResources, contentType: String?, source: Source) =
        ToolNetworkProxy(ToolNetworkTransport { request, _ ->
            val buffered = source.buffer()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(object : ResponseBody() {
                    override fun contentType() = contentType?.toMediaTypeOrNull()
                    override fun contentLength() = -1L
                    override fun source() = buffered
                }).build()
        }, resources = resources)
}
