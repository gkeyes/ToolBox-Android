package io.toolbox.host.browser

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.host.MainActivity
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real controller, preferences and Android WebView; every document/resource is resolved locally. */
@RunWith(AndroidJUnit4::class)
class BrowserFilterPersistenceTest {
    private lateinit var context: IsolatedFilterContext
    private var browser: FilterTestPage? = null

    @Before
    fun setUp() {
        context = IsolatedFilterContext(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue("Isolated filter preferences could not be cleared", context.preferences.edit().clear().commit())
    }

    @After
    fun tearDown() {
        try {
            browser?.close()
        } finally {
            browser = null
            if (::context.isInitialized) context.preferences.edit().clear().commit()
        }
    }

    @Test
    fun firstManualRuleSavesHidesAndSurvivesRecreationWhileDisableAndDeleteRestoreContent() {
        assertTrue(BrowserFilterStore(context).read().rules.isEmpty())
        var current = openPage()
        current.assertVisible("advert")
        val rule = BrowserFilterRule(site = FixtureHost, value = "#advert")

        // Do not start the picker or install its script from this test. On a clean
        // page, saving the first rule must install its own DOM validation engine.
        assertNull("The first manual rule must save successfully", current.save(rule))
        current.assertHidden("advert")
        current.assertVisible("story")
        assertEquals(rule, BrowserFilterStore(context).read().rules.single())

        current = openPage()
        current.await("new controller reloads the stored manual rule") {
            current.onMain { snapshot.rules.singleOrNull()?.id == rule.id }
        }
        current.assertHidden("advert")
        current.assertVisible("story")

        current.onMain {
            change { state -> state.copy(rules = state.rules.map { it.copy(enabled = false) }) }
        }
        current.assertVisible("advert")
        assertFalse(BrowserFilterStore(context).read().rules.single().enabled)

        current.onMain {
            change { state -> state.copy(rules = state.rules.map { it.copy(enabled = true) }) }
        }
        current.assertHidden("advert")
        current.onMain { delete(rule) }
        current.assertVisible("advert")
        current.assertVisible("story")
        assertTrue("Deletion must reach a new store instance", BrowserFilterStore(context).read().rules.isEmpty())
    }

    @Test
    fun pickerSelectionPreviewAndSavePersistTheActualChosenRegion() {
        var current = openPage()
        current.onMain {
            startPicker()
            // A delayed page callback may run before the first picker install
            // completes. Its ordinary refresh must not cancel this request.
            applyToPage()
        }
        current.await("picker starts on a clean page") { current.onMain { picker.active } }
        current.onMain { applyToPage() }
        assertEquals("true", current.evaluate("!!document.querySelector('[aria-label=\"选择要屏蔽的网页区域\"]')"))
        assertEquals("true", current.evaluate("""
            (() => {
              const advert = document.getElementById('advert');
              const shield = document.querySelector('[aria-label="选择要屏蔽的网页区域"]');
              if (!advert || !shield) return false;
              const rect = advert.getBoundingClientRect();
              const point = {clientX:rect.left + rect.width / 2, clientY:rect.top + rect.height / 2,
                bubbles:true, pointerId:1, pointerType:'touch', isPrimary:true};
              if (rect.width <= 0 || rect.height <= 0 || point.clientX >= innerWidth || point.clientY >= innerHeight) return false;
              shield.dispatchEvent(new PointerEvent('pointerdown', {...point, buttons:1}));
              shield.dispatchEvent(new PointerEvent('pointerup', {...point, buttons:0}));
              return true;
            })()
        """.trimIndent()))
        current.await("controller receives the DOM picker selection") {
            current.onMain {
                poll()
                picker.selector == "#advert" && picker.count == 1 && !picker.preview
            }
        }
        current.onMain { preview() }
        current.await("selected region enters preview") { current.onMain { picker.preview } }
        current.onMain {
            applyToPage()
            poll()
        }
        current.assertHidden("advert")
        current.await("ordinary page refresh keeps the picker preview active") { current.onMain { picker.active && picker.preview } }
        current.assertVisible("story")
        assertTrue("Preview alone must not persist a rule", BrowserFilterStore(context).read().rules.isEmpty())

        current.onMain { savePicker() }
        current.await("picker save completes and closes the selection") {
            current.onMain { !saving && !picker.active && snapshot.rules.size == 1 }
        }
        val saved = BrowserFilterStore(context).read().rules.single()
        assertEquals(FixtureHost, saved.site)
        assertEquals("#advert", saved.value)
        assertEquals(BrowserFilterSource.Picker, saved.source)
        assertEquals(BrowserFilterKind.Cosmetic, saved.kind)

        // A fresh document has no temporary preview stylesheet. Only the saved
        // rule, reloaded through the new controller, can hide this advertisement.
        current = openPage()
        current.assertHidden("advert")
        current.assertVisible("story")
        assertEquals(saved, current.onMain { snapshot.rules.single() })
    }

    @Test
    fun freshControllerBlocksStoredNetworkRuleOnItsFirstPageWithoutBlockingAllowedResources() {
        val rule = BrowserFilterRule(site = FixtureHost, value = AdvertisingHost, kind = BrowserFilterKind.NetworkHost)
        BrowserFilterStore(context).write(BrowserFilterSnapshot(builtIn = false, rules = listOf(rule)))

        // Construction immediately loads the first page; there is no pre-load
        // refresh barrier that could hide the controller's initial-read race.
        val current = openPage(networkResources = true)
        current.await("allowed script executes on the first document") {
            current.evaluate("window.allowedScriptRuns === 1") == "true"
        }
        assertEquals("true", current.evaluate("typeof window.advertScriptRuns === 'undefined'"))
        assertTrue("The production interceptor must block the advertisement", AdvertisingUrl in current.blocked)
        assertFalse("A blocked script must not reach the fixture response", AdvertisingUrl in current.served)
        assertTrue("The page itself must remain loadable", FixtureUrl in current.served)
        assertTrue("The unrelated resource must remain loadable", AllowedUrl in current.served)
        current.await("native blocked count is published") { current.onMain { blockedCount == 1 } }
        assertEquals(rule, current.onMain { snapshot.rules.single() })
        current.assertVisible("story")
    }

    private fun openPage(networkResources: Boolean = false): FilterTestPage {
        browser?.close()
        browser = null
        return FilterTestPage(context, networkResources).also { browser = it; it.awaitLoaded() }
    }

    /** The store asks for applicationContext; keep that lookup inside our test's preferences namespace. */
    private class IsolatedFilterContext(base: Context) : ContextWrapper(base) {
        private val preferencesName = "browser_filter_instrumentation_${UUID.randomUUID()}"
        val preferences: SharedPreferences get() = super.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == "browser_filters_v1") preferences else super.getSharedPreferences(name, mode)
    }

