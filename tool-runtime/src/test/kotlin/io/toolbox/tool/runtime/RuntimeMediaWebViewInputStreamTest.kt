package io.toolbox.tool.runtime

import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class RuntimeMediaWebViewInputStreamTest {
    @Test
    fun chromiumBootstrapSeekDoesNotDiscardTheAlreadySelectedMp4Tail() {
        val offset = 4_194_304L
        val tail = ByteArray(2_516) { (it % 251).toByte() }
        // Chromium 124 first verifies available(), then skips the original
        // absolute range before it reads the WebResourceResponse's 206 headers.
        val oldBody = TrackedStream(tail, availableBytes = 0)
        assertThrows(IOException::class.java) { chromiumSeek(oldBody, offset) }
        assertEquals(tail.size, oldBody.bytesRead)

        for (buffered in listOf(0, 32, tail.size)) {
            val input = TrackedStream(tail, availableBytes = buffered)
            val body = adapt(input, 206, "bytes $offset-${offset + tail.size - 1}/${offset + tail.size}")
            assertEquals(0, body.available())
            chromiumSeek(body, offset)
            assertEquals(0, input.bytesRead)
            assertEquals(0, input.skipCalls)
            assertArrayEquals(tail, body.readBytes())
        }
    }

    @Test
    fun zeroStartAndBoundedPartialRangesDeliverOnlyTheirNativeSelectedBytes() {
        for ((offset, contentRange, bytes) in listOf(
            Triple(0L, "bytes 0-9/10", "0123456789"),
            Triple(0L, "bytes 0-3/10", "0123"),
            Triple(4L, "bytes 4-7/10", "4567"),
        )) {
            val input = TrackedStream(bytes.toByteArray())
            val body = adapt(input, 206, contentRange)
            chromiumSeek(body, offset)
            assertEquals(0, input.bytesRead)
            assertEquals(bytes, String(body.readBytes()))
            assertEquals(-1, body.read())
        }
    }

    @Test
    fun suffixRangesNeedNoBootstrapSkipWhenAvailableIsUnknown() {
        for ((contentRange, bytes, range) in listOf(Triple("bytes 6-9/10", "6789", "bytes=-4"), Triple("bytes 0-9/10", "0123456789", "bytes=-20"))) {
            val input = TrackedStream(bytes.toByteArray())
            val body = adapt(input, 206, contentRange, range)
            // HttpByteRange's uncomputed suffix has first_byte_position == -1.
            chromiumSeek(body, -1)
            assertEquals(0, input.skipCalls)
            assertEquals(bytes, String(body.readBytes()))
        }
    }

    @Test
    fun splitBootstrapSkipsConsumeOffsetCreditBeforeTouchingTheNativeBody() {
        val input = TrackedStream("456789".toByteArray())
        val body = adapt(input, 206, "bytes 4-9/10")
        assertEquals(0L, body.skip(-1))
        assertEquals(0L, body.skip(0))
        assertEquals(1L, body.skip(1))
        assertEquals(3L, body.skip(3))
        assertEquals(0, input.bytesRead)
        assertEquals(0, input.skipCalls)
        assertEquals('4'.code, body.read())
        assertEquals(2L, body.skip(2))
        assertEquals('7'.code, body.read())
    }

    @Test
    fun theFirstRealReadClearsAnyUnusedBootstrapCredit() {
        for (singleByte in listOf(true, false)) {
            val input = TrackedStream("456789".toByteArray())
            val body = adapt(input, 206, "bytes 4-9/10")
            if (singleByte) assertEquals('4'.code, body.read())
            else {
                val buffer = ByteArray(3)
                assertEquals(1, body.read(buffer, 1, 1))
                assertEquals('4'.code, buffer[1].toInt())
            }
            assertEquals(2L, body.skip(2))
            assertEquals('7'.code, body.read())
        }
        val input = TrackedStream("4567".toByteArray())
        val body = adapt(input, 206, "bytes 4-7/10")
        assertEquals(0, body.read(ByteArray(0)))
        assertEquals(4L, body.skip(4))
        assertEquals(0, input.bytesRead)
        assertEquals("4567", String(body.readBytes()))
    }

    @Test
    fun aSkipBeyondBootstrapCreditMovesOnlyItsRemainingBytes() {
        val input = TrackedStream("456789".toByteArray())
        val body = adapt(input, 206, "bytes 4-9/10")
        assertEquals(6L, body.skip(6))
        assertEquals(2, input.bytesRead)
        assertEquals('6'.code, body.read())
    }

    @Test
    fun fullResponsesNeverExposeAReadBufferAsTheRepresentationLength() {
        for (buffered in listOf(0, 3, 32, 100)) {
            val input = TrackedStream(ByteArray(100) { it.toByte() }, availableBytes = buffered)
            val body = adapt(input, 200)
            assertEquals(0, body.available())
            chromiumSeek(body, 0)
            assertArrayEquals(ByteArray(100) { it.toByte() }, body.readBytes())
        }
    }

    @Test
    fun anIgnoredRange200ResponseStillReallySkipsRatherThanReceivingFakeOffsetCredit() {
        val input = TrackedStream("0123456789".toByteArray(), availableBytes = 2)
        val body = adapt(input, 200)
        assertEquals(0, body.available())
        chromiumSeek(body, 4)
        assertEquals(4, input.bytesRead)
        assertEquals(1, input.skipCalls)
        assertEquals("456789", String(body.readBytes()))
        // Status and headers are deliberately unchanged. This verifies the
        // stream contract, not successful HTTP seek against Range-ignoring sites.
    }

    @Test
    fun closeIsIdempotentAndAClosedStreamCannotReportBootstrapProgress() {
        val input = TrackedStream("4567".toByteArray())
        val body = adapt(input, 206, "bytes 4-7/10")
        body.close()
        body.close()
        assertEquals(1, input.closes)
        assertEquals(0, input.bytesRead)
        assertThrows(IOException::class.java) { body.skip(4) }
        assertThrows(IOException::class.java) { body.read() }
    }

    @Test
    fun cancellationAndNativeReadFailuresRemainVisibleAndCloseStillReachesTheBody() {
        val input = TrackedStream("4567".toByteArray())
        val body = adapt(input, 206, "bytes 4-7/10")
        input.close()
        assertThrows(IOException::class.java) { body.read(ByteArray(4)) }
        val failure = IOException("Native read failed")
        val broken = object : InputStream() {
            var closes = 0
            override fun read(): Int = throw failure
            override fun close() { closes += 1 }
        }
        val wrapper = adapt(broken, 206, "bytes 4-7/10")
        assertSame(failure, assertThrows(IOException::class.java) { wrapper.read() })
        wrapper.close()
        assertEquals(1, broken.closes)
    }

    @Test
    fun headAnd416ResponsesAbsorbTheOriginalSeekWithoutReadingAnEmptyBody() {
        for (row in listOf(
            ResponseCase("HEAD", 200, "bytes=4-", null, 4),
            ResponseCase("HEAD", 206, "bytes=4-7", "bytes 4-7/10", 4),
            ResponseCase("HEAD", 416, "bytes=20-", "bytes */10", 20),
            ResponseCase("GET", 416, "bytes=20-", "bytes */10", 20),
            ResponseCase("GET", 416, "bytes=-20", "bytes */10", -1),
        )) {
            val input = TrackedStream(ByteArray(0))
            val body = RuntimeMediaWebViewInputStream.wrap(row.method, mapOf("Range" to row.range), row.status,
                row.contentRange?.let { mapOf("content-range" to it) } ?: emptyMap(), input)
            assertEquals(0, body.available())
            chromiumSeek(body, row.first)
            assertEquals(-1, body.read())
            assertEquals(0, input.skipCalls)
            body.close()
            assertEquals(1, input.closes)
        }
    }

    @Test
    fun nonMediaResponsesAreUnchangedAndInvalidOrMismatched206FailsBeforeReading() {
        for ((method, status) in listOf("POST" to 200, "GET" to 404)) {
            val input = TrackedStream(ByteArray(0))
            assertSame(input, RuntimeMediaWebViewInputStream.wrap(method, emptyMap(), status, emptyMap(), input))
        }
        for (range in listOf(null, "bytes */10", "bytes invalid", "bytes 9223372036854775808-9223372036854775809/*")) {
            val input = TrackedStream("4567".toByteArray())
            assertThrows(IOException::class.java) { adapt(input, 206, range) }
            assertEquals(0, input.bytesRead)
        }
        for ((range, contentRange) in listOf("bytes=0-" to "bytes 4-7/10", "bytes=4-5" to "bytes 4-7/10", "bytes=-4" to "bytes 4-7/10")) {
            val input = TrackedStream("4567".toByteArray())
            assertThrows(IOException::class.java) { adapt(input, 206, contentRange, range) }
            assertEquals(0, input.bytesRead)
        }
    }

    private fun adapt(input: InputStream, status: Int, contentRange: String? = null,
        range: String? = contentRange?.let { Regex("bytes ([0-9]+)-").find(it)?.groupValues?.get(1)?.let { first -> "bytes=$first-" } }): InputStream =
        RuntimeMediaWebViewInputStream.wrap("GET", range?.let { mapOf("Range" to it) } ?: emptyMap(), status,
            contentRange?.let { mapOf("Content-Range" to it) } ?: emptyMap(), input)

    private data class ResponseCase(val method: String, val status: Int, val range: String, val contentRange: String?, val first: Long)

    /** The Chromium 124 bootstrap behavior for unknown-length open/suffix ranges. */
    private fun chromiumSeek(input: InputStream, first: Long) {
        val available = input.available()
        if (available > 0 && first >= available) throw IOException("Range exceeds estimated representation length")
        var remaining = first
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) throw IOException("Range could not be reached")
            remaining -= skipped
        }
    }

    private class TrackedStream(private val bytes: ByteArray, private val availableBytes: Int = bytes.size) : InputStream() {
        private var cursor = 0
        var bytesRead = 0
        var skipCalls = 0
        var closes = 0
        override fun available(): Int = availableBytes
        override fun read(): Int {
            if (closes > 0) throw IOException("Native body closed")
            if (cursor == bytes.size) return -1
            bytesRead += 1
            return bytes[cursor++].toInt() and 255
        }
        override fun skip(count: Long): Long { skipCalls += 1; return super.skip(count) }
        override fun close() { closes += 1 }
    }
}
