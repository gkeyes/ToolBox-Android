package io.toolbox.host.runtime

import kotlinx.coroutines.flow.StateFlow

/** What the tool page's [RuntimeViewModel] asks of the runtime sessions for its foreground tool. */
internal interface ForegroundRuntimeSessions {
    fun state(toolId: String): StateFlow<RuntimeUiState>
    fun openForeground(toolId: String)
    fun retry(toolId: String)
    suspend fun prepareForegroundClose(toolId: String, reloading: Boolean): Boolean
    fun cancelForegroundClose(toolId: String)
    fun discardForegroundRuntime(toolId: String)
    suspend fun reloadSaved(toolId: String)
    fun detachForeground(toolId: String)
}
