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
        mutableState.update { it.copy(confirmation = session, feedback = null) }
    }

    fun cancelStop() {
        mutableState.update { it.copy(confirmation = null) }
    }

    fun dismissFeedback() {
        mutableState.update { it.copy(feedback = null) }
    }

    fun confirmStop() {
        val selected = state.value.confirmation ?: return
        if (state.value.stoppingSessionId != null) return
        if (sessions.value.none { it.sessionId == selected.sessionId && it.toolId == selected.toolId }) {
            cancelStop()
            return
        }
        mutableState.update { it.copy(confirmation = null, stoppingSessionId = selected.sessionId, feedback = null) }
        viewModelScope.launch {
            try {
                stopSession(selected.sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(feedback = CatalogFeedback.Failure("BACKGROUND_STOP_FAILED", "停止未完成，请重试。"))
                }
            } finally {
                mutableState.update { it.copy(stoppingSessionId = null) }
            }
        }
    }
}
