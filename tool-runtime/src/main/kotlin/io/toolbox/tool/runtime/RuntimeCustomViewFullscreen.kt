package io.toolbox.tool.runtime

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * Element fullscreen (`requestFullscreen`, a video's fullscreen button) for a runtime WebView.
 *
 * Only a tool whose manifest sets `ui.allowFullscreen` gets it: the custom view covers the tool's
 * own container and the system bars hide until Back, `exitFullscreen()` or the page leaving the
 * window. Every other tool is refused at once, so the page leaves fullscreen instead of waiting on
 * a view nobody shows.
 */
internal class RuntimeCustomViewFullscreen(
    private val webView: WebView,
    private val allowed: Boolean,
) {
    private var customView: View? = null
    private var callback: WebChromeClient.CustomViewCallback? = null
    private var window: Window? = null
    private var hiddenTypes = 0
    private var previousBehavior = WindowInsetsController.BEHAVIOR_DEFAULT
    private var backDispatcher: OnBackInvokedDispatcher? = null
    private val backCallback = OnBackInvokedCallback { exit() }
    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) = exit()
    }

    val showing: Boolean get() = customView != null

    fun show(view: View, viewCallback: WebChromeClient.CustomViewCallback) {
        val container = webView.parent as? ViewGroup
        val activity = RuntimeJavaScriptDialogs.activity(webView.rootView.context)
            ?: container?.let { RuntimeJavaScriptDialogs.activity(it.context) }
        if (!allowed || showing || container == null || activity == null ||
            activity.isFinishing || activity.isDestroyed || !webView.isAttachedToWindow
        ) {
            viewCallback.onCustomViewHidden()
            return
        }
        customView = view
        callback = viewCallback
        view.setBackgroundColor(Color.BLACK)
        container.addView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        webView.addOnAttachStateChangeListener(detach)
        val targetWindow = activity.window
        window = targetWindow
        targetWindow.insetsController?.let { controller ->
            // Restore only the bars this call hides, so a host-hidden navigation bar stays hidden.
            val insets = targetWindow.decorView.rootWindowInsets
            hiddenTypes = listOf(WindowInsets.Type.statusBars(), WindowInsets.Type.navigationBars())
                .filter { insets?.isVisible(it) != false }
                .fold(0) { types, type -> types or type }
            previousBehavior = controller.systemBarsBehavior
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (hiddenTypes != 0) controller.hide(hiddenTypes)
        }
        // Above the host's own Back handling (the runtime's leave confirmation).
        backDispatcher = activity.onBackInvokedDispatcher.also {
            it.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, backCallback)
        }
    }

    /** Leaves fullscreen and tells the page; safe to call when nothing is shown. */
    fun exit() {
        val view = customView ?: return
        customView = null
        webView.removeOnAttachStateChangeListener(detach)
        (view.parent as? ViewGroup)?.removeView(view)
        backDispatcher?.unregisterOnBackInvokedCallback(backCallback)
        backDispatcher = null
        window?.insetsController?.let { controller ->
            if (hiddenTypes != 0) controller.show(hiddenTypes)
            controller.systemBarsBehavior = previousBehavior
        }
        window = null
        hiddenTypes = 0
        callback?.also { callback = null }?.onCustomViewHidden()
    }
}
