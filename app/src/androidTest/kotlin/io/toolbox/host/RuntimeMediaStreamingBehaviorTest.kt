package io.toolbox.host

import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import io.toolbox.core.data.SecurityProfile
import io.toolbox.host.background.NetworkResources
import io.toolbox.host.background.RuntimeNetworkGateway
import io.toolbox.host.background.ToolNetworkProxy
import io.toolbox.host.background.ToolNetworkTransport
import io.toolbox.tool.api.ToolBoxApiV1
import io.toolbox.tool.api.ToolBoxCapabilityId
import io.toolbox.tool.packagekit.InstalledManifest
import io.toolbox.tool.packagekit.InstalledManifestPermission
import io.toolbox.tool.runtime.AndroidRuntimeSystemPermissionChecker
import io.toolbox.tool.runtime.DefaultRuntimeAuthorizationPolicy
import io.toolbox.tool.runtime.HardenedRuntimeWebView
import io.toolbox.tool.runtime.PreparedToolRuntime
import io.toolbox.tool.runtime.RuntimeBridgeConfiguration
import io.toolbox.tool.runtime.RuntimeBridgeProvider
import io.toolbox.tool.runtime.RuntimeCreationPermit
import io.toolbox.tool.runtime.RuntimeCreationPermitResult
import io.toolbox.tool.runtime.RuntimeDataCleanupExecution
import io.toolbox.tool.runtime.RuntimeGrantStateSource
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeIdentity
import io.toolbox.tool.runtime.RuntimeM1Handlers
import io.toolbox.tool.runtime.RuntimeM2Handlers
import io.toolbox.tool.runtime.RuntimePolicyDecision
import io.toolbox.tool.runtime.RuntimeProfileManager
import io.toolbox.tool.runtime.RuntimeQuotaChecker
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import io.toolbox.tool.runtime.RuntimeWebViewCallbacks
import io.toolbox.tool.runtime.RuntimeWebViewCreationResult
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production STRICT AssetLoader, bridge, Gateway and WebView; only HTTP transport supplies test bytes. */
@RunWith(AndroidJUnit4::class)
class RuntimeMediaStreamingBehaviorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun mp4AndMp3PlaySeekAndCloseThroughTheProductionRuntime() = exercise {
        val ready = awaitPhase("ready")
        assertTrue(ready.toString(), ready.getInt("videoWidth") > 0)
        assertTrue(ready.toString(), ready.getDouble("videoDuration") > 0 && ready.getDouble("audioDuration") > 0)
        val sessions = ready.getJSONArray("sessions")
        for (index in 0 until sessions.length()) {
            assertTrue(sessions.toString(), sessions.getJSONObject(index).getString("url").startsWith(runtime.origin + ".toolbox/media/"))
        }
        val range = ready.getJSONObject("range")
        assertEquals(206, range.getInt("status"))
        assertEquals("bytes 8-31/${transport.videoLength}", range.getString("contentRange"))
        val actualBytes = range.getJSONArray("bytes")
        assertEquals(24, actualBytes.length())
        transport.videoBytes.copyOfRange(8, 32).forEachIndexed { index, byte -> assertEquals(byte.toInt() and 255, actualBytes.getInt(index)) }
        assertTrue(transport.requests.toString(), transport.requests.any { it.header("Range") == "bytes=8-31" })
        // page.js only probes bytes 8-31. A range into the distant moov atom is requested by the actual player.
        assertTrue("The player did not fetch the distant moov atom: ${transport.diagnostics()}", transport.tailRanges.get() > 0)
        assertTrue("No moov bytes were read from a nonzero player range: ${transport.diagnostics()}", transport.tailRangeBytes.get() > 0)

