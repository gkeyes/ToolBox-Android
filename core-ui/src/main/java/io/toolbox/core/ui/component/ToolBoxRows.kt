package io.toolbox.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = toolBoxSearchFieldMinHeight())
            .toolBoxOpenDesignSurface(RoundedCornerShape(ToolBoxThemeTokens.radii.control))
            .padding(start = 13.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolBoxIcon(ToolBoxIconKey.Search, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value, onValueChange = onValueChange, singleLine = true,
            modifier = Modifier.weight(1f).padding(vertical = 10.dp).focusRequester(focusRequester)
                .semantics { this.contentDescription = contentDescription },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
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
        if (value.isNotEmpty()) {
            ToolBoxIconButton(ToolBoxIconKey.Close, "清空${contentDescription}", onClick = {
                onValueChange("")
                focusRequester.requestFocus()
            })
        } else Spacer(Modifier.width(8.dp))
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
        modifier = modifier.fillMaxWidth().heightIn(min = if (summary.isNullOrBlank()) 56.dp else 64.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { ToolBoxPreferenceIcon(it, enabled); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            ToolBoxText(title, style = ToolBoxThemeTokens.textStyles.title.copy(
                color = if (enabled) ToolBoxThemeTokens.colors.textPrimary else ToolBoxThemeTokens.disabledContent))
            summary?.let { ToolBoxText(it, style = ToolBoxThemeTokens.textStyles.metadata.copy(
                color = if (enabled) ToolBoxThemeTokens.colors.textSecondary else ToolBoxThemeTokens.disabledContent)) }
        }
        Spacer(Modifier.width(8.dp))
        ToolBoxIcon(ToolBoxIconKey.ChevronRight, null, Modifier.size(18.dp),
            tint = if (enabled) ToolBoxThemeTokens.colors.textSecondary else ToolBoxThemeTokens.disabledContent)
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
        startAction = icon?.let { key -> ({ ToolBoxPreferenceIcon(key, enabled) }) },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = maxOf(sizes.denseRow, sizes.touchTarget))
            .semantics {
                role = Role.Switch
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                if (!enabled) disabled()
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
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (summary.isNullOrBlank()) ToolBoxThemeTokens.sizes.denseRow else 64.dp)
            .padding(horizontal = spacing.oneHalf, vertical = spacing.one),
        contentAlignment = Alignment.CenterStart,
    ) {
        val stacked = maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.3f
        val labelWidth = (maxWidth - if (icon != null) 36.dp + spacing.oneHalf else 0.dp) * 0.4f
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            icon?.let {
                ToolBoxPreferenceIcon(it)
                Spacer(Modifier.width(spacing.oneHalf))
            }
            if (stacked) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.half)) {
                    ToolBoxValueLabel(title, summary)
                    ToolBoxText(value, style = ToolBoxThemeTokens.textStyles.metadata.copy(
                        color = valueColor, fontWeight = FontWeight.Medium,
                    ))
                }
            } else {
                ToolBoxValueLabel(title, summary, Modifier.widthIn(max = labelWidth))
                Spacer(Modifier.width(spacing.oneHalf))
                ToolBoxText(
                    text = value,
                    modifier = Modifier.weight(1f),
                    style = ToolBoxThemeTokens.textStyles.metadata.copy(
                        color = valueColor,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.End,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ToolBoxValueLabel(title: String, summary: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        ToolBoxText(title, style = ToolBoxThemeTokens.textStyles.title.copy(
            color = ToolBoxThemeTokens.colors.textPrimary, fontWeight = FontWeight.Medium,
        ))
        summary?.let {
            ToolBoxText(it, style = ToolBoxThemeTokens.textStyles.metadata.copy(color = ToolBoxThemeTokens.colors.textSecondary))
        }
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
        startAction = icon?.let { key -> ({ ToolBoxPreferenceIcon(key, enabled) }) },
        endActions = {
            if (selectedLabel.isNotBlank()) {
                ToolBoxText(
                    text = selectedLabel,
                    style = ToolBoxThemeTokens.textStyles.metadata.copy(
                        color = if (enabled) ToolBoxThemeTokens.colors.textSecondary else ToolBoxThemeTokens.disabledContent,
                        textAlign = TextAlign.End,
                    ),
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = maxOf(sizes.denseRow, sizes.touchTarget))
            .semantics { role = Role.Button; if (!enabled) disabled() },
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
private fun ToolBoxPreferenceIcon(icon: ToolBoxIconKey, enabled: Boolean = true) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(if (enabled) ToolBoxThemeTokens.colors.softPrimary else ToolBoxThemeTokens.colors.surfaceMuted),
        contentAlignment = Alignment.Center,
    ) {
        ToolBoxIcon(
            icon = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (enabled) ToolBoxThemeTokens.colors.primary else ToolBoxThemeTokens.disabledContent,
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
