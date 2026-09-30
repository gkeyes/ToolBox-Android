package io.toolbox.host.browser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewRenderProcess
import android.webkit.WebViewRenderProcessClient
import android.webkit.WebSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import io.toolbox.core.ui.component.ToolBoxActionSheet
import io.toolbox.core.ui.component.ToolBoxActionSheetHeader
import io.toolbox.core.ui.component.ToolBoxIconButton
import io.toolbox.core.ui.component.ToolBoxIcon
import io.toolbox.core.ui.component.ToolBoxGroupDivider
import io.toolbox.core.ui.component.ToolBoxModalDialog
import io.toolbox.core.ui.component.ToolBoxSecondaryButton
import io.toolbox.core.ui.component.ToolBoxDestructiveButton
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxText
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.runtime.browserViewIntent
import io.toolbox.host.ui.applyHyperOsGestureNavigationImmersion
import io.toolbox.tool.runtime.validateRuntimeBrowserUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Ordinary web content: no asset loader, tool profile, page-to-native bridge or native RPC.
 * Local content-filter scripts only manipulate the current webpage DOM. */
class BrowserActivity : ComponentActivity() {
    private val filters by lazy { BrowserFilterController(this) }
    private val mediaDiagnosticsScript by lazy {
        applicationContext.assets.open("browser/media-diagnostics.js").bufferedReader().use { it.readText() }
    }
    private val mediaLayoutCompatScript by lazy {
        applicationContext.assets.open("browser/media-layout-compat.js").bufferedReader().use { it.readText() }
    }
    private var webView by mutableStateOf<WebView?>(null)
    private var address by mutableStateOf("")
    private var title by mutableStateOf("")
    private var loadProgress by mutableIntStateOf(0)
    private var interaction by mutableStateOf(BrowserInteractionState())
    private var resumed by mutableStateOf(false)
    private var unresponsiveRenderer: WebViewRenderProcess? = null
    private var canBack by mutableStateOf(false)
    private var canForward by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var menu by mutableStateOf(false)
    private var mediaDiagnosticsSheet by mutableStateOf(false)
    private var mediaDiagnosticsRunning by mutableStateOf(false)
    private var mediaDiagnosticsReport by mutableStateOf("尚未运行媒体诊断。")
    private var mediaDiagnosticsCapture = false
    private var mediaDiagnosticsEpoch = 0
    private val mediaDiagnosticEvents = ArrayDeque<String>()
    private var userAgentSheet by mutableStateOf(false)
    private var userAgentMode by mutableStateOf(BrowserUserAgentMode.ChromeMobile)
    private val browserPreferences by lazy { getSharedPreferences(BROWSER_PREFERENCES, MODE_PRIVATE) }
    private var clearConfirmation by mutableStateOf(false)
    private var fullAddress by mutableStateOf(false)
    private var clearing by mutableStateOf(false)
    private var fullScreenView by mutableStateOf<View?>(null)
    private var fullScreenCallback: WebChromeClient.CustomViewCallback? = null
    private var browserChromeHidden by mutableStateOf(false)
    private var browserTouchLastY: Float? = null
    private var browserTouchDownPx = 0f
    private var browserTouchUpPx = 0f
    private var browserChromeGestureCommitted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        address = validUrl(savedInstanceState?.getString("address") ?: intent.dataString.orEmpty()) ?: run {
            finish()
            return
        }
        userAgentMode = BrowserUserAgentMode.fromStored(
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
                BrowserScreen()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPage(savedState: Bundle? = null) {
        if (isFinishing || isDestroyed || clearing) return
        interaction = BrowserInteractionState()
        unresponsiveRenderer = null
        try {
            val page = WebView(this)
            webView = page
            filters.attach(page, address)
            page.settings.apply {
                BrowserCompatibilityPolicy.apply(this)
                applyUserAgent(this)
            }
            BrowserCompatibilityPolicy.applyCompat(page.settings)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(page, true)
            page.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    filters.intercept(view, request)

                override fun onPageCommitVisible(view: WebView, url: String) {
                    if (view === webView) filters.applyToPage()
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (view !== webView) return true
                    val requested = request.url.toString()
                    val normalized = validUrl(requested)
                    if (normalized != null) {
                        if (request.isForMainFrame && normalized != requested) {
                            view.loadUrl(normalized)
                            return true
                        }
                        return false
                    }
                    if (request.isForMainFrame) unsupported("此链接需要其他应用，请从底部“更多”选择系统浏览器。")
                    return true
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (view !== webView) return
                    validUrl(url)?.let { address = it }
                    filters.navigated(url)
                    title = ""
                    error = null
                    loadProgress = 0
                    interaction = interaction.pageStarted()
                    if (mediaDiagnosticsCapture) {
                        mediaDiagnosticsReport = "正在重新加载网页并捕获媒体错误…"
                    }
                    showBrowserChrome()
                    updateNavigation(view)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (view !== webView) return
                    interaction = interaction.pageFinished()
                    loadProgress = 100
                    updateNavigation(view)
                    filters.applyToPage()
                    applyMediaLayoutCompatibility(view)
                    view.postDelayed(
                        { if (view === webView) applyMediaLayoutCompatibility(view) },
                        MEDIA_LAYOUT_COMPAT_RETRY_MS,
                    )
                    flushCookies()
                    if (mediaDiagnosticsCapture) {
                        val epoch = mediaDiagnosticsEpoch
                        view.postDelayed(
                            { collectMediaDiagnostics(view, epoch) },
                            MEDIA_DIAGNOSTIC_SETTLE_MS,
                        )
                    }
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    if (view !== webView) return
                    validUrl(url)?.let { address = it }
                    filters.historyChanged(url)
                    updateNavigation(view)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                    if (view !== webView) return
                    if (mediaDiagnosticsCapture && !request.isForMainFrame) {
                        BrowserMediaDiagnostics.networkEvent(
                            label = "加载失败",
                            url = request.url.toString(),
                            detail = "code=${failure.errorCode} ${failure.description}",
                        )?.let(::recordMediaDiagnosticEvent)
                    }
                    if (request.isForMainFrame && !interaction.stoppedByUser) {
                        error = "网页加载失败，请检查网络后重试。"
                        interaction = interaction.pageFinished()
                        loadProgress = 100
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    if (view !== webView || !mediaDiagnosticsCapture || request.isForMainFrame) return
                    BrowserMediaDiagnostics.networkEvent(
                        label = "HTTP ${errorResponse.statusCode}",
                        url = request.url.toString(),
                        detail = errorResponse.reasonPhrase.orEmpty(),
                        mimeType = errorResponse.mimeType,
                    )?.let(::recordMediaDiagnosticEvent)
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, failure: SslError) {
                    if (view === webView) {
                        handler.proceed()
                        if (mediaDiagnosticsCapture) {
                            BrowserMediaDiagnostics.networkEvent(
                                label = "TLS 继续加载",
                                url = failure.url.orEmpty(),
                                detail = "primaryError=${failure.primaryError}",
                            )?.let(::recordMediaDiagnosticEvent)
                        }
                    } else {
                        handler.cancel()
                    }
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (view !== webView) return true
                    val restartRequested = interaction.restarting
                    hideFullScreen()
                    destroyPage(view)
                    unresponsiveRenderer = null
                    interaction = BrowserInteractionState()
                    loadProgress = 100
                    canBack = false
                    canForward = false
                    if (restartRequested && !isFinishing && !isDestroyed && !clearing) {
                        error = null
                        // Only an explicit recovery choice recreates a page; ordinary crashes never auto-reload.
                        window.decorView.post {
                            if (webView == null && !isFinishing && !isDestroyed && !clearing) createPage()
                        }
                    } else {
                        error = "网页进程已退出，点击重新加载可恢复浏览。"
                    }
                    return true
                }
            }
            page.webChromeClient = object : WebChromeClient() {
                // Websites may log form values or response bodies; never forward these to Logcat.
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (page === webView && mediaDiagnosticsCapture) {
                        BrowserMediaDiagnostics.consoleEvent(
                            level = message.messageLevel().name,
                            message = message.message(),
                            source = message.sourceId(),
                            line = message.lineNumber(),
                        )?.let(::recordMediaDiagnosticEvent)
                    }
                    return true
                }
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (view !== webView || interaction.stoppedByUser) return
                    loadProgress = newProgress.coerceIn(0, 100)
                    if (loadProgress == 100) interaction = interaction.pageFinished()
                }
                override fun onReceivedTitle(view: WebView, newTitle: String?) {
                    if (view === webView) title = newTitle.orEmpty()
                }
                override fun onPermissionRequest(request: PermissionRequest) {
                    if (page !== webView) {
                        request.deny()
                        return
                    }

                    val granted = BrowserMediaPolicy.grantedResources(
                        originScheme = request.origin.scheme,
                        requested = request.resources,
                    )
                    if (granted.isEmpty()) request.deny() else request.grant(granted)

                    if (BrowserMediaPolicy.requestsCapture(request.resources)) {
                        unsupported("内置浏览器仍不提供摄像头或麦克风权限；网页视频播放不受此限制。")
                    }
                }
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                    callback.invoke(origin, false, false)
                    if (page === webView) unsupported("内置浏览器不提供定位权限，可从菜单选择系统浏览器。")
                }
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    callback.onReceiveValue(null)
                    if (view === webView) unsupported("内置浏览器暂不支持上传文件，可从菜单选择系统浏览器。")
                    return true
                }
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (page !== webView || fullScreenView != null) {
                        callback.onCustomViewHidden()
                        return
                    }
                    fullScreenCallback = callback
                    fullScreenView = view
                    setFullscreenSystemBars(hidden = true)
                }
                override fun onHideCustomView() {
                    if (page === webView) hideFullScreen()
                }
            }
            // minSdk is 33. Use the system callback, not polling or a script injected into every page.
            page.setWebViewRenderProcessClient(mainExecutor, object : WebViewRenderProcessClient() {
                override fun onRenderProcessUnresponsive(view: WebView, renderer: WebViewRenderProcess?) {
                    if (view !== webView || isFinishing || isDestroyed || clearing) return
                    unresponsiveRenderer = renderer
                    interaction = interaction.rendererUnresponsive()
                }
                override fun onRenderProcessResponsive(view: WebView, renderer: WebViewRenderProcess?) {
                    if (view !== webView || interaction.restarting) return
                    unresponsiveRenderer = null
                    interaction = interaction.rendererResponsive()
                }
            })
            page.setOnTouchListener { view, event ->
                if (view === webView) updateBrowserChromeForTouch(page, event)
                false
            }
            page.setOnScrollChangeListener { view, _, scrollY, _, _ ->
                if (view === webView && scrollY <= browserChromeTopThresholdPx()) {
                    showBrowserChrome()
                }
            }
            page.setDownloadListener { _, _, _, _, _ ->
                if (page === webView) unsupported("内置浏览器暂不支持下载，可从菜单选择系统浏览器。")
            }
            if (savedState == null || page.restoreState(savedState) == null) {
                interaction = interaction.pageStarted()
                page.loadUrl(address)
            } else {
                validUrl(page.url.orEmpty())?.let { address = it }
                title = page.title.orEmpty()
                loadProgress = 100
                updateNavigation(page)
                page.post { if (page === webView) filters.applyToPage() }
            }
        } catch (_: android.util.AndroidRuntimeException) {
            webView?.let(::destroyPage)
            interaction = BrowserInteractionState()
            error = "Android System WebView 不可用，请启用或更新系统 WebView 后重试。"
        }
    }

    private fun browserChromeLocked(): Boolean {
        val imeVisible = ViewCompat.getRootWindowInsets(window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        return imeVisible || fullScreenView != null || menu || mediaDiagnosticsSheet || userAgentSheet ||
            fullAddress || clearConfirmation || filters.sheet || filters.picker.active ||
            interaction.showRecoveryPrompt || error != null
    }

    private fun browserChromeTopThresholdPx(): Int =
        (8f * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun resetBrowserChromeGesture() {
        browserTouchLastY = null
        browserTouchDownPx = 0f
        browserTouchUpPx = 0f
        browserChromeGestureCommitted = false
    }

    private fun showBrowserChrome() {
        browserChromeHidden = false
        browserTouchDownPx = 0f
        browserTouchUpPx = 0f
    }

    private fun updateBrowserChromeForTouch(page: WebView, event: MotionEvent) {
        if (browserChromeLocked()) {
            showBrowserChrome()
            resetBrowserChromeGesture()
            return
        }

        if (page.scrollY <= browserChromeTopThresholdPx()) {
            showBrowserChrome()
        }

        if (event.pointerCount > 1) {
            resetBrowserChromeGesture()
            return
        }

        val density = resources.displayMetrics.density
        val hideThreshold = 24f * density
        val showThreshold = 11f * density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                browserTouchLastY = event.rawY
                browserTouchDownPx = 0f
                browserTouchUpPx = 0f
                browserChromeGestureCommitted = false
            }

            MotionEvent.ACTION_MOVE -> {
                val previousY = browserTouchLastY ?: event.rawY
                val delta = previousY - event.rawY
                browserTouchLastY = event.rawY

                if (browserChromeGestureCommitted) return

                when {
                    delta > 0f -> {
                        browserTouchDownPx += delta
                        browserTouchUpPx = 0f
                        if (!browserChromeHidden &&
                            page.scrollY > browserChromeTopThresholdPx() &&
                            browserTouchDownPx >= hideThreshold
                        ) {
                            browserChromeHidden = true
                            browserChromeGestureCommitted = true
                        }
                    }

                    delta < 0f -> {
                        browserTouchUpPx += -delta
                        browserTouchDownPx = 0f
                        if (browserChromeHidden && browserTouchUpPx >= showThreshold) {
                            browserChromeHidden = false
                            browserChromeGestureCommitted = true
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> resetBrowserChromeGesture()
        }
    }

    private fun applyUserAgent(settings: WebSettings) {
        val profile = BrowserUserAgentPolicy.profile(
            mode = userAgentMode,
            defaultUserAgent = WebSettings.getDefaultUserAgent(this),
        )
        settings.userAgentString = profile.userAgent

        if (!profile.overrideMetadata ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)
        ) {
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

    private fun changeUserAgentMode(mode: BrowserUserAgentMode) {
        userAgentSheet = false
        if (mode == userAgentMode || clearing || interaction.restarting) return

        val currentAddress = validUrl(webView?.url.orEmpty()) ?: address
        userAgentMode = mode
        browserPreferences.edit().putString(USER_AGENT_MODE_KEY, mode.storageValue).apply()

        hideFullScreen()
        filters.stopPicker()
        webView?.let { page ->
            page.stopLoading()
            destroyPage(page)
        }
        address = currentAddress
        title = ""
        loadProgress = 0
        canBack = false
        canForward = false
        error = null
        interaction = BrowserInteractionState()
        createPage()
    }

    private fun recordMediaDiagnosticEvent(event: String) {
        while (mediaDiagnosticEvents.size >= MAX_MEDIA_DIAGNOSTIC_EVENTS) {
            mediaDiagnosticEvents.removeFirst()
        }
        mediaDiagnosticEvents.addLast(event)
    }

    private fun startMediaDiagnostics() {
        if (clearing || interaction.restarting || interaction.unresponsive) return
        mediaDiagnosticsSheet = true
        mediaDiagnosticsRunning = true
        mediaDiagnosticsCapture = true
        mediaDiagnosticsEpoch++
        mediaDiagnosticEvents.clear()
        mediaDiagnosticsReport = "正在重新加载网页并捕获媒体错误…"
        filters.stopPicker()
        error = null
        loadProgress = 0
        interaction = interaction.pageStarted()
        val page = webView
        if (page == null) createPage() else page.reload()
    }

    private fun collectMediaDiagnostics(page: WebView, epoch: Int) {
        if (!mediaDiagnosticsCapture || epoch != mediaDiagnosticsEpoch || page !== webView) return
        mediaDiagnosticsCapture = false
        val expression = "($mediaDiagnosticsScript)()"
        page.evaluateJavascript(expression) { raw ->
            if (epoch != mediaDiagnosticsEpoch || page !== webView) return@evaluateJavascript
            val encoded = raw?.trim()?.removeSurrounding("\"").orEmpty()
            val domReport = runCatching {
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrElse { "DOM 诊断脚本没有返回有效结果：${it.javaClass.simpleName}" }
            val provider = WebView.getCurrentWebViewPackage()
            mediaDiagnosticsReport = BrowserMediaDiagnostics.buildReport(
                domReport = domReport,
                events = mediaDiagnosticEvents.toList(),
                webViewProvider = listOfNotNull(provider?.packageName, provider?.versionName).joinToString(" "),
                userAgentMode = userAgentMode.shortLabel,
                mixedContentMode = page.settings.mixedContentMode,
            )
            mediaDiagnosticsRunning = false
        }
    }

    private fun copyMediaDiagnostics() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("ToolBox 媒体诊断", mediaDiagnosticsReport))
        if (android.os.Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "诊断报告已复制", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyMediaLayoutCompatibility(page: WebView) {
        if (page !== webView) return
        page.evaluateJavascript("($mediaLayoutCompatScript)()", null)
    }

    private fun validUrl(url: String): String? = try {
        BrowserNavigationPolicy.normalize(validateRuntimeBrowserUrl(url))
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun updateNavigation(page: WebView) {
        if (page !== webView) return
        canBack = page.canGoBack()
        canForward = page.canGoForward()
    }

    private fun reload() {
        filters.stopPicker()
        when (interaction.loadAction(clearing)) {
            BrowserLoadAction.Disabled -> return
            BrowserLoadAction.Recover -> { interaction = interaction.requestRecoveryPrompt(); return }
            else -> Unit
        }
        error = null
        loadProgress = 0
        interaction = interaction.pageStarted()
        if (webView == null) createPage() else webView?.reload()
    }

    private fun performLoadAction() {
        when (interaction.loadAction(clearing)) {
            BrowserLoadAction.Disabled -> Unit
            BrowserLoadAction.Stop -> {
                // Set this before stopLoading: an interrupted request must not become an error overlay.
                interaction = interaction.stopRequested()
                webView?.stopLoading()
                loadProgress = 100
            }
            BrowserLoadAction.Recover -> { interaction = interaction.requestRecoveryPrompt() }
            BrowserLoadAction.Reload -> reload()
        }
    }

    private fun restartUnresponsivePage() {
        if (clearing || interaction.restarting || !interaction.unresponsive) return
        val renderer = unresponsiveRenderer
        interaction = interaction.restartRequested()
        // Termination can affect other browser pages sharing this renderer. The confirmation says so.
        // Tool runtimes live in a separate process/profile and are never passed to this code.
        val requested = runCatching { renderer?.terminate() == true }.getOrDefault(false)
        if (!requested) {
            interaction = interaction.restartFailed()
            error = "网页进程暂时无法重新启动，请使用系统浏览器，或关闭后重试。"
            loadProgress = 100
        }
        // On success, onRenderProcessGone disposes the dead WebView before creating its replacement.
    }

    private fun goBack() {
        val decor = window.decorView
        when {
            ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == true ->
                WindowInsetsControllerCompat(window, decor).hide(WindowInsetsCompat.Type.ime())
            filters.picker.active -> filters.stopPicker()
            fullScreenView != null -> hideFullScreen()
            !interaction.unresponsive && !interaction.restarting && webView?.canGoBack() == true -> webView?.goBack()
            else -> finish()
        }
    }

    private fun hideFullScreen() {
        val hadFullScreen = fullScreenView != null
        (fullScreenView?.parent as? ViewGroup)?.removeView(fullScreenView)
        fullScreenView = null
        fullScreenCallback?.onCustomViewHidden()
        fullScreenCallback = null
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
        if (hasFocus && fullScreenView != null) setFullscreenSystemBars(hidden = true)
    }

    private fun destroyPage(page: WebView) {
        filters.detach(page)
        if (page === webView) webView = null
        page.setWebViewRenderProcessClient(null as WebViewRenderProcessClient?)
        (page.parent as? ViewGroup)?.removeView(page)
        page.destroy()
    }

    private fun unsupported(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun copyAddress() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("网址", address))
        // Android 13+ supplies its own clipboard confirmation.
        if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show()
    }

    private fun openSystemBrowser() {
        try { startActivity(browserViewIntent(address)) }
        catch (_: android.content.ActivityNotFoundException) { unsupported("没有可用的系统浏览器。") }
        catch (_: SecurityException) { unsupported("系统阻止了外部浏览器启动。") }
    }

    private fun flushCookies() {
        // Closing the Activity must not cancel its final cookie write.
        cookieWrites.launch { CookieManager.getInstance().flush() }
    }

    private fun clearWebsiteData() {
        if (clearing || interaction.restarting) return
        clearing = true
        hideFullScreen()
        unresponsiveRenderer = null
        interaction = BrowserInteractionState()
        webView?.let { page ->
            page.stopLoading()
            page.clearCache(true)
            destroyPage(page)
        }
        val completed = Runnable {
            flushCookies()
            if (isFinishing || isDestroyed) return@Runnable
            clearing = false
            clearConfirmation = false
            canBack = false
            canForward = false
            error = "浏览器网站数据已清除。重新加载后可重新登录。"
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            // Includes cookies, IndexedDB, service worker storage and network cache.
            WebStorageCompat.deleteBrowsingData(WebStorage.getInstance(), completed)
        } else {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies {
                completed.run()
                if (!isFinishing && !isDestroyed) {
                    error = "网站登录、缓存及常规存储已清除。当前系统 WebView 不支持完整清除，请更新 WebView 后再次清除。"
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("address", address)
        if (!interaction.unresponsive && !interaction.restarting) {
            webView?.let { page -> outState.putBundle("page", Bundle().also { page.saveState(it) }) }
        }
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        resumed = false
        webView?.onPause()
        flushCookies()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        applyHyperOsGestureNavigationImmersion()
        webView?.onResume()
        filters.refresh()
        resumed = true
    }

    override fun onDestroy() {
        resumed = false
        mediaDiagnosticsCapture = false
        hideFullScreen()
        unresponsiveRenderer = null
        webView?.let(::destroyPage)
        super.onDestroy()
    }

    @Composable
    private fun BrowserScreen() {
        val colors = ToolBoxThemeTokens.colors
        val lightSystemBars = colors.background.luminance() > 0.5f
        LaunchedEffect(lightSystemBars) {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = lightSystemBars
                isAppearanceLightNavigationBars = lightSystemBars
            }
        }
        val chromeLocked = fullScreenView != null || menu || mediaDiagnosticsSheet || userAgentSheet ||
            fullAddress || clearConfirmation || filters.sheet || filters.picker.active ||
            interaction.showRecoveryPrompt || error != null
        LaunchedEffect(chromeLocked) {
            if (chromeLocked) showBrowserChrome()
        }
        val chromeVisible = !browserChromeHidden || chromeLocked
        val chromeEasing = CubicBezierEasing(0.2f, 0.75f, 0.2f, 1f)

        Box(Modifier.fillMaxSize().background(colors.background)) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                AnimatedVisibility(
                    visible = chromeVisible,
                    enter = expandVertically(
                        expandFrom = Alignment.Top,
                        animationSpec = tween(durationMillis = 220, easing = chromeEasing),
                    ) + slideInVertically(
                        animationSpec = tween(durationMillis = 220, easing = chromeEasing),
                        initialOffsetY = { -it },
                    ),
                    exit = shrinkVertically(
                        shrinkTowards = Alignment.Top,
                        animationSpec = tween(durationMillis = 220, easing = chromeEasing),
                    ) + slideOutVertically(
                        animationSpec = tween(durationMillis = 220, easing = chromeEasing),
                        targetOffsetY = { -it },
                    ),
                ) {
                    Column {
                        BrowserToolbar()
                        Box(Modifier.fillMaxWidth().height(2.dp)) {
                            if (interaction.loading && loadProgress in 0..99) {
                                Box(Modifier.fillMaxWidth((loadProgress / 100f).coerceAtLeast(0.02f))
                                    .fillMaxHeight().background(colors.primary))
                            }
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    webView?.let { page -> key(page) { AndroidView(factory = { page }, modifier = Modifier.fillMaxSize()) } }
                    error?.let { message ->
                        Column(
                            Modifier.fillMaxSize().background(colors.background).padding(24.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ToolBoxText(message)
                            Spacer(Modifier.height(12.dp))
                            ToolBoxTextButton("重新加载", ::reload, enabled = !clearing && !interaction.restarting)
                            ToolBoxTextButton("使用系统浏览器", ::openSystemBrowser, enabled = !clearing, outlined = false)
                        }
                    }
                }
                AnimatedVisibility(
                    visible = chromeVisible,
                    enter = expandVertically(
                        expandFrom = Alignment.Bottom,
                        animationSpec = tween(durationMillis = 220, easing = chromeEasing),
                    ) + slideInVertically(
                        animationSpec = tween(durationMillis = 220, easing = chromeEasing),
                        initialOffsetY = { it },
                    ),
                    exit = shrinkVertically(
                        shrinkTowards = Alignment.Bottom,
                        animationSpec = tween(durationMillis = 220, easing = chromeEasing),
                    ) + slideOutVertically(
                        animationSpec = tween(durationMillis = 220, easing = chromeEasing),
                        targetOffsetY = { it },
                    ),
                ) {
                    Column {
                        BrowserFilterControls(filters, resumed)
                        if (fullScreenView == null) BrowserBottomBar()
                    }
                }
            }
            fullScreenView?.let { view -> AndroidView(factory = { view }, modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) }
        }
        BrowserFilterSheet(
            filters,
            canPick = webView != null && error == null && !interaction.loading && !interaction.unresponsive && !clearing,
        )
        if (menu) ToolBoxActionSheet(title = "浏览器菜单", onDismissRequest = { menu = false }) {
            Column(Modifier.fillMaxWidth()) {
                ToolBoxActionSheetHeader {
                    Spacer(Modifier.height(2.dp))
                }
                BrowserMenuAction("使用系统浏览器", ToolBoxIconKey.Globe) {
                    menu = false
                    openSystemBrowser()
                }
                BrowserMenuAction("复制链接", ToolBoxIconKey.Clipboard) {
                    copyAddress()
                    menu = false
                }
                BrowserMenuAction(
                    "网站兼容性 · ${userAgentMode.shortLabel}",
                    ToolBoxIconKey.Device,
                ) {
                    menu = false
                    userAgentSheet = true
                }
                BrowserMenuAction(
                    "媒体诊断",
                    ToolBoxIconKey.Code,
                    enabled = webView != null && !clearing && !interaction.restarting && !interaction.unresponsive,
                ) {
                    menu = false
                    startMediaDiagnostics()
                }
                BrowserMenuAction(
                    "清除浏览器网站数据",
                    ToolBoxIconKey.Shield,
                    destructive = true,
                    enabled = !clearing && !interaction.restarting,
                ) {
                    menu = false
                    clearConfirmation = true
                }
                Spacer(Modifier.height(2.dp))
            }
        }
        if (mediaDiagnosticsSheet) {
            ToolBoxActionSheet(
                title = "媒体诊断",
                onDismissRequest = { mediaDiagnosticsSheet = false },
                containerColor = colors.background,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    ToolBoxActionSheetHeader {
                        Column(Modifier.fillMaxWidth()) {
                            ToolBoxText(
                                if (mediaDiagnosticsRunning) "正在诊断…" else "诊断结果",
                                style = ToolBoxThemeTokens.textStyles.title.copy(
                                    color = colors.textPrimary,
                                    fontSize = 18.sp,
                                    lineHeight = 22.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                            Spacer(Modifier.height(4.dp))
                            ToolBoxText(
                                "只记录播放器能力、媒体相关资源错误和 DOM 状态；不会记录网页正文、Cookie 或请求参数。",
                                style = ToolBoxThemeTokens.textStyles.body.copy(
                                    color = colors.textSecondary,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                ),
                            )
                        }
                    }
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(colors.surface)
                            .padding(12.dp),
                    ) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            ToolBoxText(
                                mediaDiagnosticsReport,
                                modifier = Modifier.fillMaxWidth()
                                    .heightIn(min = 140.dp, max = 360.dp)
                                    .verticalScroll(rememberScrollState()),
                                style = ToolBoxThemeTokens.textStyles.metadata.copy(
                                    color = colors.textSecondary,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BrowserAddressSheetAction(
                            label = if (mediaDiagnosticsRunning) "诊断中…" else "重新诊断",
                            icon = ToolBoxIconKey.Refresh,
                            highlighted = true,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (!mediaDiagnosticsRunning) startMediaDiagnostics()
                        }
                        BrowserAddressSheetAction(
                            label = "复制报告",
                            icon = ToolBoxIconKey.Clipboard,
                            modifier = Modifier.weight(1f),
                        ) {
                            copyMediaDiagnostics()
                        }
                    }
                }
            }
        }
        if (userAgentSheet) {
            ToolBoxActionSheet(
                title = "网站兼容性",
                onDismissRequest = { userAgentSheet = false },
                containerColor = colors.background,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    ToolBoxActionSheetHeader {
                        Column(Modifier.fillMaxWidth()) {
                            ToolBoxText(
                                "网页身份",
                                style = ToolBoxThemeTokens.textStyles.title.copy(
                                    color = colors.textPrimary,
                                    fontSize = 18.sp,
                                    lineHeight = 22.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                            Spacer(Modifier.height(4.dp))
                            ToolBoxText(
                                "切换后会重新加载当前网页。默认使用 Chrome Mobile，提高视频和登录页兼容性。",
                                style = ToolBoxThemeTokens.textStyles.body.copy(
                                    color = colors.textSecondary,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                ),
                            )
                        }
                    }
                    BrowserUserAgentOption(
                        mode = BrowserUserAgentMode.ChromeMobile,
                        label = "Chrome Mobile",
                        description = "默认 · 适合大多数移动网页和视频播放器",
                    )
                    BrowserUserAgentOption(
                        mode = BrowserUserAgentMode.AndroidWebView,
                        label = "Android WebView",
                        description = "原始身份 · 用于兼容性排查",
                    )
                    BrowserUserAgentOption(
                        mode = BrowserUserAgentMode.DesktopChrome,
                        label = "Desktop Chrome",
                        description = "桌面身份 · 请求桌面版网页",
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
        if (fullAddress) {
            val uri = remember(address) { Uri.parse(address) }
            val host = uri.host.orEmpty().ifBlank { "网页地址" }
            val connectionLabel = when (uri.scheme?.lowercase()) {
                "https" -> "HTTPS 页面"
                "http" -> "HTTP 页面"
                else -> "网页地址"
            }
            ToolBoxActionSheet(
                title = "网页地址",
                onDismissRequest = { fullAddress = false },
                containerColor = colors.background,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    ToolBoxActionSheetHeader {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(36.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.softPrimary),
                                contentAlignment = Alignment.Center,
                            ) {
                                ToolBoxIcon(
                                    ToolBoxIconKey.Shield,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = colors.primary,
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                ToolBoxText(
                                    host,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = ToolBoxThemeTokens.textStyles.title.copy(
                                        color = colors.textPrimary,
                                        fontSize = 18.sp,
                                        lineHeight = 22.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                )
                                Spacer(Modifier.height(2.dp))
                                ToolBoxText(
                                    connectionLabel,
                                    maxLines = 1,
                                    style = ToolBoxThemeTokens.textStyles.label.copy(
                                        color = colors.textSecondary,
                                    ),
                                )
                            }
                            Box(
                                Modifier.size(36.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(colors.surfaceMuted)
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = "关闭地址详情",
                                        onClick = { fullAddress = false },
                                    )
                                    .semantics { contentDescription = "关闭地址详情" },
                                contentAlignment = Alignment.Center,
                            ) {
                                ToolBoxIcon(
                                    ToolBoxIconKey.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = colors.textSecondary,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(colors.surface)
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                    ) {
                        ToolBoxText(
                            "完整地址",
                            style = ToolBoxThemeTokens.textStyles.label.copy(
                                color = colors.textSecondary,
                            ),
                        )
                        Spacer(Modifier.height(6.dp))
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            ToolBoxText(
                                address,
                                modifier = Modifier.fillMaxWidth()
                                    .heightIn(max = 150.dp)
                                    .verticalScroll(rememberScrollState()),
                                style = ToolBoxThemeTokens.textStyles.body.copy(
                                    color = colors.textSecondary,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                ),
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BrowserAddressSheetAction(
                            label = "复制链接",
                            icon = ToolBoxIconKey.Clipboard,
                            highlighted = true,
                            modifier = Modifier.weight(1f).testTag("browser_copy_address"),
                            onClick = ::copyAddress,
                        )
                        BrowserAddressSheetAction(
                            label = "使用系统浏览器",
                            icon = ToolBoxIconKey.Globe,
                            modifier = Modifier.weight(1f),
                        ) {
                            fullAddress = false
                            openSystemBrowser()
                        }
                    }
                }
            }
        }
        if (clearConfirmation) ToolBoxModalDialog(onDismissRequest = { if (!clearing) clearConfirmation = false }) {
            ToolBoxText(
                "清除浏览器网站数据",
                modifier = Modifier.semantics { heading() },
                style = ToolBoxThemeTokens.textStyles.title.copy(
                    color = colors.textPrimary, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.height(16.dp))
            ToolBoxText(
                "清除所有工具共用的浏览器网站登录、缓存和网站存储。NextFlux 的账号及工具数据会保留。",
                style = ToolBoxThemeTokens.textStyles.body.copy(color = colors.textSecondary),
            )
            Spacer(Modifier.height(24.dp))
            ToolBoxDestructiveButton(
                if (clearing) "正在清除…" else "清除", ::clearWebsiteData,
                modifier = Modifier.fillMaxWidth(), enabled = !clearing,
            )
            Spacer(Modifier.height(12.dp))
            ToolBoxSecondaryButton(
                "取消", { clearConfirmation = false },
                modifier = Modifier.fillMaxWidth(), enabled = !clearing,
            )
        }
        // Avoid stacked dialogs and background-window prompts; a recovery callback removes this immediately.
        if (interaction.showRecoveryPrompt && resumed && !menu && !mediaDiagnosticsSheet && !userAgentSheet &&
            !fullAddress && !clearConfirmation && !filters.sheet && !filters.picker.active) {
            ToolBoxModalDialog(onDismissRequest = { interaction = interaction.keepWaiting() }) {
                ToolBoxText("网页暂未响应", modifier = Modifier.semantics { heading() },
                    style = ToolBoxThemeTokens.textStyles.title.copy(color = colors.textPrimary))
                Spacer(Modifier.height(12.dp))
                ToolBoxText(
                    "可以继续等待。重新加载会重启网页进程，未提交的内容可能丢失，其他内置浏览器页面也可能需要重新加载。小工具数据不受影响。",
                    style = ToolBoxThemeTokens.textStyles.body.copy(color = colors.textSecondary),
                )
                Spacer(Modifier.height(20.dp))
                ToolBoxSecondaryButton("继续等待", { interaction = interaction.keepWaiting() }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                ToolBoxTextButton("重新加载", ::restartUnresponsivePage, Modifier.fillMaxWidth(), outlined = false)
            }
        }
    }

    @Composable
    private fun BrowserToolbar() {
        val colors = ToolBoxThemeTokens.colors
        val uri = remember(address) { Uri.parse(address) }
        val unencrypted = uri.scheme == "http"
        val action = interaction.loadAction(clearing)
        val filterState = filters.snapshot
        val filtering = filterState.active(filters.site)
        val addressColor = if (interaction.unresponsive) colors.danger else colors.textPrimary
        val shieldTint = colors.primary
        val filterTint = if (filtering) colors.primary else ToolBoxThemeTokens.disabledContent

        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 10.dp, vertical = 5.dp)
                .testTag("browser_toolbar"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BrowserToolbarButton(ToolBoxIconKey.Close, "关闭浏览器", ::finish, compact = true)
            Row(
                Modifier.weight(1f).height(42.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(colors.surface)
                    .testTag("browser_address"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f)
                        .clickable(role = Role.Button, onClickLabel = "查看完整地址", onClick = { fullAddress = true })
                        .padding(start = 11.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolBoxIcon(
                        ToolBoxIconKey.Shield,
                        "连接状态",
                        modifier = Modifier.size(17.dp),
                        tint = shieldTint,
                    )
                    Spacer(Modifier.width(6.dp))
                    ToolBoxIcon(
                        ToolBoxIconKey.Haptics,
                        if (filtering) "广告过滤已开启" else "广告过滤未开启",
                        modifier = Modifier.size(17.dp),
                        tint = filterTint,
                    )
                    Spacer(Modifier.width(8.dp))
                    ToolBoxText(
                        (if (interaction.unresponsive) "未响应 · " else "") + uri.host.orEmpty(),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = ToolBoxThemeTokens.textStyles.body.copy(
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = addressColor,
                        ),
                    )
                }
                Box(Modifier.width(1.dp).height(20.dp).background(colors.textSecondary.copy(alpha = 0.10f)))
                BrowserRefreshButton(
                    label = when (action) {
                        BrowserLoadAction.Stop -> "停止加载"
                        BrowserLoadAction.Recover -> "查看网页恢复选项"
                        else -> "重新加载"
                    },
                    enabled = action != BrowserLoadAction.Disabled,
                    spinning = interaction.loading,
                    onClick = ::performLoadAction,
                )
            }
        }
    }

    @Composable
    private fun BrowserAddressSheetAction(
        label: String,
        icon: ToolBoxIconKey,
        modifier: Modifier = Modifier,
        highlighted: Boolean = false,
        onClick: () -> Unit,
    ) {
        val colors = ToolBoxThemeTokens.colors
        val background = if (highlighted) colors.softPrimary else colors.surface
        val tint = if (highlighted) colors.primary else colors.textPrimary
        Row(
            modifier
                .height(52.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(background)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            ToolBoxIcon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = tint,
            )
            Spacer(Modifier.width(7.dp))
            ToolBoxText(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = ToolBoxThemeTokens.textStyles.body.copy(
                    color = tint,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    }

    @Composable
    private fun BrowserRefreshButton(
        label: String,
        enabled: Boolean,
        spinning: Boolean,
        onClick: () -> Unit,
    ) {
        val tint = if (enabled) ToolBoxThemeTokens.colors.textSecondary else ToolBoxThemeTokens.disabledContent
        val interactionSource = remember { MutableInteractionSource() }
        val transition = rememberInfiniteTransition(label = "browser_refresh_rotation")
        val rotation by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 720, easing = LinearEasing),
            ),
            label = "browser_refresh_rotation_value",
        )
        Box(
            Modifier.size(40.dp)
                .clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                )
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            ToolBoxIcon(
                ToolBoxIconKey.Refresh,
                contentDescription = null,
                modifier = Modifier.size(20.dp).rotate(if (spinning) rotation else 0f),
                tint = tint,
            )
        }
    }

    @Composable
    private fun BrowserBottomBar() {
        val canInteract = !clearing && !interaction.unresponsive && !interaction.restarting
        val blocked = filters.blockedCount
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 3.dp)
                .height(54.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(ToolBoxThemeTokens.colors.surface),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrowserDockButton(
                ToolBoxIconKey.Back,
                "后退",
                Modifier.weight(1f),
                enabled = (canBack || filters.picker.active) && canInteract,
            ) {
                if (filters.picker.active) filters.stopPicker() else webView?.goBack()
            }
            BrowserDockButton(
                ToolBoxIconKey.ChevronRight,
                "前进",
                Modifier.weight(1f),
                enabled = canForward && canInteract,
            ) {
                webView?.goForward()
            }
            BrowserDockButton(
                ToolBoxIconKey.Shield,
                if (blocked > 0) "屏蔽 $blocked" else "屏蔽",
                Modifier.weight(1f),
                highlighted = filters.snapshot.active(filters.site),
                enabled = canInteract,
            ) {
                filters.stopPicker()
                filters.refresh()
                filters.sheet = true
            }
            BrowserDockButton(
                ToolBoxIconKey.Share,
                "分享",
                Modifier.weight(1f),
                enabled = canInteract,
            ) {
                try {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, address), "分享链接"))
                } catch (_: android.content.ActivityNotFoundException) {
                    unsupported("没有可用的分享应用。")
                }
            }
            BrowserDockButton(
                ToolBoxIconKey.More,
                "更多",
                Modifier.weight(1f),
                enabled = canInteract,
            ) { menu = true }
        }
    }

    @Composable
    private fun BrowserDockButton(
        icon: ToolBoxIconKey,
        label: String,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        highlighted: Boolean = false,
        onClick: () -> Unit,
    ) {
        val colors = ToolBoxThemeTokens.colors
        val tint = when {
            !enabled -> ToolBoxThemeTokens.disabledContent
            highlighted -> colors.primary
            else -> colors.textSecondary
        }
        Column(
            modifier.height(48.dp)
                .clip(RoundedCornerShape(15.dp))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 2.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ToolBoxIcon(icon, contentDescription = null, modifier = Modifier.size(19.dp), tint = tint)
            Spacer(Modifier.height(1.dp))
            ToolBoxText(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = ToolBoxThemeTokens.textStyles.metadata.copy(
                    fontSize = 9.5.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = tint,
                ),
            )
        }
    }

    @Composable
    private fun BrowserToolbarButton(
        icon: ToolBoxIconKey,
        label: String,
        onClick: () -> Unit,
        enabled: Boolean = true,
        stop: Boolean = false,
        compact: Boolean = false,
    ) {
        val tint = if (enabled) ToolBoxThemeTokens.colors.textSecondary else ToolBoxThemeTokens.disabledContent
        val size = if (compact) 40.dp else 44.dp
        Box(
            Modifier.size(size).clip(RoundedCornerShape(if (compact) 14.dp else 16.dp))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            if (stop) Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(tint))
            else ToolBoxIcon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
        }
    }

    @Composable
    private fun BrowserUserAgentOption(
        mode: BrowserUserAgentMode,
        label: String,
        description: String,
    ) {
        val colors = ToolBoxThemeTokens.colors
        val selected = userAgentMode == mode
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) colors.softPrimary else androidx.compose.ui.graphics.Color.Transparent)
                .clickable(
                    enabled = !clearing && !interaction.restarting,
                    role = Role.Button,
                    onClick = { changeUserAgentMode(mode) },
                )
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) colors.primary.copy(alpha = 0.12f) else colors.surfaceMuted),
                contentAlignment = Alignment.Center,
            ) {
                ToolBoxIcon(
                    ToolBoxIconKey.Device,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = if (selected) colors.primary else colors.textSecondary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                ToolBoxText(
                    label,
                    maxLines = 1,
                    style = ToolBoxThemeTokens.textStyles.body.copy(
                        color = colors.textPrimary,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Spacer(Modifier.height(2.dp))
                ToolBoxText(
                    description,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = ToolBoxThemeTokens.textStyles.metadata.copy(
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    ),
                )
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                ToolBoxIcon(
                    ToolBoxIconKey.Check,
                    contentDescription = "当前网页身份",
                    modifier = Modifier.size(19.dp),
                    tint = colors.primary,
                )
            }
        }
    }

    @Composable
    private fun BrowserMenuAction(
        label: String,
        icon: ToolBoxIconKey,
        destructive: Boolean = false,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) {
        val colors = ToolBoxThemeTokens.colors
        val contentColor = when {
            !enabled -> ToolBoxThemeTokens.disabledContent
            destructive -> colors.onSoftDanger
            else -> colors.textPrimary
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolBoxIcon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = when {
                    !enabled -> ToolBoxThemeTokens.disabledContent
                    destructive -> colors.danger
                    else -> colors.textSecondary
                },
            )
            Spacer(Modifier.width(12.dp))
            ToolBoxText(
                label,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = ToolBoxThemeTokens.textStyles.body.copy(
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Normal,
                    color = if (destructive && enabled) colors.danger else contentColor,
                ),
            )
        }
    }

    private companion object {
        const val BROWSER_PREFERENCES = "browser_settings"
        const val USER_AGENT_MODE_KEY = "user_agent_mode"
        const val MEDIA_DIAGNOSTIC_SETTLE_MS = 2500L
        const val MEDIA_LAYOUT_COMPAT_RETRY_MS = 1500L
        const val MAX_MEDIA_DIAGNOSTIC_EVENTS = 30
        val cookieWrites = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

