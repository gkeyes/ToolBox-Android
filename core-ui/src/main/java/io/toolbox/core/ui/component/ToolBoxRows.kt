package io.toolbox.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.overlay.OverlayDialog

enum class ToolBoxRiskLevel {
    Trusted,
    Low,
    Medium,
    High,
    Blocked,
    Unsigned,
}

@Composable
fun ToolBoxRiskBadge(
    level: ToolBoxRiskLevel,
    modifier: Modifier = Modifier,
    label: String = level.defaultLabel(),
) {
    val colors = ToolBoxThemeTokens.colors
    val (container, content, icon) = when (level) {
        ToolBoxRiskLevel.Trusted, ToolBoxRiskLevel.Low -> Triple(colors.softSuccess, colors.success, ToolBoxIconKey.Shield)
        ToolBoxRiskLevel.Medium, ToolBoxRiskLevel.Unsigned -> Triple(colors.softWarning, colors.warning, ToolBoxIconKey.Shield)
        ToolBoxRiskLevel.High, ToolBoxRiskLevel.Blocked -> Triple(colors.softDanger, colors.danger, ToolBoxIconKey.Shield)
    }
    Row(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToolBoxIcon(icon = icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = content)
        BasicText(text = label, style = ToolBoxThemeTokens.textStyles.label.copy(color = content), maxLines = 1)
    }
}

@Composable
fun ToolBoxSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    contentDescription: String = placeholder,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = toolBoxSearchFieldMinHeight(LocalDensity.current.fontScale))
            .semantics { this.contentDescription = contentDescription },
        label = placeholder,
        useLabelAsPlaceholder = true,
        leadingIcon = {
            ToolBoxIcon(icon = ToolBoxIconKey.Search, contentDescription = null)
        },
        singleLine = true,
    )
}

internal fun toolBoxSearchFieldMinHeight(fontScale: Float) = 52.dp * fontScale.coerceAtLeast(1f)

@Composable
fun ToolBoxPermissionRow(
    title: String,
    summary: String,
    riskLevel: ToolBoxRiskLevel,
    modifier: Modifier = Modifier,
    statusLabel: String = riskLevel.defaultLabel(),
    icon: ToolBoxIconKey = ToolBoxIconKey.Shield,
    onClick: (() -> Unit)? = null,
    contained: Boolean = true,
) {
    if (contained) {
        ToolBoxCard(
            modifier = modifier.fillMaxWidth(),
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PermissionRowContent(title, summary, riskLevel, statusLabel, icon)
            }
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PermissionRowContent(title, summary, riskLevel, statusLabel, icon)
        }
    }
}

@Composable
private fun RowScope.PermissionRowContent(
    title: String,
    summary: String,
    riskLevel: ToolBoxRiskLevel,
    statusLabel: String,
    icon: ToolBoxIconKey,
) {
    ToolBoxIcon(
        icon = icon,
        contentDescription = null,
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(ToolBoxThemeTokens.colors.softPrimary)
            .padding(7.dp)
            .size(18.dp),
        tint = ToolBoxThemeTokens.colors.primary,
    )
    Spacer(Modifier.width(10.dp))
    Column(modifier = Modifier.weight(1f)) {
        BasicText(text = title, style = ToolBoxThemeTokens.textStyles.body.copy(color = ToolBoxThemeTokens.colors.textPrimary))
        Spacer(Modifier.height(1.dp))
        BasicText(
            text = summary,
            style = ToolBoxThemeTokens.textStyles.metadata.copy(color = ToolBoxThemeTokens.colors.textSecondary),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(Modifier.width(6.dp))
    ToolBoxRiskBadge(level = riskLevel, label = statusLabel)
}

@Composable
fun ToolBoxStatusRow(
    title: String,
    summary: String,
    status: ToolBoxRiskLevel,
    modifier: Modifier = Modifier,
    statusLabel: String = status.defaultLabel(),
    onClick: (() -> Unit)? = null,
    contained: Boolean = true,
) {
    ToolBoxPermissionRow(
        title = title,
        summary = summary,
        riskLevel = status,
        statusLabel = statusLabel,
        modifier = modifier,
        onClick = onClick,
        contained = contained,
    )
}

@Composable
fun ToolBoxSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    ArrowPreference(
        title = title,
        summary = summary,
        modifier = modifier
            .fillMaxWidth()
            .semantics { role = Role.Button },
        onClick = onClick,
        enabled = enabled,
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
fun ToolBoxSwitchSettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = onCheckedChange,
        title = title,
        summary = summary,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        enabled = enabled,
    )
}

data class ToolBoxSettingChoice(
    val value: String,
    val label: String,
    val summary: String? = null,
)

@Composable
fun ToolBoxChoiceSettingRow(
    title: String,
    selectedValue: String,
    choices: List<ToolBoxSettingChoice>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    val selectedLabel = choices.firstOrNull { it.value == selectedValue }?.label.orEmpty()
    var choiceDialogVisible by rememberSaveable(title) { mutableStateOf(false) }
    val combinedSummary = listOfNotNull(summary, selectedLabel.takeIf(String::isNotBlank))
        .joinToString(" · ")

    ArrowPreference(
        title = title,
        summary = combinedSummary.ifBlank { null },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        onClick = { if (enabled) choiceDialogVisible = true },
        enabled = enabled,
    )

    OverlayDialog(
        show = choiceDialogVisible,
        title = title,
        onDismissRequest = { choiceDialogVisible = false },
    ) {
        Column {
            choices.forEach { choice ->
                RadioButtonPreference(
                    title = choice.label,
                    summary = choice.summary,
                    selected = choice.value == selectedValue,
                    onClick = {
                        onSelected(choice.value)
                        choiceDialogVisible = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private fun ToolBoxRiskLevel.defaultLabel(): String = when (this) {
    ToolBoxRiskLevel.Trusted -> "可信"
    ToolBoxRiskLevel.Low -> "低"
    ToolBoxRiskLevel.Medium -> "中"
    ToolBoxRiskLevel.High -> "高"
    ToolBoxRiskLevel.Blocked -> "已阻止"
    ToolBoxRiskLevel.Unsigned -> "未签名"
}
