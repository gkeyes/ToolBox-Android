package io.toolbox.host.runtime

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.toolbox.host.browser.BrowserActivity
import io.toolbox.host.browser.BrowserAppearance
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import io.toolbox.tool.runtime.validateRuntimeBrowserUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun browserViewIntent(url: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(validateRuntimeBrowserUrl(url)).normalizeScheme()).apply {
        // The selector controls resolution; the selected browser still receives ACTION_VIEW
        // and the URL. Never fall back to unselected ACTION_VIEW (which can open deep-link apps).
        // https://developer.android.com/reference/android/content/Intent#setSelector(android.content.Intent)
        selector = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
    }

internal suspend fun launchBrowserUrl(
    context: Context,
    url: String,
    beforeLaunch: suspend () -> Unit,
    ensureForeground: () -> Unit,
    startActivity: (Intent) -> Unit,
) = withContext(Dispatchers.Main.immediate) {
    val intent = Intent(context, BrowserActivity::class.java)
        .setData(Uri.parse(validateRuntimeBrowserUrl(url)).normalizeScheme())
    beforeLaunch()
    BrowserAppearance.current.writeTo(intent)
    // No suspension between the final Activity check and startActivity.
    ensureForeground()
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "内置浏览器不可用，请更新 ToolBox 后重试。")
    } catch (_: SecurityException) {
        throw RuntimeHandlerException(RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED, "系统阻止了内置浏览器启动，请重试。")
    }
}
