package io.toolbox.host.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
internal fun NetworkDomainConfirmationDialog(broker: ForegroundCapabilityBroker) {
    val request by broker.domainConfirmation.collectAsStateWithLifecycle()
    OverlayDialog(
        show = request != null,
        title = "允许连接此域名？",
        summary = request?.let {
            "${it.toolName} 请求通过 HTTPS 连接：\n\n${it.domain}\n\n允许后，此工具可向该域名发送数据。你可以在工具权限中撤销授权。"
        },
        onDismissRequest = { request?.let { broker.answerNetworkDomain(it, false) } },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one),
        ) {
            ToolBoxTextButton(
                label = "取消",
                onClick = { request?.let { broker.answerNetworkDomain(it, false) } },
                modifier = Modifier.weight(1f),
                contentColor = ToolBoxThemeTokens.colors.textPrimary,
            )
            ToolBoxPrimaryButton(
                label = "允许",
                onClick = { request?.let { broker.answerNetworkDomain(it, true) } },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
