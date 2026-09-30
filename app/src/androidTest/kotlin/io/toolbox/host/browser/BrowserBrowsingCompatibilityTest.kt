package io.toolbox.host.browser

import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.host.MainActivity
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

/** Real network, DOM, touch and POST behavior with the production navigation decision boundary. */
@RunWith(AndroidJUnit4::class)
class BrowserBrowsingCompatibilityTest {
    private lateinit var server: BrowsingFixtureServer
    private lateinit var browser: BrowsingTestPage

    @Before fun setUp() {
        server = BrowsingFixtureServer()
        browser = BrowsingTestPage()
    }

    @After fun tearDown() {
        if (::browser.isInitialized) browser.close()
        if (::server.isInitialized) server.close()
    }

    @Test fun originalAddressAndCookieBackedVerificationPostStayInWebView() {
        val original = server.url("/challenge?raw=%2Fkeep")
        browser.load(original)
        browser.await("verification image downloaded") {
            browser.evaluate("!!document.querySelector('#authimg')?.complete && document.querySelector('#authimg').naturalWidth === 2") == "true"
        }
        assertEquals(original, browser.currentUrl())
        assertTrue(server.requests.any { it.path == "/authimg" && it.cookie.contains("fixtureSession=present") })
        browser.evaluate("document.querySelector('[name=auth]').value='fixture-code'")
        browser.tap("#submit")
        browser.await("cookie-authenticated form POST") {
            browser.evaluate("document.body?.dataset.accepted === 'yes'") == "true"
        }
        val posted = server.requests.first { it.method == "POST" }
        assertEquals("/challenge?raw=%2Fkeep", posted.path)
        assertEquals("auth=fixture-code", posted.body)
        assertTrue(posted.cookie.contains("fixtureSession=present"))
        assertEquals(original, browser.currentUrl())
        assertTrue(browser.notifications.toString(), browser.notifications.isEmpty())
    }

    @Test fun touchedBlankTargetNavigatesWithoutHostRewriting() {
        browser.load(server.url("/links"))
        browser.await("blank target link") { browser.evaluate("!!document.querySelector('#blank')") == "true" }
        browser.tap("#blank")
        browser.await("target blank destination") { browser.currentUrl() == server.url("/destination?raw=%2Fkeep") }
        assertTrue(server.requests.any { it.path == "/destination?raw=%2Fkeep" })
        assertTrue("Navigation callback must delegate to production controller", browser.decisions.any { it.first == server.url("/destination?raw=%2Fkeep") && !it.second })
        assertTrue(browser.notifications.toString(), browser.notifications.isEmpty())
    }

    @Test fun dataFrameBlobDocumentAndAboutBlankRemainEngineOwned() {
        browser.load(server.url("/links"))
        browser.await("data iframe script executed") { browser.evaluate("document.body?.dataset.dataReady === 'yes'") == "true" }
        browser.evaluate("location.href=URL.createObjectURL(new Blob(['<body data-blob-ready=\"yes\">Blob document</body>'],{type:'text/html'}))")
        browser.await("blob document navigation") { browser.evaluate("document.body?.dataset.blobReady === 'yes'") == "true" }
        assertTrue(browser.currentUrl().startsWith("blob:"))
        browser.evaluate("location.href='about:blank'")
        browser.await("about blank navigation") { browser.currentUrl() == "about:blank" }
        assertTrue(browser.notifications.toString(), browser.notifications.isEmpty())
        assertTrue("No internal document should be blocked", browser.decisions.none { (url, blocked) -> blocked && (url.startsWith("blob:") || url.startsWith("data:") || url == "about:blank") })
    }
}

