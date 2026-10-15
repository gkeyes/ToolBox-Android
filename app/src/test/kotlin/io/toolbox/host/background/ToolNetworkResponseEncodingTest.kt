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
import okhttp3.ResponseBody.Companion.toResponseBody
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
            "text/plain" to ByteArray(0),
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
    fun textUsesTheDeclaredCharsetAndAnUndeclaredTypeIsBinary() = runBlocking {
        val cases = listOf(
            "text/html; charset=ISO-8859-1" to Charsets.ISO_8859_1,
            "application/json; charset=UTF-16" to Charsets.UTF_16,
            "text/plain; charset=unknown-charset" to Charsets.UTF_8,
        )
        for ((contentType, charset) in cases) {
            val bytes = "café ünïcödé".toByteArray(charset)
            val resources = NetworkResources(availableHeap = { 64L * 1024 * 1024 })
            val result = proxy(resources, contentType, fragmented(bytes, 3))
                .httpGet("https://example.test/data") as NetworkExecution.Success
            try {
                assertEquals(NetworkBodyEncoding.TEXT, result.bodyEncoding)
                assertEquals(String(bytes, charset), result.body)
            } finally { result.release() }
        }
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0xFF.toByte(), 0x00)
        val resources = NetworkResources(availableHeap = { 64L * 1024 * 1024 })
        val result = proxy(resources, null, fragmented(bytes, 2)).httpGet("https://example.test/data") as NetworkExecution.Success
        try {
            assertEquals(NetworkBodyEncoding.BASE64, result.bodyEncoding)
            assertArrayEquals(bytes, Base64.getDecoder().decode(result.body))
        } finally { result.release() }
    }

    @Test
    fun repeatedResponseHeadersAreJoinedInOrder() = runBlocking {
        val resources = NetworkResources(availableHeap = { 64L * 1024 * 1024 })
        val proxy = ToolNetworkProxy(ToolNetworkTransport { request, _ ->
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .addHeader("Link", "<https://example.test/?page=2>; rel=\"next\"")
                .addHeader("link", "<https://example.test/?page=9>; rel=\"last\"")
                .addHeader("Set-Cookie", "hidden=1")
                .body(ByteArray(0).toResponseBody("text/plain".toMediaTypeOrNull())).build()
        }, resources = resources)
        val result = proxy.httpGet("https://example.test/data") as NetworkExecution.Success
        try {
            val link = result.headers.entries.single { it.key.equals("Link", true) }.value
            assertEquals("<https://example.test/?page=2>; rel=\"next\", <https://example.test/?page=9>; rel=\"last\"", link)
            assertTrue(result.headers.keys.none { it.equals("Set-Cookie", true) })
        } finally { result.release() }
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
            withTimeout(2_000) { while (closed.count > 0) delay(1) }
            // Cancellation detaches the blocked chunk read; its buffer reservation is released on the body
            // dispatcher once closing the response makes that read fail, which can land after the join.
            withTimeout(2_000) { while (resources.reservedBytes != 0L) delay(1) }
            assertEquals(0L, resources.reservedBytes)
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
