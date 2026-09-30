package io.toolbox.host.browser

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.tool.runtime.validateRuntimeBrowserUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class BrowserNavigationInstrumentationTest {
    @Test
    fun validatedLinksReachWebViewWithoutHostOrSchemeRewriting() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (url in listOf(
            "http://www.newsmth.net/nForum/article/Memory/187055",
            "https://www.newsmth.net/nForum/article/Memory/187055",
            "http://m.newsmth.net/a/%E4%B8%AD%E6%96%87?p=%2Fraw",
            "http://example.com/legacy?p=%2Fraw",
        )) {
            val received = AtomicReference<String>()
            val requested = CountDownLatch(1)
            var page: WebView? = null
            try {
                instrumentation.runOnMainSync {
                    val browser = WebView(instrumentation.targetContext)
                    page = browser
                    BrowserCompatibilityPolicy.apply(browser.settings)
                    browser.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse {
                            if (request.isForMainFrame) {
                                received.set(request.url.toString())
                                requested.countDown()
                            }
                            // Resolve locally so this regression never depends on a live site.
                            return WebResourceResponse(
                                "text/html", "UTF-8",
                                "<html><body>Local fixture</body></html>".byteInputStream(),
                            )
                        }
                    }
                    browser.loadUrl(validateRuntimeBrowserUrl(url))
                }
                assertTrue("No main-frame request for $url", requested.await(20, TimeUnit.SECONDS))
                assertEquals(url, received.get())
            } finally {
                instrumentation.runOnMainSync {
                    page?.let {
                        it.stopLoading()
                        it.destroy()
                    }
                }
            }
        }
    }
}
