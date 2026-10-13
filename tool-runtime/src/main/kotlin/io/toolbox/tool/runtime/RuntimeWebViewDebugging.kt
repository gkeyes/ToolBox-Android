package io.toolbox.tool.runtime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Looper
import android.webkit.WebView
import androidx.annotation.UiThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RuntimeWebViewDebuggingStatus(
    val enabledByBuild: Boolean,
    val forcedByPlatform: Boolean = false,
    /** In-memory override for this process only; it is never persisted. */
    val enabledByUser: Boolean = false,
) {
    /** The value the app passes to [WebView.setWebContentsDebuggingEnabled]. */
    val appliedEnabled: Boolean get() = enabledByBuild || enabledByUser
    val enabled: Boolean get() = appliedEnabled || forcedByPlatform

    /** Only a release APK on a user system can be switched in the app; debug builds stay on. */
    val userToggleable: Boolean get() = !enabledByBuild && !forcedByPlatform
}

/**
 * Debugging follows the embedding APK's compiled flag, plus a process-wide override that the
 * developer help page can turn on. The override lives only in memory, so it resets to off when
 * the app process restarts and cannot be forgotten on.
 */
object RuntimeWebViewDebugging {
    private val enabledByUserState = MutableStateFlow(false)
    val enabledByUser: StateFlow<Boolean> = enabledByUserState.asStateFlow()

    fun status(context: Context): RuntimeWebViewDebuggingStatus = runtimeWebViewDebuggingStatus(
        debuggableApp = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        buildType = Build.TYPE,
        enabledByUser = enabledByUserState.value,
    )

    /**
     * Applies immediately to every WebView in this process; new tool pages pick it up too.
     * The override is published only after WebView accepted it. Returns false when the WebView
     * provider is missing or disabled, in which case the previous state is kept.
     */
    @UiThread
    fun setEnabledByUser(context: Context, enabled: Boolean): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) { "WebView debugging must be changed on the main thread" }
        val current = status(context)
        val next = current.copy(enabledByUser = enabled && !current.enabledByBuild)
        return try {
            WebView.setWebContentsDebuggingEnabled(next.appliedEnabled)
            enabledByUserState.value = enabled
            true
        } catch (_: RuntimeException) {
            // AndroidRuntimeException when no WebView provider is available.
            false
        }
    }

    @UiThread
    internal fun applyBuildSetting(context: Context) {
        // A developer OS may force debugging despite false; a release APK is only enabled by the user override.
        WebView.setWebContentsDebuggingEnabled(status(context).appliedEnabled)
    }
}

internal fun runtimeWebViewDebuggingStatus(
    debuggableApp: Boolean,
    buildType: String,
    enabledByUser: Boolean = false,
) = RuntimeWebViewDebuggingStatus(
    enabledByBuild = debuggableApp,
    forcedByPlatform = !debuggableApp && (buildType == "userdebug" || buildType == "eng"),
    enabledByUser = enabledByUser && !debuggableApp,
)