        tap("play")
        val playing = awaitPage("decoded video and advancing video/audio clocks") {
            it.optBoolean("trusted") && it.optDouble("videoTime") >= 0.12 && it.optDouble("audioTime") >= 0.12 && it.optInt("videoFrames") > 0
        }
        assertTrue(playing.toString(), playing.getBoolean("trusted"))
        tap("seek")
        val seeked = awaitPhase("seeked")
        assertEquals(seeked.getDouble("videoSeekTarget"), seeked.getDouble("videoSeekTime"), 0.08)
        assertEquals(seeked.getDouble("audioSeekTarget"), seeked.getDouble("audioSeekTime"), 0.08)
        tap("close")
        val closed = awaitPhase("closed")
        assertEquals(0, closed.getJSONArray("violations").length())
        assertEquals(404, closed.getJSONArray("closedStatus").getInt(0))
        assertEquals(404, closed.getJSONArray("closedStatus").getInt(1))
        awaitReleased()
        report("RUNTIME_MEDIA_PLAYBACK $closed ${transport.diagnostics()}")
    }

    @Test
    fun closeReloadAndRuntimeReleaseCancelBodiesAlreadyBlockedInRead() = exercise {
        awaitPhase("ready")
        tap("close")
        awaitPhase("closed")
        awaitReleased()
        tap("hold")
        awaitBlocked(1)
        val first = awaitPhase("holding").getJSONObject("held")
        assertTrue(resources.reservedBytes > 0)
        tap("close-held")
        awaitPhase("held-closed")
        awaitReleased()
        assertRouteClosed(first.getString("url"))

        tap("hold")
        awaitBlocked(2)
        val second = awaitPhase("holding").getJSONObject("held")
        onMain { HardenedRuntimeWebView.reload(requireNotNull(page)) }
        awaitPhase("ready")
        tap("close")
        awaitPhase("closed")
        awaitReleased()
        assertRouteClosed(second.getString("url"))

        tap("hold")
        awaitBlocked(3)
        val third = awaitPhase("holding").getJSONObject("held")
        disposeView()
        awaitReleased()
        assertRouteClosed(third.getString("url"))
        assertEquals(3, transport.blockedReads.get())
        report("RUNTIME_MEDIA_CLEANUP ${transport.diagnostics()} reserved=${resources.reservedBytes} waiting=${resources.waitingCount}")
    }

    private fun exercise(test: Harness.() -> Unit) {
        val harness = Harness()
        var primaryFailure: Throwable? = null
        try { harness.create(); harness.test() }
        catch (failure: Throwable) { primaryFailure = failure; throw failure }
        finally {
            val cleanup = runCatching { harness.close() }.exceptionOrNull()
            if (primaryFailure != null) cleanup?.let(primaryFailure::addSuppressed)
            else if (cleanup != null) throw cleanup
        }
    }

    private inner class Harness {
        private lateinit var scenario: ActivityScenario<MainActivity>
        lateinit var runtime: PreparedToolRuntime
        lateinit var transport: MediaTransport
        val resources = NetworkResources()
        private lateinit var manager: RuntimeProfileManager
        private lateinit var toolRoot: Path
        private lateinit var gateway: RuntimeNetworkGateway
        private var permit: RuntimeCreationPermit? = null
        var page: WebView? = null
        private val loadFailure = AtomicReference<String?>()

        fun create() {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            val filesRoot = instrumentation.targetContext.filesDir.toPath().toAbsolutePath().normalize()
            val toolId = "com.example.media." + UUID.randomUUID().toString().replace("-", "")
            toolRoot = filesRoot.resolve("miniapps/$toolId")
            val bundleRoot = filesRoot.resolve(RuntimeIdentity.expectedBundleLocator(toolId, 1))
            Files.createDirectories(bundleRoot)
            val assets = instrumentation.context.assets
            val videoBytes = assets.open("runtime-media/clip.mp4").use { it.readBytes() }
            val audioBytes = assets.open("runtime-media/tone.mp3").use { it.readBytes() }
            assets.open("runtime-media/page.js").use { input ->
                Files.newOutputStream(bundleRoot.resolve("page.js")).use { output -> input.copyTo(output) }
            }
            Files.writeString(bundleRoot.resolve("index.html"), """
                <!doctype html><html><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <script defer src="page.js"></script>
                <style>body{margin:16px}button{min-width:90px;min-height:48px;margin:4px}video{display:block;width:192px;height:108px}audio{display:block;width:280px}</style>
                </head><body><button id="play" disabled>Play</button><button id="seek" disabled>Seek</button><button id="close" disabled>Close</button><br>
                <button id="hold" disabled>Hold body</button><button id="close-held" disabled>Close held</button>
                <video id="video" playsinline preload="auto"></video><audio id="audio" preload="auto"></audio></body></html>
            """.trimIndent())
            runtime = PreparedToolRuntime(toolId, "Runtime media behavior test", 1, filesRoot, bundleRoot,
                "index.html", RuntimeIdentity.origin(toolId), RuntimeIdentity.profileName(toolId), SecurityProfile.STRICT,
                InstalledManifest(toolId, "Runtime media behavior test", 1, "0.3.0", "index.html", SecurityProfile.STRICT,
                    setOf("network"), listOf(InstalledManifestPermission("network", "Test media playback", true)), null),
                declaredCapabilities = setOf("network"))
            val grants = object : RuntimeGrantStateSource {
                override suspend fun currentVersionCode(toolId: String): Int? = if (toolId == runtime.toolId) 1 else null
                override suspend fun isGranted(toolId: String, capability: ToolBoxCapabilityId) =
                    toolId == runtime.toolId && capability == ToolBoxCapabilityId.NETWORK
            }
            val permissions = AndroidRuntimeSystemPermissionChecker(instrumentation.targetContext)
            val authorization = DefaultRuntimeAuthorizationPolicy(grants, permissions,
                RuntimeQuotaChecker { _, _, _ -> RuntimePolicyDecision.Allowed })
            transport = MediaTransport(videoBytes, audioBytes)
            gateway = RuntimeNetworkGateway(ToolNetworkProxy(transport = transport, resources = resources), null,
                validateNetworkAccess = {
                    if (!grants.isGranted(toolId, ToolBoxCapabilityId.NETWORK) ||
                        !permissions.hasAll(ToolBoxApiV1.capability(ToolBoxCapabilityId.NETWORK).systemPermissions)) {
                        throw RuntimeHandlerException(RuntimeRpcErrorCode.PERMISSION_DENIED, "Test network permission unavailable")
                    }
                }, toolId = toolId, origin = runtime.origin)
            manager = RuntimeProfileManager(filesRoot.toFile())
            permit = runBlocking {
                when (val result = manager.acquireRuntimePermit(toolId, awaitExistingRuntimeRelease = false)) {
                    is RuntimeCreationPermitResult.Ready -> result.permit
                    is RuntimeCreationPermitResult.Rejected -> error("Runtime media permit rejected: ${result.reason}")
                }
            }
            scenario.onActivity { activity ->
                val container = FrameLayout(activity)
                activity.setContentView(container)
                val created = HardenedRuntimeWebView.create(activity, runtime, requireNotNull(permit),
                    RuntimeWebViewCallbacks({}, { loadFailure.set(it) }, { loadFailure.set("Runtime media renderer exited") }),
                    RuntimeBridgeProvider { RuntimeBridgeConfiguration(authorization, RuntimeM1Handlers(), "1.0.0",
                        m2Handlers = RuntimeM2Handlers(network = gateway)) })
                page = when (created) {
                    is RuntimeWebViewCreationResult.Created -> created.webView
                    is RuntimeWebViewCreationResult.Failed -> error("Production WebView creation failed: ${created.message}")
                }
                container.addView(requireNotNull(page), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
            val provider = onMain { WebViewCompat.getCurrentWebViewPackage(instrumentation.targetContext) }
            report("RUNTIME_MEDIA_ENV webview=${provider?.packageName} version=${provider?.versionName} profile=STRICT")
        }

        fun awaitPhase(phase: String): JSONObject = awaitPage(phase) { it.optString("phase") == phase }

        fun awaitPage(label: String, condition: (JSONObject) -> Boolean): JSONObject {
            var last = JSONObject()
            await(label) {
                loadFailure.get()?.let { error(it) }
                val decoded = JSONTokener(evaluate("JSON.stringify(globalThis.runtimeMediaReport || null)")).nextValue()
                if (decoded is String && decoded != "null") {
                    last = JSONObject(decoded)
                    if (!last.isNull("fatal")) error(last.toString())
                    condition(last)
                } else false
            }
            return last
        }

        private fun evaluate(script: String): String {
            val result = CompletableFuture<String>()
            onMain { requireNotNull(page).evaluateJavascript(script) { result.complete(it ?: "null") } }
            return result.get(5, TimeUnit.SECONDS)
        }

        fun tap(id: String) {
            val raw = evaluate("""(() => {const r=document.getElementById(${JSONObject.quote(id)}).getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,width:innerWidth};})()""")
            val point = JSONObject(raw)
            val presented = CountDownLatch(1)
            onMain {
                requireNotNull(page).postVisualStateCallback(SystemClock.uptimeMillis(), object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { presented.countDown() }
                })
            }
            assertTrue("WebView touch target not presented", presented.await(5, TimeUnit.SECONDS))
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                if (action == MotionEvent.ACTION_UP) SystemClock.sleep(80)
                onMain {
                    val view = requireNotNull(page)
                    assertTrue("WebView must be visible and focused", view.isShown && view.hasWindowFocus())
                    val scale = view.width / point.getDouble("width")
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                        (point.getDouble("x") * scale).toFloat(), (point.getDouble("y") * scale).toFloat(), 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                }
            }
        }

        fun awaitBlocked(count: Int) = await("body entered blocked read $count") { transport.blockedReads.get() == count }
        fun awaitReleased() = await("all network reservations and bodies released") {
            resources.reservedBytes == 0L && resources.waitingCount == 0 && transport.sources.all { it.closed.get() }
        }
        fun assertRouteClosed(url: String) = runBlocking { assertNull(gateway.interceptMedia(url, "GET", emptyMap())) }
        fun disposeView() = onMain {
            page?.let { view ->
                (view.parent as? FrameLayout)?.removeView(view)
                HardenedRuntimeWebView.release(view)
            }
            page = null
        }

        fun close() {
            var failure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let { error -> if (failure == null) failure = error else failure?.addSuppressed(error) }
            }
            cleanup { disposeView() }
            cleanup { if (::gateway.isInitialized) gateway.close() }
            cleanup { onMain { permit?.close(); permit = null } }
            cleanup { if (::scenario.isInitialized) scenario.close() }
            cleanup { if (::transport.isInitialized) awaitReleased() }
            cleanup {
                if (::manager.isInitialized) {
                    val result = runBlocking { manager.clearThenRun(runtime.toolId) { Unit } }
                    assertTrue("Runtime media cleanup failed: $result", result is RuntimeDataCleanupExecution.Completed)
                }
            }
            cleanup {
                if (::toolRoot.isInitialized && Files.exists(toolRoot)) Files.walk(toolRoot).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
            failure?.let { throw it }
        }
    }

    private class MediaTransport(val videoBytes: ByteArray, private val audioBytes: ByteArray) : ToolNetworkTransport {
        private val video = MediaData.tailMoov(videoBytes)
        val videoLength: Int get() = video.length
        val requests = CopyOnWriteArrayList<Request>()
        val sources = CopyOnWriteArrayList<RecordingSource>()
        val blockedReads = AtomicInteger()
        val tailRanges = AtomicInteger()
        val tailRangeBytes = AtomicInteger()

        override suspend fun execute(request: Request, timeoutMillis: Long): Response {
            require(request.url.scheme == "https" && request.url.host == "cdn.example.test") { "Unexpected network request: ${request.url}" }
            require(request.method in setOf("GET", "HEAD") && timeoutMillis == 0L)
            val audio = request.url.encodedPath == "/audio.mp3"
            require(request.url.encodedPath in setOf("/video.mp4", "/audio.mp3", "/holding.mp4"))
            val data = when (request.url.encodedPath) {
                "/video.mp4" -> video
                "/audio.mp3" -> MediaData(audioBytes)
                else -> MediaData(videoBytes)
            }
            val range = request.header("Range")?.let { Regex("bytes=([0-9]*)-([0-9]*)").matchEntire(it) ?: error("Unexpected range: $it") }
            val rawStart = range?.groupValues?.get(1)?.takeIf(String::isNotEmpty)?.toInt()
            val rawEnd = range?.groupValues?.get(2)?.takeIf(String::isNotEmpty)?.toInt()
            val start = rawStart ?: if (rawEnd != null) (data.length - rawEnd).coerceAtLeast(0) else 0
            val end = if (rawStart == null && rawEnd != null) data.length - 1 else (rawEnd ?: data.length - 1).coerceAtMost(data.length - 1)
            require(start in 0 until data.length && end in start until data.length)
            val source = RecordingSource(data, start, end, request.url.encodedPath == "/holding.mp4", blockedReads, tailRanges, tailRangeBytes)
            sources += source
            requests += request
            val mime = (if (audio) "audio/mpeg" else "video/mp4").toMediaType()
            val body = object : ResponseBody() {
                private val buffered = source.buffer()
                override fun contentType(): MediaType = mime
                override fun contentLength(): Long = (end - start + 1).toLong()
                override fun source(): BufferedSource = buffered
            }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (range == null) 200 else 206).message(if (range == null) "OK" else "Partial Content")
                .header("Content-Type", mime.toString()).header("Content-Length", (end - start + 1).toString())
                .header("Accept-Ranges", "bytes").apply {
                    if (range != null) header("Content-Range", "bytes $start-$end/${data.length}")
                }.body(body).build()
        }

        fun diagnostics(): String = "requests=${requests.size} rangeRequests=${requests.count { it.header("Range") != null }} " +
            "tailRanges=${tailRanges.get()} tailRangeBytes=${tailRangeBytes.get()} bodies=${sources.size} closed=${sources.count { it.closed.get() }} blockedReads=${blockedReads.get()}"
    }

    /** Retains only the tiny sample and synthesizes the large free box while preserving mdat offsets. */
    private class MediaData(private val prefix: ByteArray, freeBytes: Int = 0, private val tail: ByteArray = ByteArray(0)) {
        private val freeSize = freeBytes
        private val freeHeader = if (freeSize == 0) ByteArray(0) else ByteBuffer.allocate(8).putInt(freeSize).put("free".toByteArray(Charsets.US_ASCII)).array()
        val tailOffset = prefix.size + freeSize
        val length = tailOffset + tail.size

        fun write(sink: Buffer, from: Int, count: Int) {
            var offset = from
            var remaining = count
            while (remaining > 0) {
                val bytes: ByteArray
                val local: Int
                val available: Int
                when {
                    offset < prefix.size -> { bytes = prefix; local = offset; available = prefix.size - offset }
                    offset < prefix.size + freeHeader.size -> { bytes = freeHeader; local = offset - prefix.size; available = freeHeader.size - local }
                    offset < tailOffset -> { bytes = zeros; local = 0; available = minOf(zeros.size, tailOffset - offset) }
                    else -> { bytes = tail; local = offset - tailOffset; available = tail.size - local }
                }
                val size = minOf(remaining, available)
                check(size > 0)
                sink.write(bytes, local, size)
                offset += size
                remaining -= size
            }
        }

        companion object {
            private val zeros = ByteArray(8192)
            fun tailMoov(mp4: ByteArray): MediaData {
                var offset = 0
                while (offset < mp4.size) {
                    val size = ByteBuffer.wrap(mp4, offset, 4).int
                    require(size >= 8 && size <= mp4.size - offset)
                    if (String(mp4, offset + 4, 4, Charsets.US_ASCII) == "moov") {
                        val prefix = mp4.copyOf()
                        "free".toByteArray(Charsets.US_ASCII).copyInto(prefix, offset + 4)
                        prefix.fill(0, offset + 8, offset + size)
                        return MediaData(prefix, 4 * 1024 * 1024, mp4.copyOfRange(offset, offset + size))
                    }
                    offset += size
                }
                error("Self-made MP4 fixture has no moov atom")
            }
        }
    }

    private class RecordingSource(private val bytes: MediaData, private val start: Int, private val end: Int,
        private val holdTail: Boolean, private val blockedReads: AtomicInteger, private val tailRanges: AtomicInteger,
        private val tailRangeBytes: AtomicInteger) : Source {
        val closed = AtomicBoolean()
        private val entered = AtomicBoolean()
        private val tailCounted = AtomicBoolean()
        private val releaseRead = CountDownLatch(1)
        private var offset = 0
        override fun read(sink: Buffer, byteCount: Long): Long {
            require(byteCount >= 0)
            if (byteCount == 0L) return 0
            if (closed.get()) throw IOException("Test body closed")
            if (holdTail && offset >= 64) {
                if (entered.compareAndSet(false, true)) blockedReads.incrementAndGet()
                if (!releaseRead.await(30, TimeUnit.SECONDS)) throw IOException("Test held body was not closed")
                throw IOException("Test held body closed during read")
            }
            if (offset >= end - start + 1) return -1
            val count = minOf(byteCount, (end - start + 1 - offset).toLong(),
                if (holdTail) (64 - offset).toLong() else 8192L).toInt()
            // A real tail range can overtake this gradual initial download; no multi-megabyte buffer is allocated.
            if (!holdTail && bytes.tailOffset > 1024 * 1024 && start + offset in 1663 until bytes.tailOffset) SystemClock.sleep(5)
            bytes.write(sink, start + offset, count)
            if (bytes.tailOffset > 1024 * 1024 && start > 0 && start + offset + count > bytes.tailOffset) {
                tailRangeBytes.addAndGet(minOf(count, start + offset + count - bytes.tailOffset))
                if (tailCounted.compareAndSet(false, true)) tailRanges.incrementAndGet()
            }
            offset += count
            return count.toLong()
        }
        override fun timeout(): Timeout = Timeout.NONE
        override fun close() { if (closed.compareAndSet(false, true)) releaseRead.countDown() }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        error("Timed out waiting for $label")
    }

    private fun <T> onMain(action: () -> T): T {
        val result = CompletableFuture<T>()
        instrumentation.runOnMainSync { runCatching(action).onSuccess(result::complete).onFailure(result::completeExceptionally) }
        return result.get(10, TimeUnit.SECONDS)
    }

    private fun report(message: String) = instrumentation.sendStatus(2, Bundle().apply { putString("stream", "$message\n") })
}
