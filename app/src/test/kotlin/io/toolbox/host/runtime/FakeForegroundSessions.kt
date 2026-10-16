package io.toolbox.host.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Records what a RuntimeViewModel asks of its sessions; [save] answers the exit save. */
internal class FakeForegroundSessions(
    initialState: RuntimeUiState = RuntimeUiState.Loading,
) : ForegroundRuntimeSessions {
    val calls = mutableListOf<String>()
    var save: CompletableDeferred<Boolean> = CompletableDeferred(true)
    private val state = MutableStateFlow(initialState)

    override fun state(toolId: String): StateFlow<RuntimeUiState> = state
    override fun openForeground(toolId: String) { calls += "open" }
    override fun retry(toolId: String) { calls += "retry" }
    override suspend fun prepareForegroundClose(toolId: String, reloading: Boolean): Boolean {
        calls += "prepare"
        return save.await()
    }
    override fun cancelForegroundClose(toolId: String) { calls += "cancelClose" }
    override fun discardForegroundRuntime(toolId: String) { calls += "discard" }
    override suspend fun reloadSaved(toolId: String) { calls += "reload" }
    override fun detachForeground(toolId: String) { calls += "detach" }
}
