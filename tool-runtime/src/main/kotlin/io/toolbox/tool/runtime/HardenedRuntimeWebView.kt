package io.toolbox.tool.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.os.Trace
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

data class RuntimeWebViewCallbacks(
    val onMainEntryLoaded: () -> Unit,
    val onMainEntryFailed: (String) -> Unit,
    val onRendererGone: () -> Unit,
)

sealed interface RuntimeWebViewCreationResult {
    data class Created(val webView: WebView) : RuntimeWebViewCreationResult
    data class Failed(val message: String) : RuntimeWebViewCreationResult
}

object HardenedRuntimeWebView {
    fun release(webView: WebView) {
        (webView.webViewClient as? RuntimeWebViewClient)?.endFirstMainFrameTrace()
        RuntimeWebViewLifecycle.destroyAndUnregister(webView)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun create(
        context: Context,
        runtime: PreparedToolRuntime,
        creationPermit: RuntimeCreationPermit,
        callbacks: RuntimeWebViewCallbacks,
    ): RuntimeWebViewCreationResult {
        Trace.beginSection("webView.create")
        return try {
            createTraced(context, runtime, creationPermit, callbacks)
        } finally {
            Trace.endSection()
        }
    }

    private fun createTraced(
        context: Context,
        runtime: PreparedToolRuntime,
        creationPermit: RuntimeCreationPermit,
        callbacks: RuntimeWebViewCallbacks,
    ): RuntimeWebViewCreationResult {
        var webView: WebView? = null
        var runtimeClient: RuntimeWebViewClient? = null
        try {
            WebView.setWebContentsDebuggingEnabled(false)
            val createdWebView = WebView(context)
            webView = createdWebView
            if (creationPermit.isolationMode == RuntimeIsolationMode.DEDICATED_PROFILE) {
                WebViewCompat.setProfile(createdWebView, runtime.profileName)
            } else {
                val documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                val serviceWorkerBasic = WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)
                val serviceWorkerIntercept = WebViewFeature.isFeatureSupported(
                    WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST,
                )
                check(documentStartSupported && (!serviceWorkerBasic || serviceWorkerIntercept)) {
                    "Stateless WebView hardening is unavailable"
                }
                WebViewCompat.addDocumentStartJavaScript(
                    createdWebView,
                    STATELESS_API_HARDENING_SCRIPT,
                    setOf(runtime.origin.removeSuffix("/")),
                )
            }
            hardenSettings(createdWebView, creationPermit.isolationMode)
            hardenServiceWorkers()
            if (creationPermit.isolationMode == RuntimeIsolationMode.DEDICATED_PROFILE) {
                val profile = requireNotNull(ProfileStore.getInstance().getProfile(runtime.profileName)) {
                    "The dedicated WebView profile is unavailable"
                }
                disableCookies(createdWebView, profile.cookieManager)
            } else {
                val cookieManager = CookieManager.getInstance()
                disableCookies(createdWebView, cookieManager)
                check(cookieManager.getCookie(runtime.origin).isNullOrEmpty()) {
                    "The exact stateless origin already has cookies"
                }
            }
            val assetLoader = WebViewAssetLoader.Builder()
                .setDomain(RuntimeIdentity.originHost(runtime.toolId))
                .setHttpAllowed(false)
                .addPathHandler(
                    "/",
                    BundlePathHandler(runtime.privateFilesRoot, runtime.bundleRoot, runtime.securityProfile),
                )
                .build()
            runtimeClient = RuntimeWebViewClient(
                runtime = runtime,
                assetLoader = assetLoader,
                callbacks = callbacks,
                requireStatelessSentinel = creationPermit.isolationMode == RuntimeIsolationMode.ORIGIN_ONLY_STATELESS,
            )
            createdWebView.webViewClient = runtimeClient
            createdWebView.webChromeClient = RuntimeWebChromeClient()
            creationPermit.attach(createdWebView)
            runtimeClient.beginFirstMainFrameTrace()
            createdWebView.loadUrl(runtime.entryUrl)
            return RuntimeWebViewCreationResult.Created(createdWebView)
        } catch (_: RuntimeException) {
            runtimeClient?.endFirstMainFrameTrace()
            creationPermit.close()
            webView?.let(RuntimeWebViewLifecycle::destroyAndUnregister)
            return RuntimeWebViewCreationResult.Failed(
                "当前系统 WebView 无法创建安全工具环境，请更新 Android System WebView 后重试。",
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun hardenSettings(webView: WebView, isolationMode: RuntimeIsolationMode) = with(webView.settings) {
        javaScriptEnabled = true
        domStorageEnabled = isolationMode == RuntimeIsolationMode.DEDICATED_PROFILE
        databaseEnabled = isolationMode == RuntimeIsolationMode.DEDICATED_PROFILE
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        mediaPlaybackRequiresUserGesture = true
        loadsImagesAutomatically = true
        builtInZoomControls = false
        displayZoomControls = false
        setGeolocationEnabled(false)
        saveFormData = false
        cacheMode = WebSettings.LOAD_NO_CACHE
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(this, true)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOWNLOAD_FAVICONS_ENABLED)) {
            WebSettingsCompat.setDownloadFaviconsEnabled(this, false)
        }
    }

    private fun disableCookies(webView: WebView, cookieManager: CookieManager) {
        cookieManager.setAcceptCookie(false)
        cookieManager.setAcceptThirdPartyCookies(webView, false)
    }

    private fun hardenServiceWorkers() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) return
        val controller = ServiceWorkerControllerCompat.getInstance()
        val settings = controller.serviceWorkerWebSettings
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BLOCK_NETWORK_LOADS)) {
            settings.blockNetworkLoads = true
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_CONTENT_ACCESS)) {
            settings.allowContentAccess = false
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_FILE_ACCESS)) {
            settings.allowFileAccess = false
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_CACHE_MODE)) {
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) {
            controller.setServiceWorkerClient(
                object : ServiceWorkerClientCompat() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse =
                        RuntimePolicy.blockedResponse()
                },
            )
        }
    }

    private const val STATELESS_API_HARDENING_SCRIPT = """
        (() => {
          const unavailable = () => Promise.reject(new DOMException('Disabled by ToolBox', 'NotAllowedError'));
          const unavailableSync = () => { throw new DOMException('Disabled by ToolBox', 'NotAllowedError'); };
          const disabledCaches = Object.freeze({
            delete: unavailable,
            has: unavailable,
            keys: unavailable,
            match: unavailable,
            open: unavailable
          });
          const disabledStorage = Object.freeze({
            estimate: unavailable,
            getDirectory: unavailable,
            persist: unavailable,
            persisted: unavailable
          });
          const disabledBuckets = Object.freeze({
            delete: unavailable,
            keys: unavailable,
            open: unavailable
          });
          const harden = (target, name, value) => {
            let cursor = target;
            let ownLocked = false;
            while (cursor && cursor !== Object.prototype) {
              const descriptor = Object.getOwnPropertyDescriptor(cursor, name);
              if (descriptor) {
                Object.defineProperty(cursor, name, {
                  value,
                  writable: false,
                  enumerable: descriptor.enumerable,
                  configurable: false
                });
                if (cursor === target) ownLocked = true;
              }
              cursor = Object.getPrototypeOf(cursor);
            }
            if (!ownLocked) {
              Object.defineProperty(target, name, {
                value,
                writable: false,
                enumerable: false,
                configurable: false
              });
            }
          };
          try {
            harden(globalThis, 'caches', disabledCaches);
            harden(globalThis, 'indexedDB', undefined);
            harden(globalThis, 'localStorage', undefined);
            harden(globalThis, 'sessionStorage', undefined);
            harden(globalThis, 'storageBuckets', disabledBuckets);
            harden(globalThis, 'openDatabase', unavailableSync);
            harden(globalThis, 'requestFileSystem', unavailableSync);
            harden(globalThis, 'webkitRequestFileSystem', unavailableSync);
            harden(globalThis, 'resolveLocalFileSystemURL', unavailableSync);
            harden(globalThis, 'webkitResolveLocalFileSystemURL', unavailableSync);
            harden(navigator, 'serviceWorker', undefined);
            harden(navigator, 'storage', disabledStorage);
            harden(navigator, 'storageBuckets', disabledBuckets);
            Object.defineProperty(globalThis, '__toolboxStatelessHardened', {
              value: true,
              writable: false,
              enumerable: false,
              configurable: false
            });
          } catch (_) {
            try { Object.defineProperty(globalThis, '__toolboxStatelessHardened', { value: false }); } catch (_) {}
            try { window.stop(); } catch (_) {}
            try { document.documentElement?.replaceChildren(); } catch (_) {}
            try { location.replace('about:blank'); } catch (_) {}
          }
        })();
    """
}

