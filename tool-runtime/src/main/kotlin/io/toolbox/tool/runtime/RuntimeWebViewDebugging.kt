package io.toolbox.tool.runtime

import android.webkit.WebView
import androidx.annotation.UiThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-process debugging is opt-in for this process only; call on the main thread. */
object RuntimeWebViewDebugging {
    private val state = RuntimeWebViewDebuggingState(WebView::setWebContentsDebuggingEnabled)

    val enabled: StateFlow<Boolean> get() = state.enabled

    @UiThread
    fun setEnabled(enabled: Boolean) = state.setEnabled(enabled)

    @UiThread
    internal fun applyCurrentSetting() = state.applyCurrentSetting()
}

internal class RuntimeWebViewDebuggingState(private val apply: (Boolean) -> Unit) {
    private val current = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = current.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        apply(enabled)
        current.value = enabled
    }

    fun applyCurrentSetting() = apply(current.value)
}
