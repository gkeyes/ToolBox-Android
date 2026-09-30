package io.toolbox.tool.runtime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.webkit.WebView
import androidx.annotation.UiThread

data class RuntimeWebViewDebuggingStatus(
    val enabledByBuild: Boolean,
    val forcedByPlatform: Boolean = false,
) {
    val enabled: Boolean get() = enabledByBuild || forcedByPlatform
}

/** Debugging follows the embedding APK's compiled flag; there is no runtime override. */
object RuntimeWebViewDebugging {
    fun status(context: Context): RuntimeWebViewDebuggingStatus = runtimeWebViewDebuggingStatus(
        debuggableApp = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        buildType = Build.TYPE,
    )

    @UiThread
    internal fun applyBuildSetting(context: Context) {
        // A developer OS may force debugging despite false; never enable a release APK here.
        WebView.setWebContentsDebuggingEnabled(status(context).enabledByBuild)
    }
}

internal fun runtimeWebViewDebuggingStatus(debuggableApp: Boolean, buildType: String) =
    RuntimeWebViewDebuggingStatus(
        enabledByBuild = debuggableApp,
        forcedByPlatform = !debuggableApp && (buildType == "userdebug" || buildType == "eng"),
    )
