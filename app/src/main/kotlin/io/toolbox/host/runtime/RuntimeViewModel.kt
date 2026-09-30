package io.toolbox.host.runtime

import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class RuntimeCloseUiState(
    val reloading: Boolean,
    val slow: Boolean = false,
    val error: String? = null,
)

internal class RuntimeViewModel(
    private val toolId: String,
    private val sessions: RuntimeSessionManager,
) : ViewModel() {
    val state: StateFlow<RuntimeUiState> = sessions.state(toolId)
    private val mutableCloseState = MutableStateFlow<RuntimeCloseUiState?>(null)
    val closeState = mutableCloseState.asStateFlow()
    private val mutableExitRevision = MutableStateFlow(0)
    val exitRevision = mutableExitRevision.asStateFlow()
    private var closeJob: Job? = null
    private var exitAfterSave: (() -> Unit)? = null

    init { sessions.openForeground(toolId) }

    fun retry() = sessions.retry(toolId)

    fun requestExit(onSaved: () -> Unit) {
        if (closeJob?.isActive == true) return
        exitAfterSave = onSaved
        beginClose(reloading = false)
    }

    fun reload() {
        if (closeJob?.isActive == true) return
        beginClose(reloading = true)
    }

    fun retryClose() { mutableCloseState.value?.let { beginClose(it.reloading) } }

    private fun beginClose(reloading: Boolean) {
        if (closeJob?.isActive == true) return
        mutableExitRevision.value += 1
        mutableCloseState.value = RuntimeCloseUiState(reloading)
        closeJob = viewModelScope.launch {
            val slowHint = launch {
                delay(2_000)
                mutableCloseState.value = mutableCloseState.value?.copy(slow = true)
            }
            try {
                val saved = sessions.prepareForegroundClose(toolId, reloading)
                if (saved) {
                    mutableCloseState.value = null
                    if (reloading) sessions.reloadSaved(toolId) else exitAfterSave?.invoke()
                } else {
                    mutableCloseState.value = RuntimeCloseUiState(reloading, error = "保存未完成，请重试或取消。")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                sessions.cancelForegroundClose(toolId)
                mutableCloseState.value = RuntimeCloseUiState(reloading, error = "保存失败，请重试或取消。")
            } finally {
                slowHint.cancel()
                closeJob = null
            }
        }
    }

    fun cancelClose() {
        closeJob?.cancel()
        sessions.cancelForegroundClose(toolId)
        mutableCloseState.value = null
        mutableExitRevision.value += 1
    }

    fun discardAndClose() {
        val action = mutableCloseState.value ?: return
        cancelClose()
        if (action.reloading) viewModelScope.launch { sessions.reloadSaved(toolId) }
        else {
            sessions.discardForegroundRuntime(toolId)
            exitAfterSave?.invoke()
        }
    }

    fun detached(releasedView: WebView) {
        // Releasing a failed/replaced WebView is not leaving the runtime page.
        if ((state.value as? RuntimeUiState.Ready)?.webView === releasedView) sessions.detachForeground(toolId)
    }

    override fun onCleared() {
        if (mutableCloseState.value != null) sessions.cancelForegroundClose(toolId)
        sessions.detachForeground(toolId)
    }
}
