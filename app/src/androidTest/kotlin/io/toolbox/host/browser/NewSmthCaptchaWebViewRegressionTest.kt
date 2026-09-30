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
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NewSmthCaptchaWebViewRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var page: WebView

    @Before
    fun setUp() {
        onMain {
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
                    override fun onReceivedSslError(
                        view: WebView,
                        handler: SslErrorHandler,
                        error: SslError,
                    ) {
                        handler.proceed()
                    }
                }
            }
        }
    }

    @After
    fun tearDown() {
        if (::page.isInitialized) {
            onMain {
                page.stopLoading()
                page.loadUrl("about:blank")
                page.destroy()
            }
        }
    }

    @Test
    fun liveGuestReadChallengeRendersInAndroidSystemWebView() {
        val url = "https://www.newsmth.net/nForum/article/AutoWorld/1945318701"
        onMain { page.loadUrl(url) }

        val snapshot = awaitChallenge()
        assertTrue("Unexpected final URL: $snapshot", snapshot.getString("href").contains("newsmth.net"))
        assertTrue("Captcha container missing: $snapshot", snapshot.getBoolean("challenge"))
        assertTrue("Captcha image missing: $snapshot", snapshot.getString("image").contains("/nForum/authimg"))
        assertTrue("Captcha input missing: $snapshot", snapshot.getBoolean("input"))
        assertTrue("Captcha form missing: $snapshot", snapshot.getString("form").contains("/nForum/article/"))
    }

    private fun awaitChallenge(): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        var last = JSONObject()
        while (System.nanoTime() < deadline) {
            val raw = evaluate(
                """
                (() => JSON.stringify({
                  href: location.href,
                  ready: document.readyState,
                  challenge: Boolean(document.querySelector('.guest-read-challenge')),
                  image: document.querySelector('.guest-read-challenge img')?.getAttribute('src') || '',
                  input: Boolean(document.querySelector('.guest-read-challenge input[name="auth"]')),
                  form: document.querySelector('.guest-read-challenge form')?.getAttribute('action') || '',
                  textLength: (document.body?.innerText || '').length,
                  htmlLength: (document.body?.innerHTML || '').length
                }))()
                """.trimIndent(),
            )
            if (raw.isNotBlank() && raw != "null") {
                last = JSONObject(raw)
                if (last.optBoolean("challenge")) return last
            }
            Thread.sleep(200)
        }
        error("Timed out waiting for NewSMTH captcha: $last")
    }

    private fun evaluate(script: String): String {
        val result = CompletableFuture<String>()
        onMain {
            page.evaluateJavascript(script) {
                val decoded = runCatching {
                    if (it != null && it.startsWith('"')) org.json.JSONTokener(it).nextValue() as? String else it
                }.getOrNull()
                result.complete(decoded.orEmpty())
            }
        }
        return result.get(5, TimeUnit.SECONDS)
    }

    private fun <T> onMain(action: () -> T): T {
        val result = CompletableFuture<T>()
        instrumentation.runOnMainSync {
            runCatching(action)
                .onSuccess(result::complete)
                .onFailure(result::completeExceptionally)
        }
        return result.get(10, TimeUnit.SECONDS)
    }
}
