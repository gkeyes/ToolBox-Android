package io.toolbox.host.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.HostSettings
import io.toolbox.core.data.HostSettingsRepository
import io.toolbox.core.data.ThemeMode
import io.toolbox.core.data.ThemeStyle
import io.toolbox.host.HostBackgroundOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal class SettingsViewModel(
    private val repository: HostSettingsRepository,
    private val catalog: CatalogRepository,
    private val background: HostBackgroundOperations,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()
    private val appearanceUpdates = Channel<(HostSettings) -> HostSettings>(Channel.UNLIMITED)
    private var failedAppearanceUpdate: ((HostSettings) -> HostSettings)? = null

    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                mutableState.value = mutableState.value.copy(settings = settings, loaded = true)
            }
        }
        viewModelScope.launch {
            for (transform in appearanceUpdates) save(transform, rememberForRetry = true)
        }
    }

    fun selectTheme(theme: ThemeMode) = updateAppearance { it.copy(theme = theme) }

    fun selectThemeStyle(style: ThemeStyle) = updateAppearance { it.copy(themeStyle = style) }

    fun setReduceTransparency(enabled: Boolean) =
        updateAppearance { it.copy(reduceTransparency = enabled) }

    fun retryAppearanceUpdate() {
        failedAppearanceUpdate?.let(appearanceUpdates::trySend)
    }

    fun setBackgroundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                if (!save({ it.copy(backgroundEnabled = enabled) }, rememberForRetry = false)) return@launch
                if (!enabled) {
                    val ids = catalog.observeCatalogProjection().first().map { it.toolId }
                    background.cancelAll(ids)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showError("后台任务未全部取消，请重试。")
            }
        }
    }

    private fun updateAppearance(transform: (HostSettings) -> HostSettings) {
        appearanceUpdates.trySend(transform)
    }

    private suspend fun save(
        transform: (HostSettings) -> HostSettings,
        rememberForRetry: Boolean,
    ): Boolean = when (
        repository.update(transform)
    ) {
        is DataResult.Success -> {
            if (rememberForRetry) failedAppearanceUpdate = null
            mutableState.value = mutableState.value.copy(error = null, canRetry = false)
            true
        }
        is DataResult.Failure -> {
            if (rememberForRetry) failedAppearanceUpdate = transform
            showError(canRetry = rememberForRetry)
            false
        }
    }

    private fun showError(
        message: String = "设置未保存，请重试。",
        canRetry: Boolean = false,
    ) {
        mutableState.value = mutableState.value.copy(error = message, canRetry = canRetry)
    }
}
