package io.toolbox.host.runtime

import io.toolbox.core.data.ThemeMode

internal fun ThemeMode.runtimeDarkTheme(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM, ThemeMode.MONET_SYSTEM -> systemDark
    ThemeMode.DARK, ThemeMode.MONET_DARK -> true
    ThemeMode.LIGHT, ThemeMode.MONET_LIGHT -> false
}
