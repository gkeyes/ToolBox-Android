package io.toolbox.host.browser

import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebView
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxModalDialog
import io.toolbox.core.ui.component.ToolBoxSecondaryButton
import io.toolbox.core.ui.component.ToolBoxText
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

/** All methods and WebView callbacks must be invoked on the UI thread. */
internal class BrowserSslController(currentPage: () -> WebView?) {
    private var revision by mutableIntStateOf(0)
    private val session = BrowserSslSession(
        currentPage = currentPage,
        clearPreferences = WebView::clearSslPreferences,
        changed = { revision++ },
    )

    val hasPrompt: Boolean
        get() {
            revision // Observe queue changes from Compose, including the host's toolbar lock.
            return session.first != null
        }

    fun handle(view: WebView, handler: SslErrorHandler, failure: SslError) {
        session.enqueue(
            view = view,
            url = failure.url.orEmpty(),
            reason = BrowserSslPromptPolicy.describe(
                hasUntrusted = failure.hasError(SslError.SSL_UNTRUSTED),
                hasIdMismatch = failure.hasError(SslError.SSL_IDMISMATCH),
                hasExpired = failure.hasError(SslError.SSL_EXPIRED),
                hasNotYetValid = failure.hasError(SslError.SSL_NOTYETVALID),
                hasDateInvalid = failure.hasError(SslError.SSL_DATE_INVALID),
                hasInvalid = failure.hasError(SslError.SSL_INVALID),
            ),
            proceed = handler::proceed,
            cancel = handler::cancel,
        )
    }

    fun onNavigationRequested(view: WebView, url: String? = null) = session.navigationRequested(view, url)
    fun onNavigationStarted(view: WebView, url: String) = session.navigationStarted(view, url)
    fun onDestroyPage(view: WebView) = session.destroyPage(view)

    @Composable
    fun Prompt(visible: Boolean = true) {
        revision
        val warning = session.first ?: return
        if (!visible) return
        val colors = ToolBoxThemeTokens.colors
        ToolBoxModalDialog(onDismissRequest = { session.resolve(warning.id, false) }) {
            ToolBoxText(
                "连接证书存在问题",
                modifier = Modifier.semantics { heading() },
                style = ToolBoxThemeTokens.textStyles.title.copy(
                    color = colors.textPrimary,
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.height(12.dp))
            ToolBoxText(warning.reason, style = ToolBoxThemeTokens.textStyles.body.copy(color = colors.textSecondary))
            Spacer(Modifier.height(8.dp))
            ToolBoxText(
                BrowserSslPromptPolicy.connectionLabel(warning.url),
                style = ToolBoxThemeTokens.textStyles.metadata.copy(color = colors.textSecondary),
            )
            Spacer(Modifier.height(12.dp))
            ToolBoxText(
                "这是当前页面或它所加载内容的连接。继续后，连接内容可能被窃取或篡改。系统 WebView 可能在本页浏览期间复用这次证书决定；离开或重新加载页面时会清除证书例外。",
                style = ToolBoxThemeTokens.textStyles.body.copy(color = colors.textSecondary),
            )
            if (session.size > 1) {
                Spacer(Modifier.height(8.dp))
                ToolBoxText(
                    "另有 ${session.size - 1} 个连接等待确认。",
                    style = ToolBoxThemeTokens.textStyles.metadata.copy(color = colors.textSecondary),
                )
            }
            Spacer(Modifier.height(24.dp))
            ToolBoxPrimaryButton(
                "允许本页继续加载",
                { session.resolve(warning.id, true) },
                modifier = Modifier.fillMaxWidth(),
                destructive = true,
            )
            Spacer(Modifier.height(12.dp))
            ToolBoxSecondaryButton(
                "取消此连接",
                { session.resolve(warning.id, false) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
