package io.toolbox.host.browser

import android.webkit.WebSettings
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

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
        // Keep legacy desktop-style pages usable on phones. Sites such as NewSMTH return
        // a compact verification form without a viewport meta tag; overview mode is what
        // lets WebView fit that old layout into the mobile viewport instead of presenting
        // it at desktop scale.
        loadWithOverviewMode = true
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
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
        }
        // Media Integrity remains at the platform default. Experimental BFCache/prerender toggles
        // stay untouched: predictable memory and navigation behavior is more important here.
    }
}
