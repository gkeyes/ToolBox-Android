package io.toolbox.host.background

import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeIdentity
import io.toolbox.tool.runtime.RuntimeNetworkMediaRoute
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class RuntimeNetworkMediaTest {
    private val origin = RuntimeIdentity.origin("media.fixture")
    private fun id(char: Char) = "media-" + char.toString().repeat(32)

    @Test
    fun hugeMediaIsLazyAndLocalPlayerUrlHidesTheRemoteSource() = runBlocking {
        val resources = NetworkResources(availableHeap = { 64L * 1024 * 1024 })
        val reads = AtomicInteger()
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                reads.incrementAndGet()
                val count = minOf(byteCount, 8192).toInt()
                sink.write(ByteArray(count))
                return count.toLong()
            }
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }
        val proxy = ToolNetworkProxy(ToolNetworkTransport { request, timeout ->
            assertEquals(0L, timeout)
            response(request, source, length = 2_000_000_000L)
        }, resources = resources)
        val gateway = RuntimeNetworkGateway(proxy, null, origin = origin)
        try {
            val session = gateway.openMedia(id('1'), "https://public.example/movie.mp4?token=fixture", "video")
            assertNotNull(RuntimeNetworkMediaRoute.token(session.url, origin))
            assertFalse(session.url.contains("public.example"))
            assertFalse(session.url.contains("token="))
            val media = requireNotNull(gateway.interceptMedia(session.url, "GET", emptyMap()))
            assertEquals(0, reads.get())
            assertEquals(NetworkResources.OPERATION_BYTES, resources.reservedBytes)
            assertEquals(8, media.body.read(ByteArray(8)))
            assertEquals(1, reads.get())
            media.body.close()
            assertEquals(0L, resources.reservedBytes)
        } finally { gateway.close() }
    }

    @Test
    fun seekAndHeadKeepHttpMetadataAndDropAllWebViewCredentials() = runBlocking {
        val requests = mutableListOf<Request>()
        val resources = NetworkResources(availableHeap = { 64L * 1024 * 1024 })
        val proxy = ToolNetworkProxy(ToolNetworkTransport { request, _ ->
            requests += request
            if (request.header("Range") != null) response(request, Buffer().writeUtf8("4567"), code = 206, length = 4,
                headers = mapOf("Content-Range" to "bytes 4-7/10", "Accept-Ranges" to "bytes"))
            else response(request, Buffer().writeUtf8("0123456789"), length = 10, headers = mapOf("Accept-Ranges" to "bytes"))
        }, resources = resources)
        val gateway = RuntimeNetworkGateway(proxy, null, origin = origin)
        try {
            val session = gateway.openMedia(id('2'), "https://public.example/movie", "video")
            val full = requireNotNull(gateway.interceptMedia(session.url, "GET", emptyMap()))
            full.body.use { assertEquals("0123456789", String(it.readBytes())) }
            val partial = requireNotNull(gateway.interceptMedia(session.url, "GET", mapOf("Range" to "bytes=4-7",
                "Authorization" to "Bearer fixture", "Cookie" to "fixture=1", "Referer" to origin, "X-API-Key" to "fixture")))
            assertEquals(206, partial.status)
            assertEquals("bytes 4-7/10", partial.headers["content-range"])
            assertEquals("4", partial.headers["content-length"])
            assertEquals("bytes", partial.headers["accept-ranges"])
            partial.body.use { assertEquals("4567", String(it.readBytes())) }
            val head = requireNotNull(gateway.interceptMedia(session.url, "HEAD", emptyMap()))
            assertEquals("10", head.headers["content-length"])
            assertEquals(-1, head.body.read())
            assertEquals("HEAD", requests.last().method)
            assertEquals("bytes=4-7", requests[1].header("Range"))
            assertEquals("identity", requests[1].header("Accept-Encoding"))
            requests.forEach { request ->
                listOf("Authorization", "Cookie", "Referer", "Origin", "X-API-Key").forEach { assertNull(request.header(it)) }
            }
            assertEquals(0L, resources.reservedBytes)
        } finally { gateway.close() }
    }

    @Test
    fun unsafeMethodsAndRangesFailBeforeOpeningAnyConnection() = runBlocking {
        val calls = AtomicInteger()
        val gateway = RuntimeNetworkGateway(ToolNetworkProxy(ToolNetworkTransport { request, _ ->
            calls.incrementAndGet(); response(request, Buffer())
        }), null, origin = origin)
        try {
            val session = gateway.openMedia(id('3'), "https://public.example/movie", "video")
            listOf("POST", "PUT", "OPTIONS", "get").forEach { method ->
                assertTrue(runCatching { gateway.interceptMedia(session.url, method, emptyMap()) }.isFailure)
            }
            listOf("bytes=0-1,3-4", "bytes=-0", "bytes=-", "bytes=4-1", "bytes=0-99999999999999999999", "bytes= 0-1", "items=0-1").forEach { range ->
                assertTrue(runCatching { gateway.interceptMedia(session.url, "GET", mapOf("Range" to range)) }.isFailure)
            }
            assertTrue(runCatching { gateway.interceptMedia(session.url, "GET", mapOf("Range" to "bytes=0-1", "range" to "bytes=2-3")) }.isFailure)
            assertEquals(0, calls.get())
            listOf("bytes=0-", "bytes=-10", "bytes=1-2").forEach { range ->
                assertEquals(range, RuntimeMediaHttpPolicy.requestHeaders("GET", mapOf("Range" to range))["Range"])
            }
        } finally { gateway.close() }
    }

    @Test
    fun htmlXmlAndInvalidPartialMetadataNeverReachThePlayer() = runBlocking {
        for ((type, code, extra) in listOf(
            Triple("text/html", 200, emptyMap()), Triple("application/xhtml+xml", 200, emptyMap()),
            Triple("video/svg+xml", 200, emptyMap()), Triple("application/octet-stream", 200, emptyMap()),
            Triple("video/mp4", 206, mapOf("Content-Range" to "bytes 4-7/7")),
            Triple("video/mp4", 206, mapOf("Content-Range" to "bytes 4-9/10")),
        )) {
            val resources = NetworkResources(availableHeap = { 64L * 1024 * 1024 })
            val gateway = RuntimeNetworkGateway(ToolNetworkProxy(ToolNetworkTransport { request, _ ->
                response(request, Buffer().writeUtf8("<html>unsafe</html>"), code = code, type = type, length = 18, headers = extra)
            }, resources = resources), null, origin = origin)
            try {
                val session = gateway.openMedia(id('4'), "https://public.example/movie.mp4", "video")
                assertTrue(runCatching { gateway.interceptMedia(session.url, "GET", emptyMap()) }.isFailure)
                assertEquals(0L, resources.reservedBytes)
            } finally { gateway.close() }
        }
    }

    @Test
    fun binaryMp4SignatureIsRememberedForNonzeroSeek() = runBlocking {
        val bytes = ByteArray(80).apply { "ftypisom".toByteArray().copyInto(this, 4) }
        val gateway = RuntimeNetworkGateway(ToolNetworkProxy(ToolNetworkTransport { request, _ ->
            if (request.header("Range") == null) response(request, Buffer().write(bytes), type = "application/octet-stream", length = 80)
            else response(request, Buffer().write(bytes, 40, 8), code = 206, type = "application/octet-stream", length = 8,
                headers = mapOf("Content-Range" to "bytes 40-47/80"))
        }), null, origin = origin)
        try {
            val session = gateway.openMedia(id('5'), "https://public.example/signed-media", "video")
            val full = requireNotNull(gateway.interceptMedia(session.url, "GET", emptyMap()))
            assertEquals("video/mp4", full.mimeType)
            full.body.use { assertArrayEquals(bytes, it.readBytes()) }
            val seek = requireNotNull(gateway.interceptMedia(session.url, "GET", mapOf("Range" to "bytes=40-47")))
            assertEquals("video/mp4", seek.mimeType)
            seek.body.use { assertArrayEquals(bytes.copyOfRange(40, 48), it.readBytes()) }
        } finally { gateway.close() }
    }

    @Test
    fun revokedGrantClosesExistingBodiesAndLateHeaders() = runBlocking {
        var allowed = true
        var revokeAtHeaders = false
        val resources = NetworkResources(availableHeap = { 64L * 1024 * 1024 })
        val gateway = RuntimeNetworkGateway(ToolNetworkProxy(ToolNetworkTransport { request, _ ->
            if (revokeAtHeaders) allowed = false
            response(request, Buffer().writeUtf8("media"), length = 5)
        }, resources = resources), null, validateNetworkAccess = {
            if (!allowed) throw RuntimeHandlerException(RuntimeRpcErrorCode.PERMISSION_DENIED, "revoked")
        }, origin = origin)
        try {
            val session = gateway.openMedia(id('6'), "https://public.example/movie", "video")
            val first = requireNotNull(gateway.interceptMedia(session.url, "GET", emptyMap()))
            revokeAtHeaders = true
            assertTrue(runCatching { gateway.interceptMedia(session.url, "GET", emptyMap()) }.isFailure)
            assertTrue(runCatching { first.body.read() }.isFailure)
            assertNull(gateway.interceptMedia(session.url, "GET", emptyMap()))
            assertEquals(0L, resources.reservedBytes)
            gateway.closeMedia(session.sessionId)
        } finally { gateway.close() }
    }

    @Test
    fun closeBeforeOpenStaleCrossToolAndReloadRoutesCannotOpenConnections() = runBlocking {
        val gateway = RuntimeNetworkGateway(ToolNetworkProxy(ToolNetworkTransport { request, _ -> response(request, Buffer()) }), null, origin = origin)
        gateway.closeMedia(id('7'))
        assertTrue(runCatching { gateway.openMedia(id('7'), "https://public.example/movie", "video") }.isFailure)
        val session = gateway.openMedia(id('8'), "https://public.example/movie", "video")
        val other = session.url.replace(origin.removeSuffix("/"), RuntimeIdentity.origin("other.fixture").removeSuffix("/"))
        assertNull(gateway.interceptMedia(other, "GET", emptyMap()))
        assertNull(gateway.interceptMedia(session.url + "?remote=https://public.example/other", "GET", emptyMap()))
        gateway.cancelStreams()
        assertNull(gateway.interceptMedia(session.url, "GET", emptyMap()))
        val closed = gateway.openMedia(id('9'), "https://public.example/movie", "video")
        gateway.closeMedia(closed.sessionId)
        assertNull(gateway.interceptMedia(closed.url, "GET", emptyMap()))
        listOf("http://public.example/movie", "https://user:pass@public.example/movie", "https://@public.example/movie").forEach { url ->
            assertTrue(runCatching { gateway.openMedia(id('a'), url, "video") }.isFailure)
        }
        gateway.close()
        assertTrue(runCatching { gateway.openMedia(id('b'), "https://public.example/movie", "video") }.isFailure)
    }

    @Test
    fun pendingOpenAndResponseCannotSurviveCloseOrRuntimeClear() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val resources = NetworkResources(availableHeap = { 64L * 1024 * 1024 })
        val gateway = RuntimeNetworkGateway(ToolNetworkProxy(ToolNetworkTransport { request, _ ->
            entered.complete(Unit); finish.await(); response(request, Buffer().writeUtf8("data"), length = 4)
        }, resources = resources), null, origin = origin)
        try {
            val session = gateway.openMedia(id('c'), "https://public.example/movie", "video")
            val response = async { runCatching { gateway.interceptMedia(session.url, "GET", emptyMap()) } }
            entered.await()
            gateway.closeMedia(session.sessionId)
            finish.complete(Unit)
            assertTrue(response.await().isFailure)
            assertEquals(0L, resources.reservedBytes)
        } finally { finish.complete(Unit); gateway.close() }
        val validation = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val pendingGateway = RuntimeNetworkGateway(ToolNetworkProxy(), null, validateNetworkAccess = {
            validation.complete(Unit); resume.await()
        }, origin = origin)
        try {
            val open = async { runCatching { pendingGateway.openMedia(id('d'), "https://public.example/movie", "video") } }
            validation.await()
            pendingGateway.cancelStreams()
            resume.complete(Unit)
            assertTrue(open.await().isFailure)
        } finally { resume.complete(Unit); pendingGateway.close() }
    }

    private fun response(request: Request, source: Source, code: Int = 200, type: String = "video/mp4", length: Long = -1,
        headers: Map<String, String> = emptyMap()): Response {
        val buffered = source.buffer()
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
            .header("Content-Type", type).apply {
                if (length >= 0) header("Content-Length", length.toString())
                headers.forEach { (name, value) -> header(name, value) }
            }.body(object : ResponseBody() {
                override fun contentType() = type.toMediaTypeOrNull()
                override fun contentLength() = length
                override fun source() = buffered
            }).build()
    }
}
