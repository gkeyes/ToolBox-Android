package io.toolbox.host.browser

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NewSmthLiveWebViewDiagnosticTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetUrl = "https://www.newsmth.net/nForum/article/AutoWorld/1945318701"

    @Test
    fun compareLiveNewSmthInToolBoxWebViewConfiguration() {
        val finished = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        lateinit var page: WebView

        instrumentation.runOnMainSync {
            page = WebView(instrumentation.targetContext).apply {
                BrowserCompatibilityPolicy.apply(settings)
                val profile = BrowserUserAgentPolicy.profile(
                    BrowserUserAgentMode.ChromeMobile,
                    WebSettings.getDefaultUserAgent(instrumentation.targetContext),
                )
                settings.userAgentString = profile.userAgent
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        events += "PAGE_STARTED $url"
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        events += "PAGE_FINISHED $url title=${view.title.orEmpty()}"
                        finished.countDown()
                    }

                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                        events += "SSL_ERROR primary=${error.primaryError} url=${error.url}"
                        handler.cancel()
                        finished.countDown()
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) {
                            events += "MAIN_ERROR code=${error.errorCode} desc=${error.description} url=${request.url}"
                            finished.countDown()
                        } else {
                            val url = request.url.toString()
                            if (interesting(url)) events += "SUB_ERROR code=${error.errorCode} desc=${error.description} url=$url"
                        }
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame || interesting(request.url.toString())) {
                            events += "HTTP_ERROR status=${response.statusCode} mime=${response.mimeType} main=${request.isForMainFrame} url=${request.url}"
                        }
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        val text = message.message().orEmpty()
                        if (
                            message.messageLevel() == ConsoleMessage.MessageLevel.ERROR ||
                            text.contains("mixed", ignoreCase = true) ||
                            text.contains("blocked", ignoreCase = true) ||
                            text.contains("cors", ignoreCase = true)
                        ) {
                            events += "CONSOLE ${message.messageLevel()} $text"
                        }
                        return true
                    }
                }

                loadUrl(targetUrl)
            }
        }

        finished.await(25, TimeUnit.SECONDS)
        Thread.sleep(2500)

        val snapshotLatch = CountDownLatch(1)
        var snapshot = ""
        instrumentation.runOnMainSync {
            val script = """
                (() => JSON.stringify({
                  href: location.href,
                  title: document.title,
                  readyState: document.readyState,
                  bodyTextLength: (document.body?.innerText || '').length,
                  bodyHtmlLength: (document.body?.innerHTML || '').length,
                  scrollHeight: document.documentElement?.scrollHeight || 0,
                  bodyDisplay: document.body ? getComputedStyle(document.body).display : '',
                  bodyVisibility: document.body ? getComputedStyle(document.body).visibility : '',
                  stylesheets: [...document.styleSheets].map(s => s.href || 'inline').slice(0, 30),
                  scripts: [...document.scripts].map(s => s.src || 'inline').slice(0, 30),
                  images: document.images.length,
                  links: document.links.length
                }))()
            """.trimIndent()
            page.evaluateJavascript(script) {
                snapshot = it.orEmpty()
                snapshotLatch.countDown()
            }
        }
        snapshotLatch.await(10, TimeUnit.SECONDS)

        val packageInfo = WebView.getCurrentWebViewPackage()
        println("NEWSMTH_DIAG provider=${packageInfo?.packageName} ${packageInfo?.versionName}")
        println("NEWSMTH_DIAG ua=${page.settings.userAgentString}")
        events.forEach { println("NEWSMTH_DIAG $it") }
        println("NEWSMTH_DIAG snapshot=$snapshot")

        instrumentation.runOnMainSync {
            page.stopLoading()
            page.destroy()
        }

        assertTrue("Diagnostic snapshot was empty; events=$events", snapshot.isNotBlank())
    }

    private fun interesting(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("newsmth") ||
            value.contains("mysmth") ||
            value.endsWith(".css") ||
            value.endsWith(".js") ||
            value.startsWith("http://")
    }
}