package io.toolbox.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
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
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = toolBoxSearchFieldMinHeight())
            .toolBoxOpenDesignSurface(RoundedCornerShape(17.dp))
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolBoxIcon(ToolBoxIconKey.Search, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value, onValueChange = onValueChange, singleLine = true,
            modifier = Modifier.weight(1f).semantics { this.contentDescription = contentDescription },
            textStyle = ToolBoxThemeTokens.textStyles.body.copy(color = ToolBoxThemeTokens.colors.textPrimary),
            cursorBrush = SolidColor(ToolBoxThemeTokens.colors.primary),
            decorationBox = { field ->
                Box {
                    if (value.isEmpty()) ToolBoxText(placeholder, style = ToolBoxThemeTokens.textStyles.body.copy(
                        color = ToolBoxThemeTokens.colors.textSecondary))
                    field()
                }
            },
        )
    }
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
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 68.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { ToolBoxPreferenceIcon(it); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            ToolBoxText(title, style = ToolBoxThemeTokens.textStyles.title.copy(color = ToolBoxThemeTokens.colors.textPrimary))
            summary?.let { ToolBoxText(it, style = ToolBoxThemeTokens.textStyles.metadata.copy(color = ToolBoxThemeTokens.colors.textSecondary)) }
        }
        Spacer(Modifier.width(8.dp))
        ToolBoxIcon(ToolBoxIconKey.ChevronRight, null, Modifier.size(18.dp))
    }
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
    dialogSummary: String? = null,
    icon: ToolBoxIconKey? = null,
    enabled: Boolean = true,
) {
    val sizes = ToolBoxThemeTokens.sizes
    val spacing = ToolBoxThemeTokens.spacing
    val selectedLabel = choices.firstOrNull { it.value == selectedValue }?.label.orEmpty()
    var choiceDialogVisible by rememberSaveable(title) { mutableStateOf(false) }

    ArrowPreference(
        title = title,
        startAction = icon?.let { key -> ({ ToolBoxPreferenceIcon(key) }) },
        endActions = {
            if (selectedLabel.isNotBlank()) {
                ToolBoxText(
                    text = selectedLabel,
                    style = ToolBoxThemeTokens.textStyles.metadata.copy(
                        color = ToolBoxThemeTokens.colors.textSecondary,
                        textAlign = TextAlign.End,
                    ),
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = maxOf(sizes.denseRow, sizes.touchTarget))
            .semantics { role = Role.Button },
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
            dialogSummary?.let { explanation ->
                ToolBoxText(
                    text = explanation,
                    modifier = Modifier.padding(horizontal = spacing.oneHalf, vertical = spacing.one),
                    style = ToolBoxThemeTokens.textStyles.metadata.copy(
                        color = ToolBoxThemeTokens.colors.textSecondary,
                    ),
                )
            }
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

@Composable
fun ToolBoxRadioSettingRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    RadioButtonPreference(
        title = title, selected = selected, onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = ToolBoxThemeTokens.sizes.touchTarget),
        insideMargin = PaddingValues(horizontal = 13.dp, vertical = 10.dp),
    )
}