/** Attached isolated WebView: real compositor/gesture behavior, with no runtime tool bridge. */
internal class BrowsingTestPage : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val scenario: ActivityScenario<MainActivity>
    private lateinit var page: WebView
    private lateinit var controller: BrowserNavigationController
    val failures = CopyOnWriteArrayList<String>()
    val notifications = CopyOnWriteArrayList<String>()
    val decisions = CopyOnWriteArrayList<Pair<String, Boolean>>()
    private val consoleErrors = CopyOnWriteArrayList<String>()
    private var touchGeometry = "No touch attempted"

    init {
        // BrowserActivity lives in :browser. Use a same-process host so ActivityScenario can
        // attach the isolated production-configured WebView and deliver genuine touch events.
        scenario = ActivityScenario.launch(Intent(instrumentation.targetContext, MainActivity::class.java))
        scenario.onActivity { activity ->
            page = WebView(activity)
            BrowserCompatibilityPolicy.apply(page.settings)
            BrowserCompatibilityPolicy.applyCompat(page.settings)
            page.settings.userAgentString = BrowserUserAgentPolicy.chromeMobile(android.webkit.WebSettings.getDefaultUserAgent(activity))
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(page, true)
            controller = BrowserNavigationController(activity, { it === page }, { view, url -> view.loadUrl(url) }, { notifications.add(it) })
            page.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    controller.handle(view, request).also { decisions.add(request.url.toString() to it) }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) failures.add("Main-frame error ${error.errorCode}: ${error.description}; ${request.url}")
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    failures.add("TLS rejected primaryError=${error.primaryError}; ${error.url}")
                    handler.cancel()
                }
            }
            page.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    BrowserMediaDiagnostics.consoleEvent(
                        message.messageLevel().name, message.message(), message.sourceId(), message.lineNumber(),
                    )?.let {
                        if (consoleErrors.size < 20) consoleErrors.add(it)
                        Log.i("BrowserCompatibilityTest", it)
                    }
                    return true
                }
            }
            activity.setContentView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            page.requestFocus()
        }
    }

    fun load(url: String) = instrumentation.runOnMainSync { page.loadUrl(url) }

    fun currentUrl(): String {
        var result = ""
        instrumentation.runOnMainSync { result = page.url.orEmpty() }
        return result
    }

    fun evaluate(source: String): String {
        val latch = CountDownLatch(1)
        var value = ""
        instrumentation.runOnMainSync { page.evaluateJavascript(source) { value = it.orEmpty(); latch.countDown() } }
        assertTrue("JavaScript callback timed out; $failures", latch.await(10, TimeUnit.SECONDS))
        return value
    }

    fun tap(selector: String) {
        val quotedSelector = JSONObject.quote(selector)
        evaluate("document.querySelector($quotedSelector).scrollIntoView({block:'center',inline:'center',behavior:'instant'})")
        // DOM readiness does not imply that CSS entrance/scrolling has reached the compositor.
        // Read geometry on separate frames and require that the visible point hits this control.
        val inspect = """(() => {
            const e=document.querySelector($quotedSelector), r=e.getBoundingClientRect(), v=visualViewport;
            const left=Math.max(r.left,v?.offsetLeft||0), top=Math.max(r.top,v?.offsetTop||0);
            const right=Math.min(r.right,(v?.offsetLeft||0)+(v?.width||innerWidth));
            const bottom=Math.min(r.bottom,(v?.offsetTop||0)+(v?.height||innerHeight));
            const x=(left+right)/2, y=(top+bottom)/2, target=document.elementFromPoint(x,y);
            let animating=false;
            for(let n=e;n;n=n.parentElement) {
                if(n.getAnimations().some(a=>a.playState==='running' && a.effect?.getTiming().iterations!==Infinity)) animating=true;
            }
            return {x,y,width:innerWidth,height:innerHeight,visualWidth:v?.width||innerWidth,
                offsetX:v?.offsetLeft||0,offsetY:v?.offsetTop||0,
                left:r.left,top:r.top,right:r.right,bottom:r.bottom,
                hit:right>left && bottom>top && !!target && (target===e || e.contains(target)),
                target:target?.tagName+'#'+(target?.id||'')+'.'+(target?.className||''),animating};
        })()"""
        var previous: JSONObject? = null
        var bounds: JSONObject? = null
        var stable = 0
        await("stable visible touch target $selector") {
            val current = JSONObject(evaluate(inspect))
            touchGeometry = current.toString()
            val old = previous
            stable = if (current.getBoolean("hit") && !current.getBoolean("animating") && old != null &&
                listOf("x", "y", "width", "height", "left", "top", "right", "bottom").all { abs(current.getDouble(it) - old.getDouble(it)) < 0.5 }) stable + 1 else 0
            previous = current
            bounds = current
            stable >= 2
        }
        val frameReady = CountDownLatch(1)
        instrumentation.runOnMainSync {
            page.postVisualStateCallback(SystemClock.uptimeMillis(), object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { frameReady.countDown() }
            })
        }
        assertTrue("WebView did not present touch target; $touchGeometry", frameReady.await(10, TimeUnit.SECONDS))
        val finalBounds = JSONObject(evaluate(inspect))
        assertTrue("Target moved before real touch: $finalBounds", finalBounds.getBoolean("hit") &&
            abs(finalBounds.getDouble("x") - requireNotNull(bounds).getDouble("x")) < 1 &&
            abs(finalBounds.getDouble("y") - requireNotNull(bounds).getDouble("y")) < 1)
        evaluate("""(() => {
            window.__browserTestTouch=[];
            for(const type of ['pointerdown','pointerup','click']) document.addEventListener(type,e=>{
                window.__browserTestTouch.push({type:e.type,trusted:e.isTrusted,x:e.clientX,y:e.clientY,
                    target:e.target.tagName+'#'+(e.target.id||''),matches:!!e.target.closest($quotedSelector)});
            },{capture:true,passive:true});
        })()""")
        var x = 0f
        var y = 0f
        instrumentation.runOnMainSync {
            assertTrue("WebView must be visible and focused", page.isShown && page.hasWindowFocus() && page.width > 0 && page.height > 0)
            val scale = page.width.toFloat() / finalBounds.getDouble("visualWidth").toFloat()
            x = (finalBounds.getDouble("x") - finalBounds.getDouble("offsetX")).toFloat() * scale
            y = (finalBounds.getDouble("y") - finalBounds.getDouble("offsetY")).toFloat() * scale
            touchGeometry = "$finalBounds; WebView=${page.width}x${page.height}; nativePoint=($x,$y)"
            Log.i("BrowserCompatibilityTest", "Touch $selector: $touchGeometry")
        }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            if (action == MotionEvent.ACTION_UP) SystemClock.sleep(80)
            instrumentation.runOnMainSync {
                MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).also { event ->
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    page.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
        }
        await("trusted click delivered to $selector") {
            evaluate("window.__browserTestTouch?.some(e=>e.type==='click' && e.trusted && e.matches) === true") == "true" ||
                // A successful navigation replaces the old document and its event diagnostics.
                evaluate("typeof window.__browserTestTouch === 'undefined'") == "true"
        }
    }

    fun await(label: String, timeoutSeconds: Long = 20, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutSeconds * 1000
        while (SystemClock.elapsedRealtime() < deadline) {
            assertTrue("$label: $failures", failures.isEmpty())
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out: $label; url=${currentUrl()}; failures=$failures; notifications=$notifications; console=$consoleErrors; touch=$touchGeometry; events=${evaluate("window.__browserTestTouch")}; DOM=${evaluate("document.body?.innerText?.slice(0,300)")}")
    }

    override fun close() {
        instrumentation.runOnMainSync {
            controller.cancelPage(page)
            (page.parent as? ViewGroup)?.removeView(page)
            page.stopLoading()
            page.destroy()
        }
        scenario.close()
    }
}

