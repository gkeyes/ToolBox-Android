package io.toolbox.host.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import io.toolbox.core.ui.component.ToolBoxCard
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxRiskBadge
import io.toolbox.core.ui.component.ToolBoxRiskLevel
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

private enum class RiskTone { Safe, Warning, Danger }

@Composable
internal fun ToolCard(tool: ToolCardModel, onLaunchTool: (String) -> Unit) {
    SurfaceCard(
        modifier = Modifier
            .clickable(role = Role.Button) { onLaunchTool(tool.toolId) }
            .testTag(HostTestTags.ToolCardPrefix + tool.toolId)
            .semantics { contentDescription = "打开${tool.title}，${tool.trust.label}" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolGlyph(tool.symbol)
            Spacer(Modifier.width(ToolBoxThemeTokens.spacing.two))
            Column(Modifier.weight(1f)) {
                AppText(
                    tool.title,
                    textStyle = ToolBoxThemeTokens.textStyles.title,
                    weight = FontWeight.Bold,
                    maxLines = 1,
                )
                AppText(
                    tool.metadata,
                    textStyle = ToolBoxThemeTokens.textStyles.metadata,
                    color = ToolBoxThemeTokens.colors.textSecondary,
                    maxLines = 2,
                )
            }
            RiskBadge(tool.trust.label, tool.trust.tone)
        }
    }
}

@Composable
internal fun ToolGlyph(symbol: String, size: Dp = ToolBoxThemeTokens.sizes.toolGlyph) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(ToolBoxThemeTokens.radii.denseSurface))
            .background(ToolBoxThemeTokens.colors.divider),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            symbol,
            color = ToolBoxThemeTokens.colors.textPrimary,
            textStyle = ToolBoxThemeTokens.textStyles.title,
            weight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun SurfaceCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = ToolBoxThemeTokens.spacing.two,
    content: @Composable ColumnScope.() -> Unit,
) {
    ToolBoxCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(contentPadding),
        content = content,
    )
}

@Composable
internal fun SectionHeader(title: String, action: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AppText(
            title,
            modifier = Modifier.weight(1f).semantics { heading() },
            textStyle = ToolBoxThemeTokens.textStyles.sectionTitle,
        )
        if (action.isNotEmpty()) {
            AppText(
                action,
                textStyle = ToolBoxThemeTokens.textStyles.metadata,
                color = ToolBoxThemeTokens.colors.primary,
                weight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun RiskBadge(label: String, tone: RiskTone) {
    ToolBoxRiskBadge(
        level = when (tone) {
            RiskTone.Safe -> ToolBoxRiskLevel.Trusted
            RiskTone.Warning -> if (label == "未签名") ToolBoxRiskLevel.Unsigned else ToolBoxRiskLevel.Medium
            RiskTone.Danger -> ToolBoxRiskLevel.High
        },
        label = label,
    )
}

@Composable
internal fun EmptyCatalogState(onImport: () -> Unit) {
    SurfaceCard(modifier = Modifier.testTag(HostTestTags.CatalogEmptyState)) {
        Column(verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.two)) {
            AppText("还没有已安装工具", textStyle = ToolBoxThemeTokens.textStyles.sectionTitle)
            AppText(
                "选择一个 .tbx 工具包后，ToolBox 会先检查结构、权限和风险，再显示可安装的信息。",
                textStyle = ToolBoxThemeTokens.textStyles.metadata,
                color = ToolBoxThemeTokens.colors.textSecondary,
            )
            ToolBoxPrimaryButton("导入 .tbx 工具包", onClick = onImport)
        }
    }
}

@Composable
internal fun CatalogStatusState(message: String) {
    SurfaceCard {
        AppText(message, textStyle = ToolBoxThemeTokens.textStyles.metadata, color = ToolBoxThemeTokens.colors.textSecondary)
    }
}

private val ToolTrust.label: String
    get() = when (this) {
        ToolTrust.Trusted -> "可信"
        ToolTrust.NeedsReview -> "待审核"
        ToolTrust.HighRisk -> "高风险"
    }

private val ToolTrust.tone: RiskTone
    get() = when (this) {
        ToolTrust.Trusted -> RiskTone.Safe
        ToolTrust.NeedsReview -> RiskTone.Warning
        ToolTrust.HighRisk -> RiskTone.Danger
    }

@Composable
internal fun AppText(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 14,
    color: Color = ToolBoxThemeTokens.colors.textPrimary,
    weight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign = TextAlign.Start,
    textStyle: TextStyle? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = (textStyle ?: TextStyle(fontSize = size.sp)).copy(
            color = color,
            fontWeight = weight ?: textStyle?.fontWeight ?: FontWeight.Normal,
            textAlign = align,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
