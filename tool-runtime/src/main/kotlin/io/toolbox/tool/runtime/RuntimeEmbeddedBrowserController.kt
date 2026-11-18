package io.toolbox.tool.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One official player, in the local article's slot, without any tool bridge in the remote WebView. */
internal class RuntimeEmbeddedBrowserController(
    private val owner: WebView,
    private val allowFullscreen: Boolean,
    private val createPlayer: (Context) -> WebView = ::WebView,
) : RuntimeEmbeddedBrowserHandler {
    private class Player(
        val id: String,
        val view: WebView,
        val clip: FrameLayout,
        val fullscreen: RuntimeCustomViewFullscreen,
        var bounds: RuntimeEmbeddedBrowserBounds,
        var visible: Boolean = true,
    )

    private var player: Player? = null
    @Volatile private var released = false
    private val main = Handler(Looper.getMainLooper())
    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = closeCurrent()
    }
    private val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        player?.let { current -> runCatching { position(current) }.onFailure { closeCurrent() } }
    }

    init {
        owner.addOnAttachStateChangeListener(detach)
        owner.addOnLayoutChangeListener(layout)
    }

    override suspend fun open(
        url: String,
        bounds: RuntimeEmbeddedBrowserBounds,
        beforePresent: suspend () -> Unit,
    ): String = withContext(Dispatchers.Main.immediate) {
        val target = normalizedYouTubeEmbedUrl(url)
        beforePresent()
        currentCoroutineContext().ensureActive()
        val container = requireContainer()
        requireNotNull(bounds.layout(owner.width, owner.height)) { "The player has no visible article slot" }
        closeCurrent()
        val id = "embedded-" + UUID.randomUUID().toString().replace("-", "")
        val view = createPlayer(owner.context)
        val clip = RuntimeEmbeddedBrowserSurface(owner.context, owner) { player?.fullscreen?.showing == true }.apply {
            clipChildren = true
            clipToPadding = true
            setBackgroundColor(Color.BLACK)
        }
        val fullscreen = RuntimeCustomViewFullscreen(view, allowFullscreen,
            fullscreenContainer = { owner.parent as? ViewGroup },
            onExit = { player?.takeIf { it.view === view }?.let(::position) },
        )
        val current = Player(id, view, clip, fullscreen, bounds)
        player = current
        try {
            configure(current, target)
            clip.addView(view)
            container.addView(clip)
            position(current)
            // Google requires the Android app's identity for a directly loaded embedded player.
            view.loadUrl(target, mapOf("Referer" to "https://${owner.context.packageName}"))
            id
        } catch (failure: Throwable) {
            closeCurrent()
            throw failure
        }
    }

    override suspend fun update(
        id: String,
        bounds: RuntimeEmbeddedBrowserBounds,
        beforePresent: suspend () -> Unit,
    ) = withContext(Dispatchers.Main.immediate) {
        beforePresent()
        currentCoroutineContext().ensureActive()
        requireContainer()
        val current = player?.takeIf { it.id == id } ?: throw RuntimeHandlerException(
            RuntimeRpcErrorCode.NOT_FOUND, "正文播放器已关闭，请重新点击播放。",
        )
        bounds.layout(owner.width, owner.height)
        current.bounds = bounds
        position(current)
    }

    override suspend fun close(id: String) = withContext(Dispatchers.Main.immediate) {
        if (player?.id == id) closeCurrent()
    }

    override fun resetForNavigation() = onMain { closeCurrent() }

    override fun setForeground(foreground: Boolean) {
        if (!foreground) onMain { closeCurrent() }
    }

    override fun release() {
        released = true
        onMain {
            closeCurrent()
            owner.removeOnAttachStateChangeListener(detach)
            owner.removeOnLayoutChangeListener(layout)
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    private fun requireContainer(): FrameLayout {
        if (released || !owner.isAttachedToWindow || !owner.isShown || !owner.hasWindowFocus()) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "请在前台打开文章后播放视频。")
        }
        return owner.parent as? FrameLayout ?: throw RuntimeHandlerException(
            RuntimeRpcErrorCode.UNSUPPORTED, "当前页面暂不支持正文播放，请重新打开文章。",
        )
    }

    private fun position(current: Player) {
        if (player !== current) return
        val bounds = current.bounds.layout(owner.width, owner.height)
        val visible = bounds != null
        current.clip.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        if (bounds != null) {
            current.clip.layoutParams = FrameLayout.LayoutParams(bounds.width, bounds.height).apply {
                leftMargin = owner.left + bounds.left
                topMargin = owner.top + bounds.top
            }
            current.view.layoutParams = FrameLayout.LayoutParams(bounds.playerWidth, bounds.playerHeight).apply {
                leftMargin = bounds.playerLeft
                topMargin = bounds.playerTop
            }
        }
        if (current.fullscreen.showing) return
        if (visible != current.visible) {
            if (visible) current.view.onResume() else {
                current.view.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(media){media.pause();})", null)
                current.view.onPause()
            }
        }
        current.visible = visible
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(current: Player, target: String) = with(current.view) {
        setBackgroundColor(Color.BLACK)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setGeolocationEnabled(false)
        }
        // Do not change the process/profile-wide cookie switch used by the isolated tool runtime.
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        setDownloadListener { _, _, _, _, _ -> }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame || !allowedSubresource(request.url)

            @Deprecated("WebView compatibility callback")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.isForMainFrame) {
                    return if (request.url.toString() == target) null else RuntimePolicy.blockedResponse()
                }
                return if (allowedSubresource(request.url)) null else RuntimePolicy.blockedResponse()
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) = handler.cancel()

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (player === current) closeCurrent()
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) = current.fullscreen.show(view, callback)
            override fun onHideCustomView() = current.fullscreen.exit()
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
                callback.invoke(origin, false, false)
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean = false
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                callback.onReceiveValue(null)
                return true
            }
        }
    }

    private fun allowedSubresource(uri: Uri): Boolean =
        uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
            !uri.host.isNullOrEmpty() && uri.host?.endsWith(".toolbox.invalid") != true

    private fun closeCurrent() {
        val current = player ?: return
        player = null
        runCatching { current.fullscreen.exit() }
        runCatching { (current.clip.parent as? ViewGroup)?.removeView(current.clip) }
        runCatching { current.clip.removeView(current.view) }
        runCatching { current.view.stopLoading() }
        runCatching { current.view.onPause() }
        runCatching { current.view.destroy() }
        RuntimeBridgeLifecycle.emitEvent(owner, "browser.embeddedClosed", RpcValue.ObjectValue(
            mapOf("id" to RpcValue.StringValue(current.id)),
        ))
    }
}
