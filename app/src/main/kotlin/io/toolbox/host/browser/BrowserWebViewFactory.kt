package io.toolbox.host.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewRenderProcess
import android.webkit.WebViewRenderProcessClient
import androidx.activity.ComponentActivity
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/** Activity-side operations the WebView clients call back into. Every page callback first checks that it
 * belongs to the current page ([BrowserUiState.webView]); stale pages are ignored. */
internal interface BrowserPageHost {
    /** True once the Activity is finishing or destroyed. */
    val closing: Boolean
    fun prepareNavigation(page: WebView, url: String? = null)
    fun updateNavigation(page: WebView)
    fun applyMediaLayoutCompatibility(page: WebView)
    fun flushCookies()
    fun collectMediaDiagnostics(page: WebView, epoch: Int)
    fun recordMediaDiagnosticEvent(event: String)
    fun hideFullScreen()
    fun destroyPage(page: WebView)
    fun createPage()
    /** Runs [action] after the current frame, on the Activity window's decor view. */
    fun postToWindow(action: () -> Unit)
    fun setFullscreenSystemBars(hidden: Boolean)
    fun onPageTouch(page: WebView, event: MotionEvent)
    fun browserChromeTopThresholdPx(): Int
}

internal fun validBrowserUrl(url: String): String? = BrowserWebUrl.valid(url)

