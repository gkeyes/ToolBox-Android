package io.toolbox.host.browser

import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NewSmthLiveWebViewDiagnosticTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetUrl = "https://www.newsmth.net/nForum/article/AutoWorld/1945318701"

    @Test
    fun compareLegacyAndCurrentToolBoxBehavior() {
        val defaultUa = WebSettings.getDefaultUserAgent(instrumentation.targetContext)
        val currentChromeUa = BrowserUserAgentPolicy.profile(
            BrowserUserAgentMode.ChromeMobile,
            defaultUa,
        ).userAgent

        val legacy = load("legacy-webview", defaultUa, proceedSsl = true)
        val current = load("current-chrome", currentChromeUa, proceedSsl = false)
        val currentWebViewUa = load("current-webview-ua", defaultUa, proceedSsl = false)

        println("NEWSMTH_AB legacy=$legacy")
        println("NEWSMTH_AB current=$current")
        println("NEWSMTH_AB currentWebViewUa=$currentWebViewUa")

        assertTrue("legacy result empty", legacy.optInt("htmlLength") > 0 || legacy.optBoolean("sslError"))
        assertTrue("current result empty", current.optInt("htmlLength") > 0 || current.optBoolean("sslError"))
    }

    private fun load(label: String, userAgent: String, proceedSsl: Boolean): JSONObject {
        lateinit var page: WebView
        val done = CountDownLatch(1)
        var sslError = false
        var sslPrimary = -1

        instrumentation.runOnMainSync {
            page = WebView(instrumentation.targetContext).apply {
                BrowserCompatibilityPolicy.apply(settings)
                settings.userAgentString = userAgent
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        done.countDown()
                    }
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                        sslError = true
                        sslPrimary = error.primaryError
                        if (proceedSsl) handler.proceed() else {
                            handler.cancel()
                            done.countDown()
                        }
                    }
                }
                loadUrl(targetUrl)
            }
        }

        done.await(25, TimeUnit.SECONDS)
        Thread.sleep(1200)

        val snapshotLatch = CountDownLatch(1)
        var raw = ""
        instrumentation.runOnMainSync {
            page.evaluateJavascript(
                """
                (() => JSON.stringify({
                  href: location.href,
                  title: document.title,
                  htmlLength: (document.body?.innerHTML || '').length,
                  textLength: (document.body?.innerText || '').length,
                  challenge: Boolean(document.querySelector('.guest-read-challenge')),
                  captcha: Boolean(document.querySelector('img[src*="/nForum/authimg"]')),
                  input: Boolean(document.querySelector('input[name="auth"]')),
                  bodyHeight: Math.round(document.body?.getBoundingClientRect().height || 0)
                }))()
                """.trimIndent(),
            ) {
                raw = it.orEmpty()
                snapshotLatch.countDown()
            }
        }
        snapshotLatch.await(10, TimeUnit.SECONDS)

        val parsed = runCatching {
            val outer = if (raw.startsWith("\"")) org.json.JSONTokener(raw).nextValue() as String else raw
            JSONObject(outer)
        }.getOrElse { JSONObject() }
        parsed.put("label", label)
        parsed.put("sslError", sslError)
        parsed.put("sslPrimary", sslPrimary)
        parsed.put("ua", userAgent)

        instrumentation.runOnMainSync {
            page.stopLoading()
            page.destroy()
        }
        return parsed
    }
}