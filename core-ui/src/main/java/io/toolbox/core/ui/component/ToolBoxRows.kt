package io.toolbox.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.overlay.OverlayDialog

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
            .heightIn(min = toolBoxSearchFieldMinHeight())
            .semantics { this.contentDescription = contentDescription },
        label = placeholder,
        useLabelAsPlaceholder = true,
        leadingIcon = {
            Box(Modifier.padding(start = ToolBoxThemeTokens.spacing.oneHalf)) {
                ToolBoxIcon(icon = ToolBoxIconKey.Search, contentDescription = null)
            }
        },
        cornerRadius = ToolBoxThemeTokens.radii.control,
        singleLine = true,
    )
}

internal fun toolBoxSearchFieldMinHeight() = ToolBoxThemeTokens.sizes.touchTarget

@Composable
fun ToolBoxSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ToolBoxIconKey? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val spacing = ToolBoxThemeTokens.spacing
    ArrowPreference(
        title = title,
        summary = summary,
        startAction = icon?.let { key -> ({ ToolBoxPreferenceIcon(key) }) },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ToolBoxThemeTokens.sizes.touchTarget)
            .semantics { role = Role.Button },
        onClick = onClick,
        enabled = enabled,
        insideMargin = PaddingValues(horizontal = spacing.oneHalf, vertical = spacing.one),
    )
}

@Composable
fun ToolBoxSwitchSettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ToolBoxIconKey? = null,
    enabled: Boolean = true,
) {
    val sizes = ToolBoxThemeTokens.sizes
    val spacing = ToolBoxThemeTokens.spacing
    SwitchPreference(
        checked = checked,
        onCheckedChange = onCheckedChange,
        title = title,
        summary = summary,
        startAction = icon?.let { key -> ({ ToolBoxPreferenceIcon(key) }) },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = maxOf(sizes.denseRow, sizes.touchTarget))
            .semantics {
                role = Role.Switch
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            },
        insideMargin = PaddingValues(horizontal = spacing.oneHalf, vertical = spacing.one),
        enabled = enabled,
    )
}

@Composable
fun ToolBoxValueRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ToolBoxIconKey? = null,
    valueColor: androidx.compose.ui.graphics.Color = ToolBoxThemeTokens.colors.textSecondary,
) {
    val spacing = ToolBoxThemeTokens.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ToolBoxThemeTokens.sizes.denseRow)
            .padding(horizontal = spacing.oneHalf, vertical = spacing.one),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            ToolBoxPreferenceIcon(it)
            Spacer(Modifier.width(spacing.oneHalf))
        }
        Column(Modifier.weight(1f)) {
            ToolBoxText(
                text = title,
                style = ToolBoxThemeTokens.textStyles.title.copy(
                    color = ToolBoxThemeTokens.colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                ),
            )
            summary?.let {
                ToolBoxText(
                    text = it,
                    style = ToolBoxThemeTokens.textStyles.metadata.copy(
                        color = ToolBoxThemeTokens.colors.textSecondary,
                    ),
                )
            }
        }
        Spacer(Modifier.width(spacing.one))
        ToolBoxText(
            text = value,
            style = ToolBoxThemeTokens.textStyles.metadata.copy(
                color = valueColor,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 2,
        )
    }
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
    icon: ToolBoxIconKey? = null,
    enabled: Boolean = true,
) {
    val sizes = ToolBoxThemeTokens.sizes
    val spacing = ToolBoxThemeTokens.spacing
    val selectedLabel = choices.firstOrNull { it.value == selectedValue }?.label.orEmpty()
    var choiceDialogVisible by rememberSaveable(title) { mutableStateOf(false) }
    val combinedSummary = listOfNotNull(summary, selectedLabel.takeIf(String::isNotBlank))
        .joinToString(" · ")

    ArrowPreference(
        title = title,
        summary = combinedSummary.ifBlank { null },
        startAction = icon?.let { key -> ({ ToolBoxPreferenceIcon(key) }) },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = maxOf(sizes.denseRow, sizes.touchTarget)),
        insideMargin = PaddingValues(horizontal = spacing.oneHalf, vertical = spacing.one),
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
                        .heightIn(min = maxOf(sizes.denseRow, sizes.touchTarget)),
                    insideMargin = PaddingValues(horizontal = spacing.oneHalf, vertical = spacing.one),
                )
            }
        }
    }
}

@Composable
private fun ToolBoxPreferenceIcon(icon: ToolBoxIconKey) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(ToolBoxThemeTokens.colors.softPrimary),
        contentAlignment = Alignment.Center,
    ) {
        ToolBoxIcon(
            icon = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = ToolBoxThemeTokens.colors.primary,
        )
    }
}
