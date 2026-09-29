package io.toolbox.host.runtime

/** Applies WebView's per-instance pause/resume hooks without changing JavaScript timer behavior. */
internal class RuntimeWebViewPlayback(
    private val pause: () -> Unit,
    private val resume: () -> Unit,
    initiallyResumed: Boolean = true,
) {
    private var resumed = initiallyResumed

    fun setResumed(shouldResume: Boolean) {
        if (resumed == shouldResume) return
        if (shouldResume) resume() else pause()
        resumed = shouldResume
    }
}
