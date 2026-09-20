package io.toolbox.host.settings

import androidx.compose.runtime.Immutable
import io.toolbox.core.data.HostSettings

@Immutable
internal data class SettingsUiState(
    val settings: HostSettings = HostSettings(),
    val loaded: Boolean = false,
    val error: String? = null,
    val canRetry: Boolean = false,
    val backgroundWorking: Boolean = false,
    val backgroundOperation: BackgroundSettingsOperation? = null,
    val backgroundError: String? = null,
) {
    val canChangeBackground: Boolean get() = loaded && !backgroundWorking && backgroundOperation == null
}

internal enum class BackgroundSettingsOperation { SAVE, STOP }