/** Tiny loopback server keeps POST bodies/cookies observable without intercepting WebView requests. */
internal class BrowsingFixtureServer : AutoCloseable {
    data class Request(val method: String, val path: String, val cookie: String, val body: String)
    val requests = CopyOnWriteArrayList<Request>()
    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val clients = CopyOnWriteArrayList<Socket>()
    private val handlers = Executors.newCachedThreadPool()
    @Volatile private var closed = false
    private val image = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(android.graphics.Color.BLUE)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
        }
        output.toByteArray()
    }
    private val worker = thread(name = "browser-fixture", isDaemon = true) {
        while (!closed) {
            try {
                val accepted = socket.accept()
                clients.add(accepted)
                handlers.execute {
                    try {
                        accepted.use { client ->
                            client.soTimeout = 10_000
                            val reader = client.getInputStream().bufferedReader(Charsets.UTF_8)
                            val first = reader.readLine()?.split(' ') ?: return@use
                            if (first.size < 2) return@use
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                            }
                            val body = CharArray(headers["content-length"]?.toIntOrNull() ?: 0)
                            var offset = 0
                            while (offset < body.size) {
                                val count = reader.read(body, offset, body.size - offset)
                                if (count < 0) break
                                offset += count
                            }
                            val request = Request(first[0], first[1], headers["cookie"].orEmpty(), String(body))
                            requests.add(request)
                            val path = request.path.substringBefore('?')
                            val html = when (path) {
                                "/challenge" -> if (request.method == "POST") {
                                    "<body data-accepted='${if (request.cookie.contains("fixtureSession=present") && request.body == "auth=fixture-code") "yes" else "no"}'>POST result</body>"
                                } else "<img id='authimg' src='/authimg'><form method='post' action='/challenge?raw=%2Fkeep'><input name='auth'><button id='submit' style='width:140px;height:60px'>Continue</button></form>"
                                "/links" -> """<a id='blank' target='_blank' href='/destination?raw=%2Fkeep' style='display:block;width:180px;height:80px'>Open page</a>
                                    <script>addEventListener('message',e=>{if(e.data==='data-ready')document.body.dataset.dataReady='yes'});</script>
                                    <iframe src="data:text/html,%3Cscript%3Eparent.postMessage('data-ready','*')%3C%2Fscript%3E"></iframe>"""
                                else -> "<body>Local browser fixture</body>"
                            }
                            val bytes = if (path == "/authimg") image else ("<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'>" + html).toByteArray()
                            val type = if (path == "/authimg") "image/png" else "text/html; charset=UTF-8"
                            val cookie = if (path == "/challenge" && request.method == "GET") "Set-Cookie: fixtureSession=present; Path=/; HttpOnly\r\n" else ""
                            client.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\n${cookie}Connection: close\r\n\r\n".toByteArray())
                                write(bytes)
                                flush()
                            }
                        }
                    } catch (_: java.io.IOException) {
                        // Chromium may abandon speculative connections or stop a page at teardown.
                    } finally { clients.remove(accepted) }
                }
            } catch (error: Exception) {
                if (!closed) throw error
            }
        }
    }

    fun url(path: String) = "http://127.0.0.1:${socket.localPort}$path"
    override fun close() {
        closed = true
        socket.close()
        clients.forEach { runCatching { it.close() } }
        handlers.shutdownNow()
        worker.join(1000)
    }
}
