package io.toolbox.host.runtime

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

internal class RuntimeViewModel(
    private val toolId: String,
    private val sessions: RuntimeSessionManager,
) : ViewModel() {
    val state: StateFlow<RuntimeUiState> = sessions.state(toolId)

    init {
        sessions.openForeground(toolId)
    }

    fun retry() = sessions.retry(toolId)

    fun reload() = sessions.reload(toolId)

    fun detached() = sessions.detachForeground(toolId)

    override fun onCleared() {
        sessions.detachForeground(toolId)
    }
}
