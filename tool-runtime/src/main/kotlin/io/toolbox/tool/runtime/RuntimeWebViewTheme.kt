package io.toolbox.tool.runtime

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.View
import android.webkit.WebView
import java.util.Collections
import java.util.IdentityHashMap

/** Native isLightTheme drives CSS prefers-color-scheme; a Compose palette alone does not. */
class RuntimeWebViewThemeContext(
    context: Context,
    dark: Boolean,
    configuration: Configuration = context.resources.configuration,
) : MutableContextWrapper(context.applicationContext) {
    private val application = context.applicationContext
    private var applied: Configuration? = null
    private var darkTheme: Boolean? = null
    private var foregroundActivity: Activity? = null

    init { update(dark, configuration) }

    fun update(dark: Boolean, configuration: Configuration): Boolean {
        val adjusted = Configuration(configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val owner = foregroundActivity?.takeUnless { it.isFinishing || it.isDestroyed }
        if (darkTheme == dark && applied == adjusted && foregroundActivity === owner) return false
        foregroundActivity = owner
        darkTheme = dark
        applied = adjusted
        rebuildBase()
        return true
    }

    /** Only retain the Activity while the WebView belongs to its visible window. */
    internal fun bindActivity(activity: Activity?) {
        val owner = activity?.takeUnless { it.isFinishing || it.isDestroyed }
        if (foregroundActivity === owner) return
        foregroundActivity = owner
        rebuildBase()
    }

    private fun rebuildBase() {
        // createConfigurationContext(Activity) would lose the Activity in the wrapper
        // chain. Override resources on the theme instead, keeping its WindowManager
        // and token available to Chromium's native select/date dialogs.
        baseContext = ContextThemeWrapper(
            foregroundActivity ?: application,
            if (darkTheme == true) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar,
        ).apply {
            applyOverrideConfiguration(Configuration(requireNotNull(applied)))
        }
    }
}

/** Reusable background WebView with a window-capable context only while displayed. */
@SuppressLint("ViewConstructor")
internal class RuntimeWindowWebView(context: Context) : WebView(context) {
    private fun bindWindowContext() {
        // Resolve the actual parent, never our own mutable context: it may still
        // reference a previous window during reparenting or Activity recreation.
        var ancestor = parent
        var owner: Activity? = null
        while (ancestor is View) {
            owner = findRuntimeActivity(ancestor.context)
            if (owner != null) break
            ancestor = ancestor.parent
        }
        (context as? RuntimeWebViewThemeContext)?.bindActivity(owner)
    }

    private fun releaseWindowContext() {
        (context as? RuntimeWebViewThemeContext)?.bindActivity(null)
    }

    override fun onAttachedToWindow() {
        // Bind before Chromium initializes its attached-window helpers.
        bindWindowContext()
        try {
            super.onAttachedToWindow()
        } catch (error: RuntimeException) {
            releaseWindowContext()
            throw error
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility == View.VISIBLE) bindWindowContext()
        try {
            super.onWindowVisibilityChanged(visibility)
        } finally {
            if (visibility != View.VISIBLE) releaseWindowContext()
        }
    }

    override fun onDetachedFromWindow() {
        try {
            // Let Chromium dismiss native popups before removing the window context.
            super.onDetachedFromWindow()
        } finally {
            releaseWindowContext()
        }
    }

    override fun destroy() {
        try {
            super.destroy()
        } finally {
            // Renderer-loss and failed-creation paths may destroy without detaching.
            releaseWindowContext()
        }
    }
}

private fun findRuntimeActivity(context: Context): Activity? {
    var current = context
    val seen = Collections.newSetFromMap(IdentityHashMap<Context, Boolean>())
    while (seen.add(current)) {
        if (current is Activity) return current.takeUnless { it.isFinishing || it.isDestroyed }
        current = (current as? ContextWrapper)?.baseContext ?: return null
    }
    return null
}

object RuntimeWebViewTheme {
    fun isSystemDark(configuration: Configuration): Boolean =
        configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** Runs on the UI thread. Keeps the same document, JS heap, bridge and background session. */
    fun apply(webView: WebView, dark: Boolean, configuration: Configuration) {
        val context = webView.context as? RuntimeWebViewThemeContext ?: return
        if (context.update(dark, configuration)) {
            webView.setBackgroundColor(if (dark) Color.BLACK else Color.WHITE)
            webView.dispatchConfigurationChanged(Configuration(context.resources.configuration))
            // Native preference changes are asynchronous. Schedule a rendering traversal
            // for attached documents without navigating or touching page-owned CSS.
            webView.requestLayout()
            webView.postInvalidateOnAnimation()
        }
    }
}
