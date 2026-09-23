package io.toolbox.host.browser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
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
import androidx.compose.foundation.background
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
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
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
import io.toolbox.tool.runtime.validateRuntimeBrowserUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Ordinary web content: no asset loader, tool profile, bridge, injected script or native RPC. */
class BrowserActivity : ComponentActivity() {
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
    private var clearConfirmation by mutableStateOf(false)
    private var fullAddress by mutableStateOf(false)
    private var clearing by mutableStateOf(false)
    private var fullScreenView by mutableStateOf<View?>(null)
    private var fullScreenCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        address = validUrl(savedInstanceState?.getString("address") ?: intent.dataString.orEmpty()) ?: run {
            finish()
            return
        }
        enableEdgeToEdge()
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
            page.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                // Surface a clear denial through onGeolocationPermissionsShowPrompt.
                setGeolocationEnabled(true)
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
                // User-activated target=_blank links use this view; unsolicited windows stay disabled.
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                mediaPlaybackRequiresUserGesture = true
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(page, false)
            page.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (view !== webView) return true
                    if (validUrl(request.url.toString()) != null) return false
                    if (request.isForMainFrame) unsupported("此链接需要其他应用，请从菜单选择系统浏览器。")
                    return true
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (view !== webView) return
                    validUrl(url)?.let { address = it }
                    title = ""
                    error = null
                    loadProgress = 0
                    interaction = interaction.pageStarted()
                    updateNavigation(view)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (view !== webView) return
                    interaction = interaction.pageFinished()
                    loadProgress = 100
                    updateNavigation(view)
                    flushCookies()
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    if (view !== webView) return
                    validUrl(url)?.let { address = it }
                    updateNavigation(view)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                    if (view !== webView) return
                    if (request.isForMainFrame && !interaction.stoppedByUser) {
                        error = "网页加载失败，请检查网络后重试。"
                        interaction = interaction.pageFinished()
                        loadProgress = 100
                    }
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (view !== webView) return
                    if (request.isForMainFrame) unsupported("网站返回 HTTP ${response.statusCode}，可重试或查看网站提示。")
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, failure: SslError) {
                    handler.cancel()
                    if (view !== webView) return
                    error = "网站证书无法验证，连接已停止。请检查网址或稍后重试。"
                    interaction = interaction.pageFinished()
                    loadProgress = 100
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
                override fun onConsoleMessage(message: ConsoleMessage): Boolean = true
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (view !== webView || interaction.stoppedByUser) return
                    loadProgress = newProgress.coerceIn(0, 100)
                    if (loadProgress == 100) interaction = interaction.pageFinished()
                }
                override fun onReceivedTitle(view: WebView, newTitle: String?) {
                    if (view === webView) title = newTitle.orEmpty()
                }
                override fun onPermissionRequest(request: PermissionRequest) {
                    request.deny()
                    if (page === webView) unsupported("内置浏览器不提供摄像头或麦克风权限，可从菜单选择系统浏览器。")
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
                    if (page !== webView || fullScreenView != null) { callback.onCustomViewHidden(); return }
                    fullScreenCallback = callback
                    fullScreenView = view
                }
                override fun onHideCustomView() { if (page === webView) hideFullScreen() }
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
            }
        } catch (_: android.util.AndroidRuntimeException) {
            webView?.let(::destroyPage)
            interaction = BrowserInteractionState()
            error = "Android System WebView 不可用，请启用或更新系统 WebView 后重试。"
        }
    }

    private fun validUrl(url: String): String? = try { validateRuntimeBrowserUrl(url) } catch (_: IllegalArgumentException) { null }

    private fun updateNavigation(page: WebView) {
        if (page !== webView) return
        canBack = page.canGoBack()
        canForward = page.canGoForward()
    }

    private fun reload() {
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
            fullScreenView != null -> hideFullScreen()
            !interaction.unresponsive && !interaction.restarting && webView?.canGoBack() == true -> webView?.goBack()
            else -> finish()
        }
    }

    private fun hideFullScreen() {
        (fullScreenView?.parent as? ViewGroup)?.removeView(fullScreenView)
        fullScreenView = null
        fullScreenCallback?.onCustomViewHidden()
        fullScreenCallback = null
    }

    private fun destroyPage(page: WebView) {
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
        webView?.onResume()
        resumed = true
    }

    override fun onDestroy() {
        resumed = false
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
        Box(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding().imePadding()) {
            Column(Modifier.fillMaxSize()) {
                BrowserToolbar()
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    if (interaction.loading && loadProgress in 0..99) {
                        Box(Modifier.fillMaxWidth((loadProgress / 100f).coerceAtLeast(0.02f))
                            .fillMaxHeight().background(colors.primary))
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
            }
            fullScreenView?.let { view -> AndroidView(factory = { view }, modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) }
        }
        if (menu) ToolBoxModalDialog(onDismissRequest = { menu = false }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ToolBoxText("浏览器菜单", Modifier.weight(1f).semantics { heading() },
                    style = ToolBoxThemeTokens.textStyles.title.copy(color = colors.textPrimary))
                ToolBoxIconButton(ToolBoxIconKey.Close, "关闭菜单", { menu = false })
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth()
                .semantics { paneTitle = "浏览器菜单" }
                .clip(RoundedCornerShape(ToolBoxThemeTokens.radii.denseSurface))) {
                BrowserMenuAction("网页前进", ToolBoxIconKey.ChevronRight,
                    enabled = canForward && !clearing && !interaction.unresponsive && !interaction.restarting) {
                    menu = false
                    webView?.goForward()
                }
                ToolBoxGroupDivider(startPadding = 52.dp, endPadding = 14.dp)
                BrowserMenuAction("复制链接", ToolBoxIconKey.Clipboard) { copyAddress(); menu = false }
                ToolBoxGroupDivider(startPadding = 52.dp, endPadding = 14.dp)
                BrowserMenuAction("分享链接", ToolBoxIconKey.Share) {
                    menu = false
                    try {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, address), "分享链接"))
                    } catch (_: android.content.ActivityNotFoundException) { unsupported("没有可用的分享应用。") }
                }
                ToolBoxGroupDivider(startPadding = 52.dp, endPadding = 14.dp)
                BrowserMenuAction("使用系统浏览器", ToolBoxIconKey.Globe) { menu = false; openSystemBrowser() }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(ToolBoxThemeTokens.radii.denseSurface))
                .background(colors.softDanger)) {
                BrowserMenuAction("清除浏览器网站数据", ToolBoxIconKey.Shield, destructive = true,
                    enabled = !clearing && !interaction.restarting) {
                    menu = false
                    clearConfirmation = true
                }
            }
        }
        if (fullAddress) ToolBoxModalDialog(onDismissRequest = { fullAddress = false }) {
            ToolBoxText(
                title.ifBlank { "网页地址" },
                modifier = Modifier.semantics { heading() },
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = ToolBoxThemeTokens.textStyles.title.copy(
                    color = colors.textPrimary, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.height(12.dp))
            ToolBoxText(
                if (Uri.parse(address).scheme == "http") "完整地址 · 当前连接未加密" else "完整地址",
                style = ToolBoxThemeTokens.textStyles.metadata.copy(color = colors.textSecondary),
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.text.selection.SelectionContainer {
                ToolBoxText(address, modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState()),
                    style = ToolBoxThemeTokens.textStyles.body.copy(color = colors.textSecondary))
            }
            Spacer(Modifier.height(16.dp))
            ToolBoxTextButton("复制链接", ::copyAddress, Modifier.fillMaxWidth().testTag("browser_copy_address"), outlined = false)
            Spacer(Modifier.height(8.dp))
            ToolBoxTextButton("使用系统浏览器", ::openSystemBrowser, Modifier.fillMaxWidth(), outlined = false)
            Spacer(Modifier.height(8.dp))
            ToolBoxSecondaryButton("关闭", { fullAddress = false }, modifier = Modifier.fillMaxWidth())
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
        if (interaction.showRecoveryPrompt && resumed && !menu && !fullAddress && !clearConfirmation) {
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
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp).testTag("browser_toolbar"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrowserToolbarButton(ToolBoxIconKey.Close, "关闭浏览器", ::finish)
            BrowserToolbarButton(ToolBoxIconKey.Back, "网页后退", { webView?.goBack() },
                enabled = canBack && !clearing && !interaction.unresponsive && !interaction.restarting)
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button, onClickLabel = "查看完整地址", onClick = { fullAddress = true })
                    .padding(horizontal = 6.dp, vertical = 8.dp).testTag("browser_address"),
                contentAlignment = Alignment.Center,
            ) {
                ToolBoxText(
                    (if (interaction.unresponsive) "未响应 · " else if (unencrypted) "未加密 · " else "") + uri.host.orEmpty(),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = ToolBoxThemeTokens.textStyles.body.copy(
                        fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                        color = if (unencrypted || interaction.unresponsive) colors.danger else colors.textPrimary,
                    ),
                )
            }
            BrowserToolbarButton(
                ToolBoxIconKey.Refresh,
                when (action) {
                    BrowserLoadAction.Stop -> "停止加载"
                    BrowserLoadAction.Recover -> "查看网页恢复选项"
                    else -> "重新加载"
                },
                ::performLoadAction,
                enabled = action != BrowserLoadAction.Disabled,
                stop = action == BrowserLoadAction.Stop,
            )
            BrowserToolbarButton(ToolBoxIconKey.More, "浏览器菜单", { menu = true })
        }
    }

    @Composable
    private fun BrowserToolbarButton(
        icon: ToolBoxIconKey,
        label: String,
        onClick: () -> Unit,
        enabled: Boolean = true,
        stop: Boolean = false,
    ) {
        val tint = if (enabled) ToolBoxThemeTokens.colors.textSecondary else ToolBoxThemeTokens.disabledContent
        // Compact artwork, not compact hit areas. No gesture detector competes with webpage input.
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            if (stop) Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(tint))
            else ToolBoxIcon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
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
            modifier = Modifier.fillMaxWidth().heightIn(min = ToolBoxThemeTokens.sizes.touchTarget)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolBoxIcon(icon, contentDescription = null,
                tint = if (enabled && !destructive) colors.textSecondary else contentColor)
            Spacer(Modifier.width(14.dp))
            ToolBoxText(label, modifier = Modifier.weight(1f),
                style = ToolBoxThemeTokens.textStyles.body.copy(color = contentColor))
        }
    }

    private companion object {
        val cookieWrites = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
