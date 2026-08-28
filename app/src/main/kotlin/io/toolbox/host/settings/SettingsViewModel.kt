package io.toolbox.host.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.AuditRepository
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.HostSettingsRepository
import io.toolbox.core.data.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SettingsViewModel(
    private val repository: HostSettingsRepository,
    private val audit: AuditRepository,
    private val nowMillis: () -> Long,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()
    private val auditRetentionMutex = Mutex()
    private var auditRetentionJob: Job? = null

    init {
        viewModelScope.launch {
            repository.settings.collectLatest { settings ->
                mutableState.value = mutableState.value.copy(
                    settings = settings,
                    isLoaded = true,
                )
            }
        }
    }

    fun selectTheme(theme: ThemeMode) = update { it.copy(theme = theme) }

    fun selectAuditRetention(days: Int) {
        auditRetentionJob?.cancel()
        auditRetentionJob = viewModelScope.launch {
            auditRetentionMutex.withLock {
                currentCoroutineContext().ensureActive()
                clearError()
                val result = repository.update { it.copy(auditRetentionDays = days) }
                currentCoroutineContext().ensureActive()
                when (result) {
                    is DataResult.Success -> {
                        pruneAudit(days)
                    }
                    is DataResult.Failure -> showSaveError(result)
                }
            }
        }
    }

    private fun update(transform: (io.toolbox.core.data.HostSettings) -> io.toolbox.core.data.HostSettings) {
        viewModelScope.launch {
            clearError()
            when (val result = repository.update(transform)) {
                is DataResult.Success -> Unit
                is DataResult.Failure -> showSaveError(result)
            }
        }
    }

    private suspend fun pruneAudit(days: Int) {
        val cutoff = nowMillis() - days.toLong() * MILLIS_PER_DAY
        when (val result = audit.deleteBefore(cutoff)) {
            is DataResult.Success -> Unit
            is DataResult.Failure -> {
                currentCoroutineContext().ensureActive()
                mutableState.value = mutableState.value.copy(
                    updateError = "审计日志留存已保存，但清理旧记录失败：${result.userMessage()}",
                )
            }
        }
    }

    private fun showSaveError(result: DataResult.Failure) {
        mutableState.value = mutableState.value.copy(
            updateError = "设置未保存：${result.userMessage()}",
        )
    }

    private fun clearError() {
        mutableState.value = mutableState.value.copy(updateError = null)
    }
}

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

private fun DataResult.Failure.userMessage(): String = when (this) {
    is DataResult.Failure.InvalidInput -> "输入不在允许范围内"
    is DataResult.Failure.StorageFailure -> "存储暂时不可用"
    else -> "请求被安全策略拒绝"
}
