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
import kotlinx.coroutines.launch

internal class SettingsViewModel(
    private val repository: HostSettingsRepository,
    private val catalog: CatalogRepository,
    private val background: HostBackgroundOperations,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                mutableState.value = mutableState.value.copy(settings = settings, loaded = true)
            }
        }
    }

    fun selectTheme(theme: ThemeMode) = update { it.copy(theme = theme) }

    fun setBackgroundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                if (!save { it.copy(backgroundEnabled = enabled) }) return@launch
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

    private fun update(transform: (HostSettings) -> HostSettings) {
        viewModelScope.launch { save(transform) }
    }

    private suspend fun save(transform: (HostSettings) -> HostSettings): Boolean = when (
        repository.update(transform)
    ) {
        is DataResult.Success -> {
            mutableState.value = mutableState.value.copy(error = null)
            true
        }
        is DataResult.Failure -> {
            showError()
            false
        }
    }

    private fun showError(message: String = "设置未保存，请重试。") {
        mutableState.value = mutableState.value.copy(error = message)
    }
}
