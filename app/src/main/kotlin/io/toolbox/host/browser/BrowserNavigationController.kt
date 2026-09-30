package io.toolbox.host.browser

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.SafeBrowsingResponse
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.component.ToolBoxModalDialog
import io.toolbox.core.ui.component.ToolBoxSecondaryButton
import io.toolbox.core.ui.component.ToolBoxText
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.tool.runtime.validateRuntimeBrowserUrl

/** Navigation decisions are visible and scoped to the document that requested them. */
internal class BrowserNavigationController(
    private val context: Context,
    private val isCurrentPage: (WebView) -> Boolean,
    private val navigate: (WebView, String) -> Unit,
    private val notify: (String) -> Unit,
) {
    private class Decision(
        val page: WebView,
        val title: String,
        val message: String,
        val actionLabel: String,
        val accept: () -> Unit,
        val cancel: () -> Unit = {},
    )

    private var decisions by mutableStateOf<List<Decision>>(emptyList())
    val hasPrompt get() = decisions.isNotEmpty()

    /** Returns false for navigations that the engine should perform without host rewriting. */
    fun handle(page: WebView, request: WebResourceRequest): Boolean {
        if (!isCurrentPage(page)) return true
        val url = request.url.toString()
        return when (BrowserLinkPolicy.classify(url)) {
            BrowserLinkKind.Web -> false
            BrowserLinkKind.External -> {
                offerExternal(page, url)
                true
            }
            BrowserLinkKind.Unsupported -> {
                if (request.isForMainFrame) notify("此链接不是可浏览的网页地址。")
                true
            }
        }
    }

    private fun offerExternal(page: WebView, url: String) {
        val parsed = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull()
        val target = parsed?.data ?: Uri.parse(url)
        val fallback = parsed?.getStringExtra("browser_fallback_url")?.let {
            runCatching { validateRuntimeBrowserUrl(it) }.getOrNull()
        }
        // Build a fresh browsable intent; never inherit a webpage's component, extras or flags.
        val kind = BrowserLinkPolicy.classify(target.toString())
        if (kind == BrowserLinkKind.Unsupported || target.scheme == "intent") {
            if (fallback != null) navigate(page, fallback)
            else notify("此应用链接没有有效的目标地址。")
            return
        }
        decisions = decisions + Decision(
            page, "在其他应用中打开", "网页请求打开 ${target.scheme.orEmpty()} 链接。", "打开应用",
            accept = {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, target).addCategory(Intent.CATEGORY_BROWSABLE)
                    parsed?.`package`?.let(intent::setPackage)
                    context.startActivity(intent)
                } catch (_: android.content.ActivityNotFoundException) {
                    if (fallback != null) navigate(page, fallback)
                    else notify("未安装能够打开此链接的应用。")
                } catch (_: SecurityException) {
                    if (fallback != null) navigate(page, fallback)
                    else notify("系统未允许打开此应用链接。")
                }
            },
        )
    }

    fun safeBrowsing(page: WebView, request: WebResourceRequest, response: SafeBrowsingResponse) {
        if (!isCurrentPage(page)) {
            response.backToSafety(false)
            return
        }
        decisions = decisions + Decision(
            page, "网站风险提示",
            "系统将 ${request.url.host.orEmpty()} 标记为可能存在风险。你可以返回，也可以选择继续访问。",
            "继续访问", accept = { response.proceed(false) }, cancel = { response.backToSafety(false) },
        )
    }

    fun download(page: WebView, url: String, userAgent: String, disposition: String?, mime: String?) {
        if (!isCurrentPage(page)) return
        if (runCatching { validateRuntimeBrowserUrl(url) }.isFailure) {
            notify("此下载由网页临时生成，系统下载器无法接管；可在系统浏览器中完成。")
            return
        }
        val filename = URLUtil.guessFileName(url, disposition, mime)
            .replace('/', '_').replace('\\', '_').filterNot(Char::isISOControl).take(180).ifBlank { "download" }
        decisions = decisions + Decision(
            page, "下载文件", "$filename\n来源：${Uri.parse(url).host.orEmpty()}", "下载",
            accept = {
                runCatching {
                    val request = DownloadManager.Request(Uri.parse(url))
                        .setTitle(filename)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                    if (!mime.isNullOrBlank()) request.setMimeType(mime)
                    if (userAgent.isNotBlank()) request.addRequestHeader("User-Agent", userAgent)
                    CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                        request.addRequestHeader("Cookie", it)
                    }
                    context.getSystemService(DownloadManager::class.java).enqueue(request)
                }.onSuccess { notify("已添加到系统下载，文件保存至下载目录。") }
                    .onFailure { notify("系统下载器未能开始下载，可从菜单使用系统浏览器。") }
            },
        )
    }

    fun cancelPage(page: WebView) {
        val canceled = decisions.filter { it.page === page }
        decisions = decisions.filterNot { it.page === page }
        canceled.forEach { runCatching { it.cancel() } }
    }

    private fun resolve(decision: Decision, allow: Boolean) {
        // A double click on an obsolete dialog must not approve the next queued request.
        if (decisions.firstOrNull() !== decision) return
        decisions = decisions.drop(1)
        if (allow && isCurrentPage(decision.page)) decision.accept() else decision.cancel()
    }

    @Composable
    fun Prompt() {
        val decision = decisions.firstOrNull() ?: return
        val colors = ToolBoxThemeTokens.colors
        ToolBoxModalDialog(onDismissRequest = { resolve(decision, false) }) {
            ToolBoxText(decision.title, style = ToolBoxThemeTokens.textStyles.title.copy(color = colors.textPrimary))
            Spacer(Modifier.height(12.dp))
            ToolBoxText(decision.message, style = ToolBoxThemeTokens.textStyles.body.copy(color = colors.textSecondary))
            Spacer(Modifier.height(20.dp))
            ToolBoxSecondaryButton(decision.actionLabel, { resolve(decision, true) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            ToolBoxTextButton("取消", { resolve(decision, false) }, Modifier.fillMaxWidth(), outlined = false)
        }
    }
}
