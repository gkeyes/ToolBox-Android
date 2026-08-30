package io.toolbox.host.settings

import androidx.compose.runtime.Immutable
import io.toolbox.core.data.HostSettings
import io.toolbox.core.data.ThemeMode

@Immutable
internal data class SettingsUiState(
    val settings: HostSettings = HostSettings(),
    val loaded: Boolean = false,
    val error: String? = null,
)

internal val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "跟随系统"
        ThemeMode.LIGHT -> "明亮"
        ThemeMode.DARK -> "深色"
        ThemeMode.MONET_SYSTEM -> "系统取色"
        ThemeMode.MONET_LIGHT -> "取色·明亮"
        ThemeMode.MONET_DARK -> "取色·深色"
    }