private class RuntimeWebViewClient(
    private val runtime: PreparedToolRuntime,
    private val assetLoader: WebViewAssetLoader,
    private val callbacks: RuntimeWebViewCallbacks,
    private val requireStatelessSentinel: Boolean,
) : WebViewClient() {
    private var mainFrameTerminal = false
    private var sentinelCheckPending = false
    private var rendererGone = false
    private var firstMainFrameTraceOpen = false
    private val firstMainFrameTraceCookie = System.identityHashCode(this)

    fun beginFirstMainFrameTrace() {
        if (firstMainFrameTraceOpen) return
        firstMainFrameTraceOpen = true
        Trace.beginAsyncSection("webView.firstMainFrame", firstMainFrameTraceCookie)
    }

    fun endFirstMainFrameTrace() {
        if (!firstMainFrameTraceOpen) return
        firstMainFrameTraceOpen = false
        Trace.endAsyncSection("webView.firstMainFrame", firstMainFrameTraceCookie)
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        if (!RuntimeIdentity.isExactLocalUrl(request.url.toString(), runtime.origin)) {
            return RuntimePolicy.blockedResponse()
        }
        return assetLoader.shouldInterceptRequest(request.url) ?: RuntimePolicy.blockedResponse(404, "Not Found")
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !RuntimeIdentity.isExactLocalUrl(request.url.toString(), runtime.origin)

    @Deprecated("WebView compatibility callback")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        !RuntimeIdentity.isExactLocalUrl(url, runtime.origin)

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        if (url == runtime.entryUrl) {
            mainFrameTerminal = false
            sentinelCheckPending = false
        }
    }

    override fun onPageCommitVisible(view: WebView, url: String) {
        if (url == runtime.entryUrl) endFirstMainFrameTrace()
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (!mainFrameTerminal && !sentinelCheckPending && url == runtime.entryUrl) {
            if (!requireStatelessSentinel) {
                mainFrameTerminal = true
                endFirstMainFrameTrace()
                callbacks.onMainEntryLoaded()
                return
            }
            sentinelCheckPending = true
            view.evaluateJavascript("globalThis.__toolboxStatelessHardened === true") { result ->
                if (mainFrameTerminal) return@evaluateJavascript
                mainFrameTerminal = true
                endFirstMainFrameTrace()
                if (result == "true") {
                    callbacks.onMainEntryLoaded()
                } else {
                    view.stopLoading()
                    view.evaluateJavascript("document.documentElement?.replaceChildren()", null)
                    callbacks.onMainEntryFailed("当前 WebView 无法建立无状态安全环境，请更新后重试。")
                }
            }
        }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame && !mainFrameTerminal) {
            mainFrameTerminal = true
            endFirstMainFrameTrace()
            callbacks.onMainEntryFailed("工具入口加载失败，请重试。")
        }
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (request.isForMainFrame && !mainFrameTerminal) {
            mainFrameTerminal = true
            endFirstMainFrameTrace()
            callbacks.onMainEntryFailed("工具入口被安全策略拒绝，请重新导入。")
        }
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        endFirstMainFrameTrace()
        RuntimeWebViewLifecycle.destroyAndUnregister(view)
        if (!rendererGone) {
            rendererGone = true
            callbacks.onRendererGone()
        }
        return true
    }
}

private class RuntimeWebChromeClient : WebChromeClient() {
    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean {
        filePathCallback.onReceiveValue(null)
        return true
    }

    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean = false

    override fun onPermissionRequest(request: PermissionRequest) = request.deny()

    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        callback.invoke(origin, false, false)
    }

    override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
        result.cancel()
        return true
    }

    override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
        result.cancel()
        return true
    }

    override fun onJsPrompt(
        view: WebView,
        url: String,
        message: String,
        defaultValue: String,
        result: JsPromptResult,
    ): Boolean {
        result.cancel()
        return true
    }
}
