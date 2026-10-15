package io.toolbox.host.browser

import java.net.URI

internal object BrowserSslPromptPolicy {
    fun describe(
        hasUntrusted: Boolean,
        hasIdMismatch: Boolean,
        hasExpired: Boolean,
        hasNotYetValid: Boolean,
        hasDateInvalid: Boolean,
        hasInvalid: Boolean,
    ): String = buildList {
        if (hasIdMismatch) add("证书与连接的网站域名不匹配")
        if (hasExpired) add("证书已经过期")
        if (hasNotYetValid) add("证书尚未生效")
        if (hasDateInvalid) add("证书日期无效")
        if (hasUntrusted) add("证书链无法被系统完整信任")
        if (hasInvalid) add("证书无效")
    }.let { reasons ->
        if (reasons.isEmpty()) "系统无法验证此连接的安全证书。"
        else reasons.joinToString("；") + "。"
    }

    /** Scheme, host and port only, parsed like navigation so every page the browser loads gets a label. */
    fun connectionLabel(url: String): String {
        // Never show credentials; drop them so the remaining address can be parsed.
        val parts = BrowserWebUrl.parse(url.replaceFirst(USER_INFO, "$1")) ?: return "未知地址"
        return "${parts.scheme}://${parts.host}" + (parts.port?.let { ":$it" } ?: "")
    }

    private val USER_INFO = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/?#]*@")

}

/** The production callback queue. Platform actions are injected so lifecycle ordering is testable. */
internal class BrowserSslSession<Page : Any>(
    private val currentPage: () -> Page?,
    private val clearPreferences: (Page) -> Unit,
    private val changed: () -> Unit = {},
    private val bypassChanged: (Boolean) -> Unit = {},
) {
    internal data class Warning<Page : Any>(
        val id: Long,
        val page: Page,
        val url: String,
        val reason: String,
        val proceed: () -> Unit,
        val cancel: () -> Unit,
    )

    private var page: Page? = null
    private var awaitingStart = false
    /**
     * The user allowed a connection since the last navigation request or page start; the page that
     * starts next inherits the warning. The warning itself only changes when a page starts, never on a
     * request, so a navigation that never commits (a 204, a download, window.stop()) cannot clear it.
     */
    private var proceededBeforeStart = false
    private var nextId = 0L
    private val warnings = ArrayDeque<Warning<Page>>()
    val first: Warning<Page>? get() = warnings.firstOrNull()
    val size: Int get() = warnings.size

    /** Call before an explicit load, reload, history traversal, or accepted main-frame navigation. */
    fun navigationRequested(view: Page, url: String? = null) {
        if (view !== currentPage()) return
        replaceSession(view)
        awaitingStart = true
    }

    /** True only when this callback starts a session that was not announced by the host. */
    fun navigationStarted(view: Page, url: String): Boolean {
        if (view !== currentPage()) return false
        if (view === page && awaitingStart) {
            // SSL can arrive before onPageStarted. This is the start of the already announced
            // navigation, including a late callback after proceed(), not a new session. The
            // engine may normalize the URL or follow a redirect before delivering this start;
            // URL equality cannot identify a navigation operation.
            awaitingStart = false
            bypassChanged(proceededBeforeStart)
            proceededBeforeStart = false
            return false
        }
        // An unannounced navigation whose certificate the user already allowed keeps the warning;
        // when the allowance was really for the previous page this errs on the side of warning.
        val carry = proceededBeforeStart
        replaceSession(view)
        bypassChanged(carry)
        return true
    }

    fun enqueue(view: Page, url: String, reason: String, proceed: () -> Unit, cancel: () -> Unit) {
        if (view !== currentPage()) {
            cancel()
            return
        }
        if (view !== page) {
            replaceSession(view)
            // Also supports the first SSL callback arriving before the first page-start callback.
            awaitingStart = true
        }
        warnings.addLast(Warning(++nextId, view, url, reason, proceed, cancel))
        changed()
    }

    fun resolve(id: Long, allow: Boolean) {
        val warning = warnings.firstOrNull()?.takeIf { it.id == id } ?: return
        warnings.removeFirst()
        changed()
        if (allow && warning.page === page && warning.page === currentPage()) {
            proceededBeforeStart = true
            bypassChanged(true)
            warning.proceed()
        } else {
            warning.cancel()
        }
    }

    fun destroyPage(view: Page) {
        if (view !== page) return
        page = null
        awaitingStart = false
        // Stop also lands here and leaves the allowed document on screen, so the warning stays until
        // the next page starts.
        clearPreferences(view)
        cancelOutstanding()
    }

    private fun replaceSession(view: Page) {
        val previous = page
        page = view
        awaitingStart = false
        proceededBeforeStart = false
        if (previous != null && previous !== view) clearPreferences(previous)
        clearPreferences(view)
        cancelOutstanding()
    }

    private fun cancelOutstanding() {
        val obsolete = warnings.toList()
        warnings.clear()
        changed()
        obsolete.forEach { it.cancel() }
    }
}
