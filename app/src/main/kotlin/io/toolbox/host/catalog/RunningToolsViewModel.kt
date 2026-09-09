package io.toolbox.host.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.toolbox.host.runtime.RuntimeBackgroundSessionUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class RunningToolsUiState(
    val sessions: List<RuntimeBackgroundSessionUi> = emptyList(),
    val confirmation: RuntimeBackgroundSessionUi? = null,
    val stoppingSessionId: String? = null,
    val batchConfirmation: List<RuntimeBackgroundSessionUi> = emptyList(),
    val feedback: CatalogFeedback.Failure? = null,
)

internal class RunningToolsViewModel(
    private val sessions: StateFlow<List<RuntimeBackgroundSessionUi>>,
    private val stopSession: suspend (String) -> Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RunningToolsUiState(sessions = sessions.value))
    val state: StateFlow<RunningToolsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            sessions.collect { current ->
                mutableState.update { state ->
                    state.copy(
                        sessions = current,
                        batchConfirmation = state.batchConfirmation.filter { selected ->
                            current.any { it.sessionId == selected.sessionId && it.toolId == selected.toolId }
                        },
                        confirmation = state.confirmation?.takeIf { selected ->
                            current.any { it.sessionId == selected.sessionId && it.toolId == selected.toolId }
                        },
                    )
                }
            }
        }
    }

    fun requestStop(sessionId: String) {
        if (state.value.stoppingSessionId != null) return
        val session = sessions.value.firstOrNull { it.sessionId == sessionId } ?: return
        mutableState.update { it.copy(confirmation = session, batchConfirmation = emptyList(), feedback = null) }
    }

    fun requestStopAll() {
        if (state.value.stoppingSessionId != null || sessions.value.size <= 2) return
        mutableState.update { it.copy(confirmation = null, batchConfirmation = sessions.value.toList(), feedback = null) }
    }

    fun cancelStop() {
        mutableState.update { it.copy(confirmation = null, batchConfirmation = emptyList()) }
    }

    fun dismissFeedback() {
        mutableState.update { it.copy(feedback = null) }
    }

    fun confirmStop() {
        if (state.value.stoppingSessionId != null) return
        // Confirm only the snapshot shown in the dialog, never sessions started afterwards.
        val selected = state.value.confirmation?.let(::listOf) ?: state.value.batchConfirmation
        val targets = selected.filter { target ->
            sessions.value.any { it.sessionId == target.sessionId && it.toolId == target.toolId }
        }
        if (targets.isEmpty()) {
            cancelStop()
            return
        }
        mutableState.update { it.copy(confirmation = null, batchConfirmation = emptyList(), stoppingSessionId = targets.first().sessionId, feedback = null) }
        viewModelScope.launch {
            var failed = false
            try {
                for (target in targets) {
                    if (sessions.value.none { it.sessionId == target.sessionId && it.toolId == target.toolId }) continue
                    mutableState.update { it.copy(stoppingSessionId = target.sessionId) }
                    try {
                        val stopped = stopSession(target.sessionId)
                        if (!stopped && sessions.value.any { it.sessionId == target.sessionId }) failed = true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failed = true
                    }
                }
            } finally {
                mutableState.update { it.copy(
                    stoppingSessionId = null,
                    feedback = if (failed) CatalogFeedback.Failure("BACKGROUND_STOP_FAILED", "部分后台运行未能停止，请重试。") else null,
                ) }
            }
        }
    }
}
