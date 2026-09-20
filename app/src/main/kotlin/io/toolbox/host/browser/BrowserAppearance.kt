package io.toolbox.host.browser

import android.content.Intent
import io.toolbox.core.ui.theme.ToolBoxThemeMode
import io.toolbox.core.ui.theme.ToolBoxThemeStyle

/** Appearance only: the browser process never opens host settings or tool storage. */
internal data class BrowserAppearance(
    val themeMode: ToolBoxThemeMode = ToolBoxThemeMode.System,
    val reduceTransparency: Boolean = false,
    val themeStyle: ToolBoxThemeStyle = ToolBoxThemeStyle.LiquidGlass,
) {
    fun writeTo(intent: Intent) {
        intent.putExtra(EXTRA_THEME_MODE, themeMode.name)
        intent.putExtra(EXTRA_REDUCE_TRANSPARENCY, reduceTransparency)
        intent.putExtra(EXTRA_THEME_STYLE, themeStyle.name)
    }

    companion object {
        private const val EXTRA_THEME_MODE = "io.toolbox.host.browser.THEME_MODE"
        private const val EXTRA_REDUCE_TRANSPARENCY = "io.toolbox.host.browser.REDUCE_TRANSPARENCY"
        private const val EXTRA_THEME_STYLE = "io.toolbox.host.browser.THEME_STYLE"

        @Volatile
        var current = BrowserAppearance()

        fun fromIntent(intent: Intent): BrowserAppearance = BrowserAppearance(
            themeMode = ToolBoxThemeMode.entries.firstOrNull {
                it.name == intent.getStringExtra(EXTRA_THEME_MODE)
            } ?: ToolBoxThemeMode.System,
            reduceTransparency = intent.getBooleanExtra(EXTRA_REDUCE_TRANSPARENCY, false),
            themeStyle = ToolBoxThemeStyle.entries.firstOrNull {
                it.name == intent.getStringExtra(EXTRA_THEME_STYLE)
            } ?: ToolBoxThemeStyle.LiquidGlass,
        )
    }
}
