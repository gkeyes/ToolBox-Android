package io.toolbox.host.importflow

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.component.ToolBoxCard
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxRiskBadge
import io.toolbox.core.ui.component.ToolBoxRiskLevel
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.tool.packagekit.RiskFindingCode
import io.toolbox.tool.packagekit.SignatureEvidence
import io.toolbox.tool.packagekit.SignatureState

@Composable
internal fun MessageCard(
    title: String,
    message: String,
    level: ToolBoxRiskLevel,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    ToolBoxCard(contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    title,
                    style = ToolBoxThemeTokens.textStyles.body.copy(
                        color = ToolBoxThemeTokens.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Spacer(Modifier.height(3.dp))
                BasicText(
                    message,
                    style = ToolBoxThemeTokens.textStyles.metadata.copy(color = ToolBoxThemeTokens.colors.textSecondary),
                )
            }
            Spacer(Modifier.width(8.dp))
            ToolBoxRiskBadge(level)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(10.dp))
            ToolBoxPrimaryButton(actionLabel, onAction, Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun CompactActionCard(
    title: String,
    summary: String,
    actionLabel: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    ToolBoxCard(contentPadding = PaddingValues(14.dp)) {
        BasicText(
            title,
            style = ToolBoxThemeTokens.textStyles.body.copy(
                color = ToolBoxThemeTokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        Spacer(Modifier.height(2.dp))
        BasicText(
            summary,
            style = ToolBoxThemeTokens.textStyles.metadata.copy(color = ToolBoxThemeTokens.colors.textSecondary),
        )
        Spacer(Modifier.height(8.dp))
        ToolBoxPrimaryButton(actionLabel, onClick, Modifier.fillMaxWidth(), enabled)
    }
}

@Composable
internal fun GroupCard(title: String, content: @Composable () -> Unit) {
    ToolBoxCard(contentPadding = PaddingValues(14.dp)) {
        SectionLabel(title)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
internal fun SectionLabel(text: String) {
    BasicText(
        text,
        style = ToolBoxThemeTokens.textStyles.body.copy(
            color = ToolBoxThemeTokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        ),
    )
}

@Composable
internal fun FactLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        BasicText(
            label,
            modifier = Modifier.width(72.dp),
            style = ToolBoxThemeTokens.textStyles.metadata.copy(color = ToolBoxThemeTokens.colors.textSecondary),
        )
        BasicText(
            value,
            modifier = Modifier.weight(1f),
            style = ToolBoxThemeTokens.textStyles.metadata.copy(color = ToolBoxThemeTokens.colors.textPrimary),
        )
    }
}

internal val SignatureEvidence.riskLevel: ToolBoxRiskLevel
    get() = when (state) {
        SignatureState.VERIFIED_TRUSTED -> ToolBoxRiskLevel.Trusted
        SignatureState.VERIFIED_UNKNOWN -> ToolBoxRiskLevel.Medium
        SignatureState.UNSIGNED -> ToolBoxRiskLevel.Unsigned
        SignatureState.INVALID -> ToolBoxRiskLevel.Blocked
    }

internal val SignatureEvidence.label: String
    get() = when (state) {
        SignatureState.VERIFIED_TRUSTED -> "可信签名"
        SignatureState.VERIFIED_UNKNOWN -> "未知发布者"
        SignatureState.UNSIGNED -> "未签名"
        SignatureState.INVALID -> "无效签名"
    }

internal val ImportPermissionFact.displayName: String
    get() = when (name) {
        "storage" -> "专属存储"
        "storage.secure" -> "安全存储"
        "clipboard.write" -> "写入剪贴板"
        "clipboard.read" -> "读取剪贴板"
        "share" -> "系统分享"
        "files.open" -> "打开文件"
        "files.save" -> "保存文件"
        "network" -> "受控网络访问"
        "device.basic" -> "设备基础信息"
        "haptics" -> "触感反馈"
        "notifications" -> "通知"
        "shortcuts" -> "桌面快捷方式"
        "camera" -> "相机"
        "location" -> "位置"
        else -> name
    }

internal val ImportPermissionFact.riskLevel: ToolBoxRiskLevel
    get() = when (name) {
        "storage", "haptics", "device.basic" -> ToolBoxRiskLevel.Low
        "clipboard.read", "files.open", "camera", "location" -> ToolBoxRiskLevel.High
        else -> ToolBoxRiskLevel.Medium
    }

internal val RiskFindingCode.displayName: String
    get() = when (this) {
        RiskFindingCode.INLINE_SCRIPT -> "内联脚本"
        RiskFindingCode.DYNAMIC_CODE -> "动态代码"
        RiskFindingCode.EMBEDDED_FRAME -> "嵌入页面"
        RiskFindingCode.REMOTE_REFERENCE -> "远程引用"
    }

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
