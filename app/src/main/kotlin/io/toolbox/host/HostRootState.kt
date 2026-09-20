package io.toolbox.host

import io.toolbox.core.data.ThemeMode
import io.toolbox.core.data.ThemeStyle
import io.toolbox.host.catalog.CatalogUiState
import io.toolbox.host.settings.SettingsUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Only state used by the activity shell; feature state stays with its own surface. */
internal data class HostRootSettings(
    val loaded: Boolean,
    val hasError: Boolean,
    val theme: ThemeMode,
    val themeStyle: ThemeStyle,
    val reduceTransparency: Boolean,
)

internal fun SettingsUiState.rootSettings() = HostRootSettings(
    loaded = loaded,
    hasError = error != null,
    theme = settings.theme,
    themeStyle = settings.themeStyle,
    reduceTransparency = settings.reduceTransparency,
)

internal fun Flow<SettingsUiState>.rootSettings(): Flow<HostRootSettings> =
    map(SettingsUiState::rootSettings).distinctUntilChanged()

internal fun Flow<CatalogUiState>.catalogReadiness(): Flow<Boolean> =
    map { it.isLoaded && !it.loadFailed }.distinctUntilChanged()
