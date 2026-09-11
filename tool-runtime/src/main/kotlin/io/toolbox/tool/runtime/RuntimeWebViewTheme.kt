package io.toolbox.tool.runtime

import android.content.Context
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.webkit.WebView

/** Native isLightTheme drives CSS prefers-color-scheme; a Compose palette alone does not. */
class RuntimeWebViewThemeContext(
    context: Context,
    dark: Boolean,
    configuration: Configuration = context.resources.configuration,
) : MutableContextWrapper(context.applicationContext) {
    private val application = context.applicationContext
    private var applied: Configuration? = null
    private var darkTheme: Boolean? = null

    init { update(dark, configuration) }

    fun update(dark: Boolean, configuration: Configuration): Boolean {
        val adjusted = Configuration(configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        if (darkTheme == dark && applied == adjusted) return false
        baseContext = ContextThemeWrapper(
            application.createConfigurationContext(adjusted),
            if (dark) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar,
        )
        darkTheme = dark
        applied = adjusted
        return true
    }
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
        }
    }
}
