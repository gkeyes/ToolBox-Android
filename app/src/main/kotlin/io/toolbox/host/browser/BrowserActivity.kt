package io.toolbox.host.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewRenderProcessClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.host.runtime.browserViewIntent
import io.toolbox.host.ui.applyHyperOsGestureNavigationImmersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Ordinary web content: no asset loader, tool profile, page-to-native bridge or native RPC.
 * Local content-filter scripts only manipulate the current webpage DOM.
 *
 * Wiring only: UI state lives in [BrowserUiState], WebView setup and clients in [BrowserWebViewFactory],
 * and the Compose UI in [BrowserScreen] and its toolbar/menu files. */
class BrowserActivity : ComponentActivity() {
    private val state = BrowserUiState()
    private val filters by lazy { BrowserFilterController(this, lifecycleScope) }
    private val mediaDiagnosticsScript by lazy {
        applicationContext.assets.open("browser/media-diagnostics.js").bufferedReader().use { it.readText() }
    }
    private val mediaLayoutCompatScript by lazy {
        applicationContext.assets.open("browser/media-layout-compat.js").bufferedReader().use { it.readText() }
    }
    private val ssl = BrowserSslController { state.webView }
    private val capabilities = BrowserCapabilityController(this) { it === state.webView }
    private val navigation = BrowserNavigationController(
        context = this,
        isCurrentPage = { it === state.webView },
        navigate = ::navigatePage,
        notify = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() },
    )
    private val hasBrowserPrompt get() = ssl.hasPrompt || capabilities.hasPrompt || navigation.hasPrompt
    private val browserPreferences by lazy { getSharedPreferences(BROWSER_PREFERENCES, MODE_PRIVATE) }

    private val pageHost = object : BrowserPageHost {
        override val closing: Boolean get() = isFinishing || isDestroyed
        override fun prepareNavigation(page: WebView, url: String?) = this@BrowserActivity.prepareNavigation(page, url)
        override fun updateNavigation(page: WebView) = this@BrowserActivity.updateNavigation(page)
        override fun applyMediaLayoutCompatibility(page: WebView) = this@BrowserActivity.applyMediaLayoutCompatibility(page)
        override fun flushCookies() = this@BrowserActivity.flushCookies()
        override fun collectMediaDiagnostics(page: WebView, epoch: Int) = this@BrowserActivity.collectMediaDiagnostics(page, epoch)
        override fun recordMediaDiagnosticEvent(event: String) = this@BrowserActivity.recordMediaDiagnosticEvent(event)
        override fun hideFullScreen() = this@BrowserActivity.hideFullScreen()
        override fun destroyPage(page: WebView) = this@BrowserActivity.destroyPage(page)
        override fun createPage() = this@BrowserActivity.createPage()
        override fun postToWindow(action: () -> Unit) { window.decorView.post(action) }
        override fun setFullscreenSystemBars(hidden: Boolean) = this@BrowserActivity.setFullscreenSystemBars(hidden)
        override fun onPageTouch(page: WebView, event: MotionEvent) = this@BrowserActivity.updateBrowserChromeForTouch(page, event)
        override fun browserChromeTopThresholdPx(): Int = this@BrowserActivity.browserChromeTopThresholdPx()
    }

    private val screenActions = object : BrowserScreenActions {
        override fun close() = finish()
        override fun reload() = this@BrowserActivity.reload()
        override fun performLoadAction() = this@BrowserActivity.performLoadAction()
        override fun traverseHistory(forward: Boolean) = this@BrowserActivity.traverseHistory(forward)
        override fun share() = this@BrowserActivity.share()
        override fun openSystemBrowser() = this@BrowserActivity.openSystemBrowser()
        override fun copyAddress() = this@BrowserActivity.copyAddress()
        override fun startMediaDiagnostics() = this@BrowserActivity.startMediaDiagnostics()
        override fun copyMediaDiagnostics() = this@BrowserActivity.copyMediaDiagnostics()
        override fun changeUserAgentMode(mode: BrowserUserAgentMode) = this@BrowserActivity.changeUserAgentMode(mode)
        override fun clearWebsiteData() = this@BrowserActivity.clearWebsiteData()
        override fun restartUnresponsivePage() = this@BrowserActivity.restartUnresponsivePage()
        override fun applySystemBarAppearance(light: Boolean) {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }

    private val webViewFactory by lazy {
        BrowserWebViewFactory(this, state, filters, ssl, capabilities, navigation, pageHost)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state.address = validBrowserUrl(savedInstanceState?.getString("address") ?: intent.dataString.orEmpty()) ?: run {
            finish()
            return
        }
        state.userAgentMode = BrowserUserAgentMode.fromStored(
            browserPreferences.getString(USER_AGENT_MODE_KEY, null),
        )
        enableEdgeToEdge()
        applyHyperOsGestureNavigationImmersion()
        createPage(savedInstanceState?.getBundle("page"))
        val appearance = BrowserAppearance.fromIntent(intent)
        setContent {
            ToolBoxTheme(
                mode = appearance.themeMode,
                style = appearance.themeStyle,
                reduceTransparency = appearance.reduceTransparency,
            ) {
                BackHandler { goBack() }
                BrowserScreen(
                    state = state,
                    filters = filters,
                    ssl = ssl,
                    capabilities = capabilities,
                    navigation = navigation,
                    hasBrowserPrompt = { hasBrowserPrompt },
                    actions = screenActions,
                )
            }
        }
    }

    private fun createPage(savedState: Bundle? = null) {
        if (isFinishing || isDestroyed || state.clearing) return
        state.interaction = BrowserInteractionState()
        state.unresponsiveRenderer = null
        try {
            val page = webViewFactory.create()
            state.webView = page
            prepareNavigation(page)
            filters.attach(page, state.address)
            webViewFactory.configure(page)
            if (savedState == null || page.restoreState(savedState) == null) {
                state.interaction = state.interaction.pageStarted()
                navigatePage(page, state.address)
            } else {
                validBrowserUrl(page.url.orEmpty())?.let { state.address = it }
                state.title = page.title.orEmpty()
                state.loadProgress = 100
                updateNavigation(page)
                page.post { if (page === state.webView) filters.applyToPage() }
            }
        } catch (_: android.util.AndroidRuntimeException) {
            state.webView?.let(::destroyPage)
            state.interaction = BrowserInteractionState()
            state.error = "Android System WebView 不可用，请启用或更新系统 WebView 后重试。"
        }
    }

    private fun browserChromeLocked(): Boolean {
        val imeVisible = ViewCompat.getRootWindowInsets(window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        return hasBrowserPrompt || imeVisible || state.fullScreenView != null || state.menu || state.mediaDiagnosticsSheet || state.userAgentSheet ||
            state.fullAddress || state.clearConfirmation || filters.sheet || filters.picker.active ||
            state.interaction.showRecoveryPrompt || state.error != null
    }

    private fun browserChromeTopThresholdPx(): Int =
        (8f * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun updateBrowserChromeForTouch(page: WebView, event: MotionEvent) {
        state.chrome.onTouch(
            locked = browserChromeLocked(),
            scrollY = page.scrollY,
            topThresholdPx = browserChromeTopThresholdPx(),
            pointerCount = event.pointerCount,
            action = event.actionMasked,
            rawY = event.rawY,
            density = resources.displayMetrics.density,
        )
    }

    private fun changeUserAgentMode(mode: BrowserUserAgentMode) {
        state.userAgentSheet = false
        if (mode == state.userAgentMode || state.clearing || state.interaction.restarting) return

        val currentAddress = validBrowserUrl(state.webView?.url.orEmpty()) ?: state.address
        state.userAgentMode = mode
        browserPreferences.edit().putString(USER_AGENT_MODE_KEY, mode.storageValue).apply()

        hideFullScreen()
        filters.stopPicker()
        state.webView?.let { page ->
            page.stopLoading()
            destroyPage(page)
        }
        state.address = currentAddress
        state.title = ""
        state.loadProgress = 0
        state.canBack = false
        state.canForward = false
        state.error = null
        state.interaction = BrowserInteractionState()
        createPage()
    }

    private fun recordMediaDiagnosticEvent(event: String) {
        while (state.mediaDiagnosticEvents.size >= MAX_MEDIA_DIAGNOSTIC_EVENTS) {
            state.mediaDiagnosticEvents.removeFirst()
        }
        state.mediaDiagnosticEvents.addLast(event)
    }

    private fun startMediaDiagnostics() {
        if (state.clearing || state.interaction.restarting || state.interaction.unresponsive) return
        state.mediaDiagnosticsSheet = true
        state.mediaDiagnosticsRunning = true
        state.mediaDiagnosticsCapture = true
        state.mediaDiagnosticsEpoch++
        state.mediaDiagnosticEvents.clear()
        state.mediaDiagnosticsReport = "正在重新加载网页并捕获媒体错误…"
        filters.stopPicker()
        state.error = null
        state.loadProgress = 0
        state.interaction = state.interaction.pageStarted()
        val page = state.webView
        if (page == null) createPage() else {
            prepareNavigation(page, page.url)
            page.reload()
        }
    }

    private fun collectMediaDiagnostics(page: WebView, epoch: Int) {
        if (!state.mediaDiagnosticsCapture || epoch != state.mediaDiagnosticsEpoch || page !== state.webView) return
        state.mediaDiagnosticsCapture = false
        val expression = "($mediaDiagnosticsScript)()"
        page.evaluateJavascript(expression) { raw ->
            if (epoch != state.mediaDiagnosticsEpoch || page !== state.webView) return@evaluateJavascript
            val encoded = raw?.trim()?.removeSurrounding("\"").orEmpty()
            val domReport = runCatching {
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrElse { "DOM 诊断脚本没有返回有效结果：${it.javaClass.simpleName}" }
            val provider = WebView.getCurrentWebViewPackage()
            state.mediaDiagnosticsReport = BrowserMediaDiagnostics.buildReport(
                domReport = domReport,
                events = state.mediaDiagnosticEvents.toList(),
                webViewProvider = listOfNotNull(provider?.packageName, provider?.versionName).joinToString(" "),
                userAgentMode = state.userAgentMode.shortLabel,
                mixedContentMode = page.settings.mixedContentMode,
            )
            state.mediaDiagnosticsRunning = false
        }
    }

    private fun copyMediaDiagnostics() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("ToolBox 媒体诊断", state.mediaDiagnosticsReport))
        if (android.os.Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "诊断报告已复制", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyMediaLayoutCompatibility(page: WebView) {
        if (page !== state.webView) return
        page.evaluateJavascript("($mediaLayoutCompatScript)()", null)
    }

    private fun updateNavigation(page: WebView) {
        if (page !== state.webView) return
        state.canBack = page.canGoBack()
        state.canForward = page.canGoForward()
    }

    private fun prepareNavigation(page: WebView, url: String? = null) {
        capabilities.cancelPage(page)
        navigation.cancelPage(page)
        ssl.onNavigationRequested(page, url)
    }

    private fun navigatePage(page: WebView, url: String) {
        if (page !== state.webView) return
        prepareNavigation(page, url)
        page.loadUrl(url)
    }

    private fun traverseHistory(forward: Boolean) {
        state.webView?.let { page ->
            prepareNavigation(page)
            if (forward) page.goForward() else page.goBack()
        }
    }

    private fun cancelPageRequests(page: WebView) {
        capabilities.cancelPage(page)
        navigation.cancelPage(page)
        ssl.onDestroyPage(page)
    }

    private fun reload() {
        filters.stopPicker()
        when (state.interaction.loadAction(state.clearing)) {
            BrowserLoadAction.Disabled -> return
            BrowserLoadAction.Recover -> { state.interaction = state.interaction.requestRecoveryPrompt(); return }
            else -> Unit
        }
        state.error = null
        state.loadProgress = 0
        state.interaction = state.interaction.pageStarted()
        if (state.webView == null) createPage() else state.webView?.let { page ->
            prepareNavigation(page, page.url)
            page.reload()
        }
    }

    private fun performLoadAction() {
        when (state.interaction.loadAction(state.clearing)) {
            BrowserLoadAction.Disabled -> Unit
            BrowserLoadAction.Stop -> {
                // Set this before stopLoading: an interrupted request must not become an error overlay.
                state.interaction = state.interaction.stopRequested()
                state.webView?.let { page ->
                    cancelPageRequests(page)
                    page.stopLoading()
                }
                state.loadProgress = 100
            }
            BrowserLoadAction.Recover -> { state.interaction = state.interaction.requestRecoveryPrompt() }
            BrowserLoadAction.Reload -> reload()
        }
    }

    private fun restartUnresponsivePage() {
        if (state.clearing || state.interaction.restarting || !state.interaction.unresponsive) return
        val renderer = state.unresponsiveRenderer
        state.interaction = state.interaction.restartRequested()
        // Termination can affect other browser pages sharing this renderer. The confirmation says so.
        // Tool runtimes live in a separate process/profile and are never passed to this code.
        val requested = runCatching { renderer?.terminate() == true }.getOrDefault(false)
        if (!requested) {
            state.interaction = state.interaction.restartFailed()
            state.error = "网页进程暂时无法重新启动，请使用系统浏览器，或关闭后重试。"
            state.loadProgress = 100
        }
        // On success, onRenderProcessGone disposes the dead WebView before creating its replacement.
    }

    private fun goBack() {
        val decor = window.decorView
        when {
            ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == true ->
                WindowInsetsControllerCompat(window, decor).hide(WindowInsetsCompat.Type.ime())
            filters.picker.active -> filters.stopPicker()
            state.fullScreenView != null -> hideFullScreen()
            !state.interaction.unresponsive && !state.interaction.restarting && state.webView?.canGoBack() == true -> traverseHistory(forward = false)
            else -> finish()
        }
    }

    private fun hideFullScreen() {
        val hadFullScreen = state.fullScreenView != null
        (state.fullScreenView?.parent as? ViewGroup)?.removeView(state.fullScreenView)
        state.fullScreenView = null
        state.fullScreenCallback?.onCustomViewHidden()
        state.fullScreenCallback = null
        if (hadFullScreen && !isFinishing && !isDestroyed) {
            setFullscreenSystemBars(hidden = false)
            applyHyperOsGestureNavigationImmersion()
        }
    }

    private fun setFullscreenSystemBars(hidden: Boolean) {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (hidden) hide(WindowInsetsCompat.Type.systemBars())
            else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && state.fullScreenView != null) setFullscreenSystemBars(hidden = true)
    }

    private fun destroyPage(page: WebView) {
        cancelPageRequests(page)
        filters.detach(page)
        if (page === state.webView) state.webView = null
        page.setWebViewRenderProcessClient(null as WebViewRenderProcessClient?)
        (page.parent as? ViewGroup)?.removeView(page)
        page.destroy()
    }

    private fun unsupported(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun copyAddress() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("网址", state.address))
        // Android 13+ supplies its own clipboard confirmation.
        if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show()
    }

    private fun openSystemBrowser() {
        try { startActivity(browserViewIntent(state.address)) }
        catch (_: android.content.ActivityNotFoundException) { unsupported("没有可用的系统浏览器。") }
        catch (_: SecurityException) { unsupported("系统阻止了外部浏览器启动。") }
    }

    private fun share() {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, state.address), "分享链接"))
        } catch (_: android.content.ActivityNotFoundException) {
            unsupported("没有可用的分享应用。")
        }
    }

    private fun flushCookies() {
        // Closing the Activity must not cancel its final cookie write.
        cookieWrites.launch { CookieManager.getInstance().flush() }
    }

    private fun clearWebsiteData() {
        if (state.clearing || state.interaction.restarting) return
        state.clearing = true
        hideFullScreen()
        state.unresponsiveRenderer = null
        state.interaction = BrowserInteractionState()
        state.webView?.let { page ->
            page.stopLoading()
            page.clearCache(true)
            destroyPage(page)
        }
        val completed = Runnable {
            flushCookies()
            if (isFinishing || isDestroyed) return@Runnable
            state.clearing = false
            state.clearConfirmation = false
            state.canBack = false
            state.canForward = false
            state.error = "浏览器网站数据已清除。重新加载后可重新登录。"
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            // Includes cookies, IndexedDB, service worker storage and network cache.
            WebStorageCompat.deleteBrowsingData(WebStorage.getInstance(), completed)
        } else {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies {
                completed.run()
                if (!isFinishing && !isDestroyed) {
                    state.error = "网站登录、缓存及常规存储已清除。当前系统 WebView 不支持完整清除，请更新 WebView 后再次清除。"
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("address", state.address)
        if (!state.interaction.unresponsive && !state.interaction.restarting) {
            state.webView?.let { page -> outState.putBundle("page", Bundle().also { page.saveState(it) }) }
        }
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        state.resumed = false
        state.webView?.onPause()
        flushCookies()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        applyHyperOsGestureNavigationImmersion()
        state.webView?.onResume()
        filters.refresh()
        state.resumed = true
    }

    override fun onDestroy() {
        state.resumed = false
        state.mediaDiagnosticsCapture = false
        hideFullScreen()
        state.unresponsiveRenderer = null
        state.webView?.let(::destroyPage)
        super.onDestroy()
    }

    private companion object {
        const val BROWSER_PREFERENCES = "browser_settings"
        const val USER_AGENT_MODE_KEY = "user_agent_mode"
        const val MAX_MEDIA_DIAGNOSTIC_EVENTS = 30
        val cookieWrites = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
