package io.toolbox.host.browser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttp

/**
 * OkHttp's Android public suffix list is set up by androidx-startup, which runs only in the main
 * process; the :browser process has to load it itself before [browserDisplayHost] can shorten hosts.
 * [ready] is snapshot state, so the address capsule recomposes once the list (about 130 KB) is in.
 */
internal object BrowserPublicSuffix {
    var ready by mutableStateOf(false)
        private set

    /** Blocking; call off the main thread. A failed load leaves hosts unshortened, as before. */
    fun load(context: Context) {
        if (ready) return
        runCatching {
            OkHttp.initialize(context.applicationContext)
            "https://example.com/".toHttpUrl().topPrivateDomain()
        }
        ready = true
    }
}
