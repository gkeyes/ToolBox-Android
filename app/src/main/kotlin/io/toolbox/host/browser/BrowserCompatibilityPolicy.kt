package io.toolbox.host.browser

import android.webkit.WebSettings

/**
 * Browser-first defaults for ordinary web content.
 *
 * Stay close to Chromium/WebView behavior, explicitly enable capabilities real sites depend on,
 * and keep sensitive device capabilities behind WebChromeClient permission callbacks.
 */
internal object BrowserCompatibilityPolicy {
    fun apply(settings: WebSettings) = with(settings) {
        javaScriptEnabled = true
        domStorageEnabled = true

        allowFileAccess = false
        allowContentAccess = false
        loadsImagesAutomatically = true
        blockNetworkImage = false
        blockNetworkLoads = false
        cacheMode = WebSettings.LOAD_DEFAULT

        useWideViewPort = true
        loadWithOverviewMode = false
        textZoom = 100
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false

        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // Keep one long-lived WebView for memory stability. With multi-window disabled,
        // target=_blank becomes a top-level navigation instead of allocating a second WebView.
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false

        // Avoid a second host-only gesture gate on modern media players.
        mediaPlaybackRequiresUserGesture = false

        // Keep the engine plumbing available; the permission callback still denies device location.
        setGeolocationEnabled(true)
    }

    fun applyCompat(settings: WebSettings) {
        // Keep Android System WebView defaults. The host should not layer extra blocking policy
        // on top of ordinary browsing behavior.
    }
}
