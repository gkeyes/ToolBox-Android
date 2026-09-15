package io.toolbox.tool.runtime

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.widget.FrameLayout

/** Minimal visible host for the Wasm test, using production WebView creation and cleanup. */
class WasmRuntimeTestActivity : Activity() {
    private lateinit var content: FrameLayout
    private var activeWebView: WebView? = null
    private var rendererGone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = FrameLayout(this)
        setContentView(content)
    }

    fun attach(
        runtime: PreparedToolRuntime,
        creationPermit: RuntimeCreationPermit,
        callbacks: RuntimeWebViewCallbacks,
        bridgeProvider: RuntimeBridgeProvider,
    ): WebView {
        disposeActiveWebView()
        rendererGone = false
        val creation = HardenedRuntimeWebView.create(
            this,
            runtime,
            creationPermit,
            callbacks.copy(onRendererGone = {
                rendererGone = true
                callbacks.onRendererGone()
            }),
            bridgeProvider,
        )
        val webView = when (creation) {
            is RuntimeWebViewCreationResult.Created -> creation.webView
            is RuntimeWebViewCreationResult.Failed -> error(creation.message)
        }
        activeWebView = webView
        content.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        return webView
    }

    fun disposeActiveWebView() {
        val webView = activeWebView ?: return
        content.removeView(webView)
        if (!rendererGone) HardenedRuntimeWebView.release(webView)
        activeWebView = null
        rendererGone = false
    }

    override fun onDestroy() {
        disposeActiveWebView()
        super.onDestroy()
    }
}