/** Creates the ordinary-web WebView: settings, user agent, cookies, and the page/chrome/renderer clients. */
internal class BrowserWebViewFactory(
    private val activity: ComponentActivity,
    private val state: BrowserUiState,
    private val filters: BrowserFilterController,
    private val ssl: BrowserSslController,
    private val capabilities: BrowserCapabilityController,
    private val navigation: BrowserNavigationController,
    private val host: BrowserPageHost,
) {
    /** Bumped on every page start, so a mixed-content report from the previous document is dropped. */
    @Volatile private var documentGeneration = 0

    /** May throw [android.util.AndroidRuntimeException] when System WebView is missing or disabled. */
    fun create(): WebView = WebView(activity)

    private fun markMixedContent(view: WebView, generation: Int) {
        if (view === state.webView && generation == documentGeneration &&
            state.address.startsWith("https:", ignoreCase = true)
        ) {
            state.mixedContent = true
        }
    }

    /** One shared transparent poster; WebView asks for it on every video element. */
    private val transparentVideoPoster: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(page: WebView) {
        // Until the first document paints, WebView fills itself with its background colour (white by
        // default), which flashes in dark mode. Use the window colour until then; once the page has
        // drawn (or finished loading) white returns, the base colour pages without a background expect.
        page.setBackgroundColor(activity.getColor(io.toolbox.host.R.color.toolbox_window_background))
        page.settings.apply {
            BrowserCompatibilityPolicy.apply(this)
            applyUserAgent(this)
        }
        BrowserCompatibilityPolicy.applyCompat(page.settings)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(page, true)
        page.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                filters.intercept(view, request)?.let { return it }
                // Network thread. A plain-HTTP subresource that is actually requested marks an HTTPS page insecure.
                if (!request.isForMainFrame && request.url.scheme == "http") {
                    val generation = documentGeneration
                    view.post { markMixedContent(view, generation) }
                }
                return null
            }

            // Also sees the destination of a subresource redirect, which shouldInterceptRequest never does.
            override fun onLoadResource(view: WebView, url: String) {
                if (url.startsWith("http:", ignoreCase = true)) {
                    markMixedContent(view, documentGeneration)
                }
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                // The commit can draw before render-blocking CSS arrives, so white returns only once a
                // frame with the page's own content has been drawn.
                if (WebViewFeature.isFeatureSupported(WebViewFeature.VISUAL_STATE_CALLBACK)) {
                    androidx.webkit.WebViewCompat.postVisualStateCallback(view, 0L) {
                        view.setBackgroundColor(android.graphics.Color.WHITE)
                    }
                }
                if (view === state.webView) filters.applyToPage()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val handled = navigation.handle(view, request)
                val destination = request.url.toString()
                val current = view.url.orEmpty()
                val fragmentOnly = destination != current &&
                    destination.substringBefore('#') == current.substringBefore('#')
                if (!handled && request.isForMainFrame && !fragmentOnly) {
                    host.prepareNavigation(view, destination)
                }
                return handled
            }

            override fun onSafeBrowsingHit(
                view: WebView, request: WebResourceRequest, threatType: Int, callback: SafeBrowsingResponse,
            ) = navigation.safeBrowsing(view, request, callback)

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (view !== state.webView) return
                if (ssl.onNavigationStarted(view, url)) {
                    capabilities.cancelPage(view)
                    navigation.cancelPage(view)
                }
                validBrowserUrl(url)?.let { state.address = it }
                filters.navigated(url)
                state.title = ""
                documentGeneration++
                state.mixedContent = false
                state.error = null
                state.loadProgress = 0
                state.interaction = state.interaction.pageStarted()
                if (state.mediaDiagnosticsCapture) {
                    state.mediaDiagnosticsReport = "正在重新加载网页并捕获媒体错误…"
                }
                state.chrome.show()
                host.updateNavigation(view)
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.setBackgroundColor(android.graphics.Color.WHITE)
                if (view !== state.webView) return
                state.interaction = state.interaction.pageFinished()
                state.loadProgress = 100
                host.updateNavigation(view)
                filters.applyToPage()
                host.applyMediaLayoutCompatibility(view)
                view.postDelayed(
                    { if (view === state.webView) host.applyMediaLayoutCompatibility(view) },
                    MEDIA_LAYOUT_COMPAT_RETRY_MS,
                )
                host.flushCookies()
                if (state.mediaDiagnosticsCapture) {
                    val epoch = state.mediaDiagnosticsEpoch
                    view.postDelayed(
                        { host.collectMediaDiagnostics(view, epoch) },
                        MEDIA_DIAGNOSTIC_SETTLE_MS,
                    )
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (view !== state.webView) return
                validBrowserUrl(url)?.let { state.address = it }
                filters.historyChanged(url)
                host.updateNavigation(view)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                if (view !== state.webView) return
                if (state.mediaDiagnosticsCapture && !request.isForMainFrame) {
                    BrowserMediaDiagnostics.networkEvent(
                        label = "加载失败",
                        url = request.url.toString(),
                        detail = "code=${failure.errorCode} ${failure.description}",
                    )?.let(host::recordMediaDiagnosticEvent)
                }
                if (request.isForMainFrame && !state.interaction.stoppedByUser) {
                    state.error = "网页加载失败，请检查网络后重试。"
                    state.interaction = state.interaction.pageFinished()
                    state.loadProgress = 100
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (view !== state.webView || !state.mediaDiagnosticsCapture || request.isForMainFrame) return
                BrowserMediaDiagnostics.networkEvent(
                    label = "HTTP ${errorResponse.statusCode}",
                    url = request.url.toString(),
                    detail = errorResponse.reasonPhrase.orEmpty(),
                    mimeType = errorResponse.mimeType,
                )?.let(host::recordMediaDiagnosticEvent)
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, failure: SslError) {
                ssl.handle(view, handler, failure)
                if (view === state.webView && state.mediaDiagnosticsCapture) {
                    BrowserMediaDiagnostics.networkEvent(
                        label = "TLS 等待用户选择",
                        url = failure.url.orEmpty(),
                        detail = "primaryError=${failure.primaryError}",
                    )?.let(host::recordMediaDiagnosticEvent)
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view !== state.webView) return true
                val restartRequested = state.interaction.restarting
                host.hideFullScreen()
                host.destroyPage(view)
                state.unresponsiveRenderer = null
                state.interaction = BrowserInteractionState()
                state.loadProgress = 100
                state.canBack = false
                state.canForward = false
                if (restartRequested && !host.closing && !state.clearing) {
                    state.error = null
                    // Only an explicit recovery choice recreates a page; ordinary crashes never auto-reload.
                    host.postToWindow {
                        if (state.webView == null && !host.closing && !state.clearing) host.createPage()
                    }
                } else {
                    state.error = "网页进程已退出，点击重新加载可恢复浏览。"
                }
                return true
            }
        }
        page.webChromeClient = object : WebChromeClient() {
            // Websites may log form values or response bodies; never forward these to Logcat.
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (page === state.webView && state.mediaDiagnosticsCapture) {
                    BrowserMediaDiagnostics.consoleEvent(
                        level = message.messageLevel().name,
                        message = message.message(),
                        source = message.sourceId(),
                        line = message.lineNumber(),
                    )?.let(host::recordMediaDiagnosticEvent)
                }
                return true
            }
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view !== state.webView || state.interaction.stoppedByUser) return
                state.loadProgress = newProgress.coerceIn(0, 100)
                if (state.loadProgress == 100) state.interaction = state.interaction.pageFinished()
            }
            // WebView's stock poster is a grey square with a play circle that flashes before the site's own
            // poster or first frame; a transparent poster lets the page show through instead.
            override fun getDefaultVideoPoster(): Bitmap = transparentVideoPoster
            override fun onReceivedTitle(view: WebView, newTitle: String?) {
                if (view === state.webView) state.title = newTitle.orEmpty()
            }
            override fun onPermissionRequest(request: PermissionRequest) =
                capabilities.handleWebPermission(page, request)
            override fun onPermissionRequestCanceled(request: PermissionRequest) =
                capabilities.cancelWebPermission(request)
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
                capabilities.handleGeolocation(page, origin, callback)
            override fun onGeolocationPermissionsHidePrompt() = capabilities.cancelGeolocation(page)
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
                capabilities.showFileChooser(view, callback, params)
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (page !== state.webView || state.fullScreenView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                state.fullScreenCallback = callback
                state.fullScreenView = view
                host.setFullscreenSystemBars(hidden = true)
            }
            override fun onHideCustomView() {
                if (page === state.webView) host.hideFullScreen()
            }
        }
        // minSdk is 33. Use the system callback, not polling or a script injected into every page.
        page.setWebViewRenderProcessClient(activity.mainExecutor, object : WebViewRenderProcessClient() {
            override fun onRenderProcessUnresponsive(view: WebView, renderer: WebViewRenderProcess?) {
                if (view !== state.webView || host.closing || state.clearing) return
                state.unresponsiveRenderer = renderer
                state.interaction = state.interaction.rendererUnresponsive()
            }
            override fun onRenderProcessResponsive(view: WebView, renderer: WebViewRenderProcess?) {
                if (view !== state.webView || state.interaction.restarting) return
                state.unresponsiveRenderer = null
                state.interaction = state.interaction.rendererResponsive()
            }
        })
        page.setOnTouchListener { view, event ->
            if (view === state.webView) host.onPageTouch(page, event)
            false
        }
        page.setOnScrollChangeListener { view, _, scrollY, _, _ ->
            if (view === state.webView && scrollY <= host.browserChromeTopThresholdPx()) {
                state.chrome.show()
            }
        }
        page.setDownloadListener { url, userAgent, disposition, mimeType, _ ->
            navigation.download(page, url, userAgent, disposition, mimeType)
        }
    }

    /** Applies the current [BrowserUiState.userAgentMode] to a live page; the caller reloads it. */
    fun applyUserAgent(page: WebView) {
        applyUserAgent(page.settings, resetMetadata = true)
    }

    private fun applyUserAgent(settings: WebSettings, resetMetadata: Boolean = false) {
        val profile = BrowserUserAgentPolicy.profile(
            mode = state.userAgentMode,
            defaultUserAgent = WebSettings.getDefaultUserAgent(activity),
        )
        settings.userAgentString = profile.userAgent

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        if (!profile.overrideMetadata) {
            // A page switched from an overriding mode drops its metadata; unset fields use the system default.
            if (resetMetadata) WebSettingsCompat.setUserAgentMetadata(settings, UserAgentMetadata.Builder().build())
            return
        }

        val metadata = UserAgentMetadata.Builder()
            .setMobile(profile.mobile)
            .setPlatform(profile.platform)

        profile.chromeVersion?.let { fullVersion ->
            val majorVersion = fullVersion.substringBefore('.').ifBlank { fullVersion }
            metadata
                .setFullVersion(fullVersion)
                .setBrandVersionList(
                    listOf(
                        UserAgentMetadata.BrandVersion.Builder()
                            .setBrand("Chromium")
                            .setMajorVersion(majorVersion)
                            .setFullVersion(fullVersion)
                            .build(),
                        UserAgentMetadata.BrandVersion.Builder()
                            .setBrand("Google Chrome")
                            .setMajorVersion(majorVersion)
                            .setFullVersion(fullVersion)
                            .build(),
                    ),
                )
        }

        WebSettingsCompat.setUserAgentMetadata(settings, metadata.build())
    }

    private companion object {
        const val MEDIA_DIAGNOSTIC_SETTLE_MS = 2500L
        const val MEDIA_LAYOUT_COMPAT_RETRY_MS = 1500L
    }
}
