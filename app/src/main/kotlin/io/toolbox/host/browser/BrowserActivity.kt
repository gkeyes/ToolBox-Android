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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
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
                    if (validUrl(request.url.toString()) != null) return false
                    if (request.isForMainFrame) unsupported("此链接需要其他应用，请从菜单选择系统浏览器。")
                    return true
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    validUrl(url)?.let { address = it }
                    title = ""
                    error = null
                    updateNavigation(view)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    updateNavigation(view)
                    flushCookies()
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    validUrl(url)?.let { address = it }
                    updateNavigation(view)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                    if (request.isForMainFrame) {
                        error = "网页加载失败，请检查网络后重试。"
                        loadProgress = 100
                    }
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) unsupported("网站返回 HTTP ${response.statusCode}，可重试或查看网站提示。")
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, failure: SslError) {
                    handler.cancel()
                    error = "网站证书无法验证，连接已停止。请检查网址或稍后重试。"
                    loadProgress = 100
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    hideFullScreen()
                    (view.parent as? ViewGroup)?.removeView(view)
                    webView = null
                    view.destroy()
                    error = "网页进程已退出，点击重新加载可恢复浏览。"
                    loadProgress = 100
                    canBack = false
                    canForward = false
                    return true
                }
            }
            page.webChromeClient = object : WebChromeClient() {
                // Websites may log form values or response bodies; never forward these to Logcat.
                override fun onConsoleMessage(message: ConsoleMessage): Boolean = true
                override fun onProgressChanged(view: WebView, newProgress: Int) { loadProgress = newProgress }
                override fun onReceivedTitle(view: WebView, newTitle: String?) { title = newTitle.orEmpty() }
                override fun onPermissionRequest(request: PermissionRequest) {
                    request.deny()
                    unsupported("内置浏览器不提供摄像头或麦克风权限，可从菜单选择系统浏览器。")
                }
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                    callback.invoke(origin, false, false)
                    unsupported("内置浏览器不提供定位权限，可从菜单选择系统浏览器。")
                }
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    callback.onReceiveValue(null)
                    unsupported("内置浏览器暂不支持上传文件，可从菜单选择系统浏览器。")
                    return true
                }
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (fullScreenView != null) { callback.onCustomViewHidden(); return }
                    fullScreenCallback = callback
                    fullScreenView = view
                }
                override fun onHideCustomView() { hideFullScreen() }
            }
            page.setDownloadListener { _, _, _, _, _ ->
                unsupported("内置浏览器暂不支持下载，可从菜单选择系统浏览器。")
            }
            if (savedState == null || page.restoreState(savedState) == null) page.loadUrl(address)
        } catch (_: android.util.AndroidRuntimeException) {
            error = "Android System WebView 不可用，请启用或更新系统 WebView 后重试。"
        }
    }

    private fun validUrl(url: String): String? = try { validateRuntimeBrowserUrl(url) } catch (_: IllegalArgumentException) { null }

    private fun updateNavigation(page: WebView) {
        canBack = page.canGoBack()
        canForward = page.canGoForward()
    }

    private fun reload() {
        error = null
        if (webView == null) createPage() else webView?.reload()
    }

    private fun goBack() {
        val decor = window.decorView
        when {
            ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == true ->
                WindowInsetsControllerCompat(window, decor).hide(WindowInsetsCompat.Type.ime())
            fullScreenView != null -> hideFullScreen()
            webView?.canGoBack() == true -> webView?.goBack()
            else -> finish()
        }
    }

    private fun hideFullScreen() {
        (fullScreenView?.parent as? ViewGroup)?.removeView(fullScreenView)
        fullScreenView = null
        fullScreenCallback?.onCustomViewHidden()
        fullScreenCallback = null
    }

    private fun unsupported(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun flushCookies() {
        // Closing the Activity must not cancel its final cookie write.
        cookieWrites.launch { CookieManager.getInstance().flush() }
    }

    private fun clearWebsiteData() {
        clearing = true
        hideFullScreen()
        webView?.let { page ->
            page.stopLoading()
            page.clearCache(true)
            (page.parent as? ViewGroup)?.removeView(page)
            page.destroy()
        }
        webView = null
        val completed = Runnable {
            flushCookies()
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
                error = "网站登录、缓存及常规存储已清除。当前系统 WebView 不支持完整清除，请更新 WebView 后再次清除。"
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("address", address)
        webView?.let { page -> outState.putBundle("page", Bundle().also { page.saveState(it) }) }
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        webView?.onPause()
        flushCookies()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        hideFullScreen()
        webView?.let { page ->
            (page.parent as? ViewGroup)?.removeView(page)
            page.destroy()
        }
        webView = null
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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ToolBoxIconButton(ToolBoxIconKey.Close, "关闭浏览器", ::finish)
                    Column(Modifier.weight(1f)) {
                        ToolBoxText(title.ifBlank { "内置浏览器" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val uri = Uri.parse(address)
                        ToolBoxText(
                            (if (uri.scheme == "http") "未加密 · " else "") + uri.host.orEmpty(),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ToolBoxTextButton("菜单", { menu = true }, outlined = false)
                }
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    if (loadProgress in 0..99) Box(Modifier.fillMaxWidth(loadProgress / 100f).fillMaxHeight().background(colors.primary))
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
                            ToolBoxTextButton("重新加载", ::reload, enabled = !clearing)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ToolBoxIconButton(ToolBoxIconKey.Back, "网页后退", { webView?.goBack() }, enabled = canBack)
                    ToolBoxIconButton(ToolBoxIconKey.ChevronRight, "网页前进", { webView?.goForward() }, enabled = canForward)
                    ToolBoxIconButton(ToolBoxIconKey.Refresh, "重新加载", ::reload, enabled = !clearing)
                }
            }
            fullScreenView?.let { view -> AndroidView(factory = { view }, modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) }
        }
        if (menu) ToolBoxModalDialog(onDismissRequest = { menu = false }) {
            Column(Modifier.fillMaxWidth()
                .semantics { paneTitle = "浏览器菜单" }
                .clip(RoundedCornerShape(ToolBoxThemeTokens.radii.denseSurface))) {
                BrowserMenuAction("查看完整地址", ToolBoxIconKey.Note) { menu = false; fullAddress = true }
                ToolBoxGroupDivider(startPadding = 52.dp, endPadding = 14.dp)
                BrowserMenuAction("复制链接", ToolBoxIconKey.Clipboard) {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("网址", address))
                    menu = false
                }
                ToolBoxGroupDivider(startPadding = 52.dp, endPadding = 14.dp)
                BrowserMenuAction("分享链接", ToolBoxIconKey.Share) {
                    menu = false
                    try {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, address), "分享链接"))
                    } catch (_: android.content.ActivityNotFoundException) { unsupported("没有可用的分享应用。") }
                }
                ToolBoxGroupDivider(startPadding = 52.dp, endPadding = 14.dp)
                BrowserMenuAction("使用系统浏览器", ToolBoxIconKey.Globe) {
                    menu = false
                    try { startActivity(browserViewIntent(address)) }
                    catch (_: android.content.ActivityNotFoundException) { unsupported("没有可用的系统浏览器。") }
                    catch (_: SecurityException) { unsupported("系统阻止了外部浏览器启动。") }
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(ToolBoxThemeTokens.radii.denseSurface))
                .background(colors.softDanger)) {
                BrowserMenuAction("清除浏览器网站数据", ToolBoxIconKey.Shield, destructive = true) {
                    menu = false
                    clearConfirmation = true
                }
            }
            Spacer(Modifier.height(12.dp))
            ToolBoxSecondaryButton("取消", { menu = false }, modifier = Modifier.fillMaxWidth())
        }
        if (fullAddress) ToolBoxModalDialog(onDismissRequest = { fullAddress = false }) {
            ToolBoxText(
                "查看完整地址",
                modifier = Modifier.semantics { heading() },
                style = ToolBoxThemeTokens.textStyles.title.copy(
                    color = colors.textPrimary, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.height(16.dp))
            androidx.compose.foundation.text.selection.SelectionContainer {
                ToolBoxText(address, modifier = Modifier.fillMaxWidth(),
                    style = ToolBoxThemeTokens.textStyles.body.copy(color = colors.textSecondary))
            }
            Spacer(Modifier.height(24.dp))
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
    }

    @Composable
    private fun BrowserMenuAction(
        label: String,
        icon: ToolBoxIconKey,
        destructive: Boolean = false,
        onClick: () -> Unit,
    ) {
        val colors = ToolBoxThemeTokens.colors
        val contentColor = if (destructive) colors.onSoftDanger else colors.textPrimary
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = ToolBoxThemeTokens.sizes.touchTarget)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolBoxIcon(icon, contentDescription = null,
                tint = if (destructive) colors.onSoftDanger else colors.textSecondary)
            Spacer(Modifier.width(14.dp))
            ToolBoxText(label, modifier = Modifier.weight(1f),
                style = ToolBoxThemeTokens.textStyles.body.copy(color = contentColor))
        }
    }

    private companion object {
        val cookieWrites = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
