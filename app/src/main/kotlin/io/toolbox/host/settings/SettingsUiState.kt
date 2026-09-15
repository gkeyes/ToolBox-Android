package io.toolbox.host.settings

import androidx.compose.runtime.Immutable
import io.toolbox.core.data.HostSettings

@Immutable
internal data class SettingsUiState(
    val settings: HostSettings = HostSettings(),
    val loaded: Boolean = false,
    val error: String? = null,
    val canRetry: Boolean = false,
)
