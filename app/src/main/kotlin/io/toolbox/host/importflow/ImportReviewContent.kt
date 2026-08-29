package io.toolbox.host.importflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.toolbox.core.ui.component.ToolBoxCard
import io.toolbox.core.ui.component.ToolBoxPermissionRow
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxRiskBadge
import io.toolbox.core.ui.component.ToolBoxRiskLevel
import io.toolbox.core.ui.component.ToolBoxStatusRow
import io.toolbox.core.ui.component.ToolBoxText
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.tool.packagekit.RiskFinding
import io.toolbox.tool.packagekit.RiskFindingCode
import io.toolbox.tool.packagekit.SecurityProfile

internal fun LazyListScope.reviewContent(
    state: ImportReviewUiState,
    review: ImportReviewFacts,
    onGrantChanged: (String, ImportGrantChoice) -> Unit,
) {
    item(key = "identity") { IdentityCard(review) }
    item(key = "structure") {
        GroupCard("结构与入口") {
            FactLine("入口", "${review.entry} · ToolBox API ${review.apiVersion}")
            FactLine("文件", "${review.fileCount} 个 · ${formatBytes(review.extractedBytes)} 解压后")
            FactLine("压缩包", formatBytes(review.compressedBytes))
            FactLine("安全配置", if (review.securityProfile == SecurityProfile.STRICT) "Strict" else "Compat")
            if (review.files.isNotEmpty()) FactLine("文件清单", review.files.joinToString("、"))
        }
    }
    item(key = "security") {
        GroupCard("签名与发布者") {
            FactLine("签名", review.signature.detail)
            FactLine("发布者", review.publisherName ?: "未提供")
            review.publisherKeyId?.let { FactLine("密钥", it) }
        }
    }
    items(review.blockers, key = { "blocker-${it.code}-${it.detail}" }) { blocker ->
        ToolBoxStatusRow(blocker.code.name, blocker.detail, ToolBoxRiskLevel.Blocked, statusLabel = "禁止安装")
    }
    items(review.riskFindings, key = { "risk-${it.code}-${it.file}" }) { finding -> RiskRow(finding) }
    item(key = "permissions-title") { SectionLabel("申请权限") }
    if (review.permissions.isEmpty()) {
        item(key = "no-permissions") {
            MessageCard("不申请宿主能力", "此工具没有声明 ToolBox 权限。", ToolBoxRiskLevel.Low)
        }
    } else {
        itemsIndexed(review.permissions, key = { index, permission -> "${permission.name}-$index" }) { _, permission ->
            PermissionChoiceCard(permission, state.grants.getValue(permission.name)) { choice ->
                onGrantChanged(permission.name, choice)
            }
        }
    }
    if (review.networkDomains.isNotEmpty()) {
        item(key = "domains") {
            GroupCard("受控网络域名") {
                review.networkDomains.forEach { domain -> FactLine("HTTPS", domain) }
            }
        }
    }
}

internal fun LazyListScope.installedContent(state: ImportReviewUiState, onBack: () -> Unit) {
    val feedback = state.installFeedback ?: return
    item(key = "installed") {
        MessageCard(
            title = when (feedback) {
                is ImportInstallFeedback.Committed -> "安装完成"
                is ImportInstallFeedback.AlreadyCommitted -> "已经安装"
                is ImportInstallFeedback.CommittedRecoveryPending -> "安装已提交，仍需恢复整理"
            },
            message = "${feedback.toolId} · 版本代码 ${feedback.versionCode}",
            level = if (feedback is ImportInstallFeedback.CommittedRecoveryPending) {
                ToolBoxRiskLevel.Medium
            } else {
                state.review?.signature?.riskLevel ?: ToolBoxRiskLevel.Low
            },
            actionLabel = "返回工具列表",
            onAction = onBack,
        )
    }
}

@Composable
private fun IdentityCard(review: ImportReviewFacts) {
    ToolBoxCard(contentPadding = PaddingValues(ToolBoxThemeTokens.spacing.card)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.micro),
            ) {
                ToolBoxText(
                    review.toolName,
                    style = ToolBoxThemeTokens.textStyles.screenTitle.copy(
                        color = ToolBoxThemeTokens.colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                ToolBoxText(
                    "${review.toolId} · ${review.version} (${review.versionCode})",
                    style = ToolBoxThemeTokens.textStyles.metadata.copy(color = ToolBoxThemeTokens.colors.textSecondary),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ToolBoxText(
                    review.sourceName,
                    style = ToolBoxThemeTokens.textStyles.metadata.copy(color = ToolBoxThemeTokens.colors.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(ToolBoxThemeTokens.spacing.one))
            ToolBoxRiskBadge(review.signature.riskLevel, label = review.signature.label)
        }
    }
}

@Composable
private fun PermissionChoiceCard(
    permission: ImportPermissionFact,
    choice: ImportGrantChoice,
    onChoice: (ImportGrantChoice) -> Unit,
) {
    ToolBoxCard(contentPadding = PaddingValues(vertical = ToolBoxThemeTokens.spacing.half)) {
        ToolBoxPermissionRow(
            title = permission.displayName,
            summary = "${permission.reason}${if (permission.required) " · 必需" else " · 可选"}",
            riskLevel = permission.riskLevel,
            statusLabel = if (choice == ImportGrantChoice.ALLOW_SESSION) "本次会话" else "拒绝",
            contained = false,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = ToolBoxThemeTokens.spacing.row,
                vertical = ToolBoxThemeTokens.spacing.compact,
            ),
            horizontalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one),
        ) {
            CompactChoice("拒绝", choice == ImportGrantChoice.DENY, { onChoice(ImportGrantChoice.DENY) }, Modifier.weight(1f))
            CompactChoice(
                "允许本次会话",
                choice == ImportGrantChoice.ALLOW_SESSION,
                { onChoice(ImportGrantChoice.ALLOW_SESSION) },
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CompactChoice(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    ToolBoxCard(
        modifier,
        onClick,
        contentPadding = PaddingValues(
            horizontal = ToolBoxThemeTokens.spacing.row,
            vertical = ToolBoxThemeTokens.spacing.oneHalf,
        ),
    ) {
        ToolBoxText(
            if (selected) "✓ $label" else label,
            style = ToolBoxThemeTokens.textStyles.label.copy(
                color = if (selected) ToolBoxThemeTokens.colors.primary else ToolBoxThemeTokens.colors.textSecondary,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            ),
        )
    }
}

@Composable
private fun RiskRow(finding: RiskFinding) {
    ToolBoxStatusRow(
        finding.code.displayName,
        "${finding.file} · ${finding.detail}",
        if (finding.code == RiskFindingCode.DYNAMIC_CODE) ToolBoxRiskLevel.High else ToolBoxRiskLevel.Medium,
        statusLabel = "风险提示",
    )
}
