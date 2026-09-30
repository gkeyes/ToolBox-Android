package io.toolbox.host.browser

import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
        val bounds = JSONObject(evaluate("(() => {const e=document.querySelector(${JSONObject.quote(selector)});e.scrollIntoView({block:'center'});const r=e.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,width:innerWidth};})()"))
        instrumentation.runOnMainSync {
            val scale = page.width.toFloat() / bounds.getDouble("width").toFloat()
            val x = bounds.getDouble("x").toFloat() * scale
            val y = bounds.getDouble("y").toFloat() * scale
            val down = SystemClock.uptimeMillis()
            for ((action, time) in listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 60)) {
                MotionEvent.obtain(down, time, action, x, y, 0).also { event -> page.dispatchTouchEvent(event); event.recycle() }
            }
        }
    }

    fun await(label: String, timeoutSeconds: Long = 20, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutSeconds * 1000
        while (SystemClock.elapsedRealtime() < deadline) {
            assertTrue("$label: $failures", failures.isEmpty())
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out: $label; url=${currentUrl()}; failures=$failures; notifications=$notifications; DOM=${evaluate("document.body?.innerText?.slice(0,300)")}")
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
