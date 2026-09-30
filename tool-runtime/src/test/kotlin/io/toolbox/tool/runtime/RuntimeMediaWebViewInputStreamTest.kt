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
        assertThrows(IOException::class.java) { chromiumSeek(oldBody, "bytes=$offset-") }
        assertEquals(tail.size, oldBody.bytesRead)

        for (buffered in listOf(0, 32, tail.size)) {
            val input = TrackedStream(tail, availableBytes = buffered)
            val body = adapt(input, 206, "bytes $offset-${offset + tail.size - 1}/${offset + tail.size}")
            assertEquals(offset + tail.size, body.available().toLong())
            assertEquals(tail.size.toLong(), chromiumSeek(body, "bytes=$offset-"))
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
            assertEquals(bytes.length.toLong(), chromiumSeek(body, "bytes=$offset-"))
            assertEquals(0, input.bytesRead)
            assertEquals(bytes, String(body.readBytes()))
            assertEquals(-1, body.read())
        }
    }

    @Test
    fun suffixBoundsProduceTheCorrectLengthWithoutDiscardingSelectedNativeBytes() {
        for ((contentRange, bytes, range) in listOf(Triple("bytes 6-9/10", "6789", "bytes=-4"), Triple("bytes 0-9/10", "0123456789", "bytes=-20"))) {
            val input = TrackedStream(bytes.toByteArray())
            val body = adapt(input, 206, contentRange, range)
            assertEquals(bytes.length.toLong(), chromiumSeek(body, range))
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
            assertEquals(100, body.available())
            assertEquals(100L, chromiumSeek(body, null))
            assertArrayEquals(ByteArray(100) { it.toByte() }, body.readBytes())
        }
    }

    @Test
    fun normal200StreamsStillReallySkipAfterReadingWithoutReceivingFakeOffsetCredit() {
        val input = TrackedStream("0123456789".toByteArray(), availableBytes = 2)
        val body = adapt(input, 200)
        assertEquals(10, body.available())
        assertEquals(10L, chromiumSeek(body, null))
        assertEquals('0'.code, body.read())
        assertEquals(0, body.available())
        assertEquals(3L, body.skip(3))
        assertEquals(4, input.bytesRead)
        assertEquals(1, input.skipCalls)
        assertEquals("456789", String(body.readBytes()))
        // No synthetic Content-Range or 206 status is added. HTTP seek against
        // Range-ignoring sites remains a documented compatibility limitation.
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
            ResponseCase("HEAD", 200, "bytes=0-", null, 0),
            ResponseCase("HEAD", 206, "bytes=4-7", "bytes 4-7/10", 4),
            ResponseCase("HEAD", 416, "bytes=20-", "bytes */10", 20),
            ResponseCase("GET", 416, "bytes=20-", "bytes */10", 20),
            ResponseCase("GET", 416, "bytes=-20", "bytes */10", -1),
        )) {
            val input = TrackedStream(ByteArray(0))
            val headers = (row.contentRange?.let { mapOf("content-range" to it) } ?: emptyMap()) +
                if (row.status == 200) mapOf("Content-Length" to "10") else if (row.status == 416) mapOf("Content-Length" to "0") else emptyMap()
            val prepared = RuntimeMediaWebViewInputStream.prepare(row.method, mapOf("Range" to row.range), row.status, headers, input)
            val body = prepared.body
            assertFalse(prepared.headers.keys.any { it.equals("content-length", true) })
            val declared = if (row.status == 200) 10L else if (row.status == 206) 4L else 0L
            assertEquals(declared, chromiumSeek(body, row.range))
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
            assertSame(input, RuntimeMediaWebViewInputStream.prepare(method, emptyMap(), status, emptyMap(), input).body)
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

    @Test
    fun chromiumGeneratedContentLengthIsTheOnlyLengthForHeadAndPartialBodies() {
        for ((method, range, contentRange) in listOf(
            Triple("HEAD", "bytes=8-31", "bytes 8-31/4196820"),
            Triple("GET", "bytes=8-31", "bytes 8-31/4196820"),
            Triple("GET", "bytes=-24", "bytes 4196796-4196819/4196820"),
        )) {
            val input = TrackedStream(if (method == "HEAD") ByteArray(0) else ByteArray(24))
            val prepared = RuntimeMediaWebViewInputStream.prepare(method, mapOf("Range" to range), 206,
                mapOf("Content-Range" to contentRange, "content-length" to "24", "Accept-Ranges" to "bytes"), input)
            val generated = chromiumSeek(prepared.body, range)
            // ResponseDelegate's AddHeader must not turn this into "0, 24" or
            // even "24, 24". Exactly one accurate Chromium field remains.
            assertEquals(24L, generated)
            assertFalse(prepared.headers.keys.any { it.equals("content-length", true) })
            assertEquals(contentRange, prepared.headers["Content-Range"])
            assertEquals(if (method == "HEAD") 0 else 24, prepared.body.readBytes().size)
        }
    }

    @Test
    fun lowBoundedAndSuffixRangesRemainUsableForLargeOrUnknownTotalRepresentations() {
        for ((range, contentRange) in listOf(
            "bytes=8-31" to "bytes 8-31/5000000000",
            "bytes=8-31" to "bytes 8-31/*",
            "bytes=-24" to "bytes 4999999976-4999999999/5000000000",
            "bytes=-24" to "bytes 4999999976-4999999999/*",
        )) {
            val prepared = RuntimeMediaWebViewInputStream.prepare("GET", mapOf("Range" to range), 206,
                mapOf("Content-Range" to contentRange, "Content-Length" to "24"), TrackedStream(ByteArray(24)))
            assertEquals(24L, chromiumSeek(prepared.body, range))
            assertEquals(contentRange, prepared.headers["Content-Range"])
            assertEquals(24, prepared.body.readBytes().size)
        }
    }

    @Test
    fun unrepresentableLengthsAndOffsetsAreRejectedBeforeReadingAnyPayload() {
        for ((status, range, headers) in listOf(
            Triple(200, null, emptyMap()),
            Triple(200, null, mapOf("Content-Length" to "2147483648")),
            Triple(206, "bytes=2147483648-", mapOf("Content-Range" to "bytes 2147483648-2147483651/5000000000", "Content-Length" to "4")),
            Triple(206, "bytes=0-", mapOf("Content-Range" to "bytes 0-2147483647/5000000000", "Content-Length" to "2147483648")),
            Triple(206, "bytes=8-31", mapOf("Content-Range" to "bytes 8-31/100", "Content-Length" to "23")),
        )) {
            val input = TrackedStream(ByteArray(4))
            assertThrows(IOException::class.java) {
                RuntimeMediaWebViewInputStream.prepare("GET", range?.let { mapOf("Range" to it) } ?: emptyMap(), status, headers, input)
            }
            assertEquals(0, input.bytesRead)
        }
    }

    @Test
    fun ignoredRange200ResponsesNeverDeliverAShortenedLengthWithTheFullBody() {
        for (range in listOf("bytes=4-", "bytes=4-7", "bytes=0-3", "bytes=-4")) {
            val input = TrackedStream("0123456789".toByteArray(), availableBytes = 1)
            assertThrows(IOException::class.java) {
                RuntimeMediaWebViewInputStream.prepare("GET", mapOf("Range" to range), 200,
                    mapOf("Content-Length" to "10"), input)
            }
            assertEquals(0, input.bytesRead)
            assertEquals(0, input.skipCalls)
        }
    }

    private fun adapt(input: InputStream, status: Int, contentRange: String? = null,
        range: String? = contentRange?.let { Regex("bytes ([0-9]+)-").find(it)?.groupValues?.get(1)?.let { first -> "bytes=$first-" } }): InputStream =
        RuntimeMediaWebViewInputStream.prepare("GET", range?.let { mapOf("Range" to it) } ?: emptyMap(), status,
            (contentRange?.let { mapOf("Content-Range" to it) } ?: emptyMap()) +
                if (status == 200 && input is TrackedStream) mapOf("Content-Length" to input.length.toString()) else emptyMap(), input).body

    private data class ResponseCase(val method: String, val status: Int, val range: String, val contentRange: String?, val first: Long)

    /** Chromium 124 VerifyRequestedRange/ComputeBounds, followed by its skip loop. */
    private fun chromiumSeek(input: InputStream, range: String?): Long {
        val parts = range?.let { Regex("bytes=([0-9]*)-([0-9]*)").matchEntire(it)!!.groupValues }
        var first = parts?.get(1)?.takeIf(String::isNotEmpty)?.toLong() ?: -1
        var last = parts?.get(2)?.takeIf(String::isNotEmpty)?.toLong() ?: -1
        val available = input.available().toLong()
        var length = 0L
        if (available > 0) {
            if (range == null) { first = 0; last = available - 1 }
            else if (first < 0) { first = maxOf(0, available - last); last = available - 1 }
            else {
                if (first >= available) throw IOException("Range exceeds bootstrap representation length")
                last = if (last < 0) available - 1 else minOf(last, available - 1)
            }
            length = last - first + 1
        }
        var remaining = first
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) throw IOException("Range could not be reached")
            remaining -= skipped
        }
        return length
    }

    private class TrackedStream(private val bytes: ByteArray, private val availableBytes: Int = bytes.size) : InputStream() {
        val length: Int get() = bytes.size
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
