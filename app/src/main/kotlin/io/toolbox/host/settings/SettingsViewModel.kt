package io.toolbox.host.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.HostSettings
import io.toolbox.core.data.HostSettingsRepository
import io.toolbox.core.data.ThemeMode
import io.toolbox.host.HostBackgroundOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
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
    private var pendingBackgroundEnabled: Boolean? = null

    init {
        viewModelScope.launch {
            repository.settings.catch {
                mutableState.value = mutableState.value.copy(loaded = true, error = "设置或首页布局无法读取。数据已保留，请更新 ToolBox 或从完整备份恢复。")
            }.collect { settings ->
                mutableState.value = mutableState.value.copy(settings = settings, loaded = true)
            }
        }
        viewModelScope.launch {
            for (transform in appearanceUpdates) save(transform, rememberForRetry = true)
        }
    }

    fun selectThemeStyle(style: io.toolbox.core.data.ThemeStyle) = updateAppearance { it.copy(themeStyle = style) }

    fun selectTheme(theme: ThemeMode) = updateAppearance { it.copy(theme = theme) }

    fun setReduceTransparency(enabled: Boolean) =
        updateAppearance { it.copy(reduceTransparency = enabled) }

    fun retryAppearanceUpdate() {
        failedAppearanceUpdate?.let(appearanceUpdates::trySend)
    }

    fun setBackgroundEnabled(enabled: Boolean) {
        if (!state.value.canChangeBackground || state.value.settings.backgroundEnabled == enabled) return
        pendingBackgroundEnabled = enabled
        mutableState.update { it.copy(backgroundOperation = BackgroundSettingsOperation.SAVE) }
        runBackgroundUpdate()
    }

    fun retryBackgroundUpdate() {
        if (state.value.backgroundWorking || state.value.backgroundOperation == null) return
        runBackgroundUpdate()
    }

    private fun runBackgroundUpdate() {
        // Set the guard before dispatching so two callbacks in the same frame cannot overlap.
        mutableState.update { it.copy(backgroundWorking = true) }
        viewModelScope.launch {
            try {
                if (state.value.backgroundOperation == BackgroundSettingsOperation.SAVE) {
                    val enabled = requireNotNull(pendingBackgroundEnabled)
                    when (repository.update { it.copy(backgroundEnabled = enabled) }) {
                        is DataResult.Failure -> {
                            showBackgroundError()
                            return@launch
                        }
                        is DataResult.Success -> {
                            pendingBackgroundEnabled = null
                            mutableState.update { it.copy(
                                settings = it.settings.copy(backgroundEnabled = enabled),
                                backgroundOperation = if (enabled) null else BackgroundSettingsOperation.STOP,
                                backgroundError = if (enabled) null else it.backgroundError,
                            ) }
                        }
                    }
                }
                if (state.value.backgroundOperation == BackgroundSettingsOperation.STOP) {
                    val ids = catalog.observeCatalogProjection().first().map { it.toolId }
                    background.cancelAll(ids)
                    mutableState.update { it.copy(backgroundOperation = null, backgroundError = null) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showBackgroundError()
            } finally {
                mutableState.update { it.copy(backgroundWorking = false) }
            }
        }
    }

    private fun showBackgroundError() {
        mutableState.update { it.copy(backgroundError = when (it.backgroundOperation) {
            BackgroundSettingsOperation.STOP -> "后台运行已关闭，但部分任务或运行环境未能停止。请重试停止。"
            else -> "后台设置未保存，请重试保存。"
        }) }
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
