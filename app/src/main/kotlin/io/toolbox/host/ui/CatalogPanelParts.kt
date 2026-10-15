package io.toolbox.host.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.component.*
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.catalog.CatalogTool
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CheckboxDefaults
import androidx.compose.foundation.layout.PaddingValues
import io.toolbox.core.ui.component.ToolBoxGlassIconButton
import io.toolbox.core.ui.component.ToolBoxGlassCapsule
import io.toolbox.core.ui.component.toolBoxClearGlassFill

@Composable
internal fun PanelEditorActions(
    cancel: () -> Unit, cancelEnabled: Boolean,
    confirmLabel: String, confirmEnabled: Boolean, confirmTag: String,
    destructive: Boolean = false, confirm: () -> Unit,
) {
    val largeText = LocalDensity.current.fontScale >= 1.3f
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        val buttons: @Composable (Modifier) -> Unit = { modifier ->
            ToolBoxSecondaryButton("取消", cancel, modifier.testTag("catalog_group_cancel"), enabled = cancelEnabled)
            ToolBoxPrimaryButton(confirmLabel, confirm, modifier.testTag(confirmTag), enabled = confirmEnabled, destructive = destructive)
        }
        if (maxWidth < 300.dp || largeText) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons(Modifier.fillMaxWidth()) }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { buttons(Modifier.weight(1f)) }
        }
    }
}

@Composable
internal fun PanelHeader(title: String, onClose: () -> Unit, onBack: (() -> Unit)? = null, tool: CatalogTool? = null, enabled: Boolean = true) {
    ToolBoxActionSheetHeader(Modifier.testTag("catalog_panel_header")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onBack != null) ToolBoxGlassIconButton(ToolBoxIconKey.Back, "返回", onBack, modifier = Modifier.testTag("catalog_panel_back"), enabled = enabled)
            if (tool != null) CatalogToolGlyph(tool.toolId, tool.versionCode, tool.visual(ToolBoxThemeTokens.colors.primary))
            ToolBoxText(title, Modifier.weight(1f).semantics { heading() }, style = ToolBoxThemeTokens.textStyles.headline.copy(color = ToolBoxThemeTokens.colors.textPrimary))
            ToolBoxGlassIconButton(ToolBoxIconKey.Close, "关闭", onClose, modifier = Modifier.testTag("catalog_panel_close"), enabled = enabled)
        }
    }
}

@Composable
internal fun PanelShortcut(
    label: String,
    icon: ToolBoxIconKey,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = ToolBoxThemeTokens.colors
    val haptics = rememberToolBoxHaptics()
    // iOS 26 glass capsules: clear glass, accent-tinted glass with white text once selected.
    val content = if (selected) Color.White else colors.textPrimary
    ToolBoxGlassCapsule(
        onClick = { haptics.tick(); onClick() },
        modifier = modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.46f }
            .semantics { if (busy) stateDescription = "正在保存" },
        enabled = enabled,
        surface = if (selected) colors.primary.copy(alpha = 0.9f) else toolBoxClearGlassFill(),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        if (busy) {
            ToolBoxBusyIndicator()
        } else {
            ToolBoxIcon(
                icon = icon,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = if (enabled) content else ToolBoxThemeTokens.disabledContent,
            )
        }
        ToolBoxText(
            label,
            Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            style = ToolBoxThemeTokens.textStyles.body.copy(
                color = if (enabled) content else ToolBoxThemeTokens.disabledContent,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            ),
        )
    }
}
@Composable
internal fun ToolCheckRow(tool: CatalogTool, selected: Boolean, enabled: Boolean, tag: String, modifier: Modifier = Modifier, onChecked: (Boolean) -> Unit) {
    PanelCheckRow(tool.name, selected, enabled, modifier.testTag(tag), icon = {
        CatalogToolGlyph(tool.toolId, tool.versionCode, tool.visual(ToolBoxThemeTokens.colors.primary), size = 36.dp)
    }, onChecked = onChecked)
}

@Composable
internal fun PanelCheckRow(
    title: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    busy: Boolean = false,
    summary: String? = null,
    onChecked: (Boolean) -> Unit,
) {
    val colors = ToolBoxThemeTokens.colors
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberToolBoxHaptics()
    val shape = toolBoxShape(ToolBoxThemeTokens.radii.denseSurface)
    Row(
        modifier.fillMaxWidth()
            .toolBoxPressFeedback(interaction, enabled = enabled, shape = shape, scale = ToolBoxMotion.RowPressScale)
            .clip(shape)
            .toggleable(selected, interactionSource = interaction, indication = null, enabled = enabled, role = Role.Checkbox,
                onValueChange = { haptics.toggle(it); onChecked(it) })
            .semantics { if (busy) stateDescription = "正在保存" }
            .heightIn(min = 60.dp).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolBoxText(title, style = ToolBoxThemeTokens.textStyles.body.copy(
                color = if (enabled) colors.textPrimary else ToolBoxThemeTokens.disabledContent))
            if (!summary.isNullOrBlank()) ToolBoxText(summary, style = ToolBoxThemeTokens.textStyles.metadata.copy(color = colors.textSecondary))
        }
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (busy) ToolBoxBusyIndicator() else Checkbox(
                state = if (selected) ToggleableState.On else ToggleableState.Off,
                onClick = null, enabled = enabled, modifier = Modifier.clearAndSetSemantics { },
                colors = CheckboxDefaults.checkboxColors(
                    checkedForegroundColor = colors.onPrimary,
                    checkedBackgroundColor = colors.primary,
                    uncheckedBackgroundColor = colors.divider,
                    disabledCheckedBackgroundColor = colors.primary.copy(alpha = 0.5f),
                    disabledUncheckedBackgroundColor = colors.divider,
                ),
            )
        }
    }
}

@Composable
internal fun PanelDivider() { Spacer(Modifier.fillMaxWidth().height(ToolBoxThemeTokens.spacing.xs)) }

@Composable
internal fun PanelEmpty(message: String) {
    ToolBoxStatusState(title = message, icon = ToolBoxIconKey.Search, modifier = Modifier.padding(vertical = 0.dp))
}

@Composable
internal fun PanelError(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        ToolBoxBanner(message, ToolBoxTone.Danger)
    }
}

internal fun matchingTools(tools: List<CatalogTool>, query: String): List<CatalogTool> {
    val term = query.trim()
    return if (term.isEmpty()) tools else tools.filter { it.name.contains(term, true) || it.toolId.contains(term, true) }
}
