package io.toolbox.host.browser

import android.content.Intent
import io.toolbox.core.ui.theme.ToolBoxThemeMode

/** Appearance only: the browser process never opens host settings or tool storage. */
internal data class BrowserAppearance(
    val themeMode: ToolBoxThemeMode = ToolBoxThemeMode.System,
    val reduceTransparency: Boolean = false,
) {
    fun writeTo(intent: Intent) {
        intent.putExtra(EXTRA_THEME_MODE, themeMode.name)
        intent.putExtra(EXTRA_REDUCE_TRANSPARENCY, reduceTransparency)
    }

    companion object {
        private const val EXTRA_THEME_MODE = "io.toolbox.host.browser.THEME_MODE"
        private const val EXTRA_REDUCE_TRANSPARENCY = "io.toolbox.host.browser.REDUCE_TRANSPARENCY"

        @Volatile
        var current = BrowserAppearance()

        fun fromIntent(intent: Intent): BrowserAppearance = BrowserAppearance(
            themeMode = ToolBoxThemeMode.entries.firstOrNull {
                it.name == intent.getStringExtra(EXTRA_THEME_MODE)
            } ?: ToolBoxThemeMode.System,
            reduceTransparency = intent.getBooleanExtra(EXTRA_REDUCE_TRANSPARENCY, false),
        )
    }
}
