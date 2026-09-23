package io.toolbox.host.browser

internal enum class BrowserLoadAction { Reload, Stop, Recover, Disabled }

/** UI policy only. No timers, injected JavaScript, automatic reloads or automatic process kills. */
internal data class BrowserInteractionState(
    val loading: Boolean = false,
    val stoppedByUser: Boolean = false,
    val unresponsive: Boolean = false,
    val promptDismissed: Boolean = false,
    val restarting: Boolean = false,
) {
    val showRecoveryPrompt: Boolean
        get() = unresponsive && !promptDismissed && !restarting

    fun loadAction(clearing: Boolean): BrowserLoadAction = when {
        clearing || restarting -> BrowserLoadAction.Disabled
        unresponsive -> BrowserLoadAction.Recover
        loading -> BrowserLoadAction.Stop
        else -> BrowserLoadAction.Reload
    }

    fun pageStarted() = copy(loading = true, stoppedByUser = false)
    fun pageFinished() = copy(loading = false)
    fun stopRequested() = copy(loading = false, stoppedByUser = true)

    // WebView can report an unresponsive renderer repeatedly. Waiting silences only this episode.
    fun rendererUnresponsive() = if (restarting) this else copy(unresponsive = true)
    fun rendererResponsive() = if (restarting) this else copy(unresponsive = false, promptDismissed = false)
    fun keepWaiting() = copy(promptDismissed = true)
    fun requestRecoveryPrompt() = copy(promptDismissed = false)
    fun restartRequested() = copy(loading = false, restarting = true, promptDismissed = true)
    fun restartFailed() = copy(restarting = false, promptDismissed = true)
}