    private class FilterTestPage(context: Context, networkResources: Boolean) : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val loaded = CountDownLatch(1)
        private val failures = CopyOnWriteArrayList<String>()
        private val scenario: ActivityScenario<MainActivity>
        private lateinit var page: WebView
        private lateinit var controller: BrowserFilterController
        val served = CopyOnWriteArrayList<String>()
        val blocked = CopyOnWriteArrayList<String>()

        init {
            // BrowserActivity is in :browser. As in BrowsingTestPage, use a
            // same-process host for an attached, isolated production WebView.
            scenario = ActivityScenario.launch(Intent(instrumentation.targetContext, MainActivity::class.java))
            scenario.onActivity { activity ->
                page = WebView(activity)
                BrowserCompatibilityPolicy.apply(page.settings)
                page.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                controller = BrowserFilterController(context, scope)
                page.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        controller.intercept(view, request)?.let { response ->
                            blocked.add(request.url.toString())
                            return response
                        }
                        val url = request.url.toString()
                        served.add(url)
                        return when (url) {
                            FixtureUrl -> response("text/html", fixtureHtml(networkResources))
                            AdvertisingUrl -> response("application/javascript", "window.advertScriptRuns=(window.advertScriptRuns||0)+1;")
                            AllowedUrl -> response("application/javascript", "window.allowedScriptRuns=(window.allowedScriptRuns||0)+1;")
                            else -> response("text/plain", "")
                        }
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        controller.navigated(url)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        if (url == FixtureUrl) {
                            controller.applyToPage()
                            loaded.countDown()
                        }
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) failures.add("${error.errorCode}: ${error.description}; ${request.url}")
                    }
                }
                activity.setContentView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                page.requestFocus()
                controller.attach(page, FixtureUrl)
                page.loadUrl(FixtureUrl)
            }
        }

        fun awaitLoaded() {
            assertTrue("Filter fixture did not load: $failures", loaded.await(20, TimeUnit.SECONDS))
            await("fixture DOM ready") { evaluate("document.body?.dataset.fixture === 'filters'") == "true" }
        }

        fun <T> onMain(action: BrowserFilterController.() -> T): T {
            val result = AtomicReference<T>()
            instrumentation.runOnMainSync { result.set(controller.action()) }
            return result.get()
        }

        fun save(rule: BrowserFilterRule): String? {
            val completed = CountDownLatch(1)
            val failure = AtomicReference<String?>()
            onMain { save(rule) { failure.set(it); completed.countDown() } }
            assertTrue("Saving filter rule never completed", completed.await(10, TimeUnit.SECONDS))
            return failure.get()
        }

        fun evaluate(source: String): String {
            val completed = CountDownLatch(1)
            val result = AtomicReference("")
            instrumentation.runOnMainSync {
                page.evaluateJavascript(source) { result.set(it.orEmpty()); completed.countDown() }
            }
            assertTrue("JavaScript callback timed out: $failures", completed.await(10, TimeUnit.SECONDS))
            return result.get()
        }

        fun assertHidden(id: String) = await("#$id hidden by the filter") {
            evaluate("getComputedStyle(document.getElementById('$id')).display === 'none'") == "true"
        }

        fun assertVisible(id: String) = await("#$id visible") {
            evaluate("(() => { const e=document.getElementById('$id'); return !!e && getComputedStyle(e).display !== 'none' && e.getBoundingClientRect().height > 0; })()") == "true"
        }

        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (SystemClock.elapsedRealtime() < deadline) {
                assertTrue("$label: $failures", failures.isEmpty())
                if (condition()) return
                SystemClock.sleep(50)
            }
            throw AssertionError("Timed out: $label; notice=${onMain { notice }}; picker=${onMain { picker }}; rules=${onMain { snapshot.rules }}; failures=$failures")
        }

        override fun close() {
            instrumentation.runOnMainSync {
                controller.detach(page)
                scope.cancel()
                (page.parent as? ViewGroup)?.removeView(page)
                page.stopLoading()
                page.destroy()
            }
            scenario.close()
        }

        private fun response(type: String, body: String) = WebResourceResponse(
            type, "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"), body.byteInputStream(),
        )

        private fun fixtureHtml(networkResources: Boolean): String = """
            <!doctype html><html><head>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              body { margin:16px; }
              #advert { width:200px; height:80px; background:#ffdf80; }
              #story { min-height:120px; }
            </style></head><body data-fixture="filters">
            <div id="advert">Advertisement fixture</div>
            <p id="story">Article content must remain readable.</p>
            ${if (networkResources) "<script src='$AdvertisingUrl'></script><script src='$AllowedUrl'></script>" else ""}
            </body></html>
        """.trimIndent()
    }

    private companion object {
        const val FixtureHost = "news.filter-fixture.invalid"
        const val FixtureUrl = "https://$FixtureHost/article"
        const val AdvertisingHost = "ads.filter-fixture.invalid"
        const val AdvertisingUrl = "https://$AdvertisingHost/ad.js"
        const val AllowedUrl = "https://content.filter-fixture.invalid/allowed.js"
    }
}
