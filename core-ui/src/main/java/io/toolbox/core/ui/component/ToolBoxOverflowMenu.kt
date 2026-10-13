package io.toolbox.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

data class ToolBoxMenuAction(
    val label: String,
    val icon: ToolBoxIconKey,
    val testTag: String,
    val onClick: () -> Unit,
    /** Shown in the destructive red, separated from the actions above it by a menu gap. */
    val destructive: Boolean = false,
)

/** Anchored native menu; finish dismissing its window before opening another surface. */
@Composable
fun ToolBoxOverflowMenu(
    contentDescription: String,
    actions: List<ToolBoxMenuAction>,
    menuTestTag: String,
    modifier: Modifier = Modifier,
    glassState: ToolBoxGlassState? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    Box {
        val stateModifier = modifier.semantics { stateDescription = if (expanded) "已展开" else "已收起" }
        if (glassState != null) {
            ToolBoxGlassIconButton(
                icon = ToolBoxIconKey.More,
                contentDescription = contentDescription,
                onClick = { expanded = true },
                glassState = glassState,
                modifier = stateModifier,
            )
        } else {
            ToolBoxIconButton(
                icon = ToolBoxIconKey.More,
                contentDescription = contentDescription,
                onClick = { expanded = true },
                modifier = stateModifier,
                tint = ToolBoxThemeTokens.colors.primary,
            )
        }
        // Miuix draws an opaque 16dp surface behind the list; make it transparent so the iOS
        // context-menu panel below supplies the shape, material, rim and shadow.
        MiuixTheme(colors = MiuixTheme.colorScheme.copy(surfaceContainer = Color.Transparent)) {
            WindowListPopup(
                show = expanded,
                alignment = PopupPositionProvider.Align.End,
                enableWindowDim = false,
                popupModifier = Modifier.testTag(menuTestTag).semantics { paneTitle = contentDescription },
                onDismissRequest = {
                    if (expanded) {
                        pendingAction = null
                        expanded = false
                    }
                },
                onDismissFinished = {
                    val action = pendingAction
                    pendingAction = null
                    action?.invoke()
                },
            ) {
                ToolBoxContextMenuPanel {
                    actions.forEachIndexed { index, action ->
                        if (index > 0) {
                            val previous = actions[index - 1]
                            if (action.destructive && !previous.destructive) ToolBoxContextMenuGap()
                            else ToolBoxContextMenuSeparator()
                        }
                        ToolBoxContextMenuItem(
                            label = action.label,
                            icon = action.icon,
                            destructive = action.destructive,
                            enabled = expanded,
                            modifier = Modifier.testTag(action.testTag),
                            onClick = {
                                pendingAction = action.onClick
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

private val ContextMenuShape = ToolBoxContinuousShape(26.dp)
private val ContextMenuItemShape = ToolBoxContinuousShape(20.dp)

/**
 * iOS 27 context-menu panel: a 26dp continuous rounded panel with an elevated material fill,
 * a light rim and a soft drop shadow. The outer padding leaves room for the shadow inside the
 * popup window's own clip.
 */
@Composable
private fun ToolBoxContextMenuPanel(content: @Composable ColumnScope.() -> Unit) {
    val colors = ToolBoxThemeTokens.colors
    val materials = ToolBoxThemeTokens.materials
    val dark = colors.surface.luminance() < 0.5f
    val fill = if (dark) Color(0xFF2C2C2E) else Color(0xFFFBFBFD)
    Box(Modifier.padding(10.dp)) {
        Column(
            Modifier
                .width(250.dp)
                .shadow(elevation = 18.dp, shape = ContextMenuShape, clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.22f), spotColor = Color.Black.copy(alpha = 0.22f))
                .clip(ContextMenuShape)
                .background(fill)
                .border(0.5.dp, materials.glassBorder, ContextMenuShape)
                .verticalScroll(rememberScrollState())
                .padding(6.dp),
            content = content,
        )
    }
}

@Composable
private fun ToolBoxContextMenuItem(
    label: String,
    icon: ToolBoxIconKey,
    destructive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ToolBoxThemeTokens.colors
    val tint = if (destructive) colors.danger else colors.textPrimary
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(ContextMenuItemShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolBoxText(
            label,
            modifier = Modifier.weight(1f),
            style = ToolBoxThemeTokens.textStyles.body.copy(color = tint),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        ToolBoxIcon(icon, null, Modifier.size(20.dp), tint = tint)
    }
}

@Composable
private fun ToolBoxContextMenuSeparator() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(0.5.dp)
            .background(ToolBoxThemeTokens.colors.divider),
    )
}

/** The thick band iOS menus use to set destructive actions apart. */
@Composable
private fun ToolBoxContextMenuGap() {
    val dark = ToolBoxThemeTokens.colors.surface.luminance() < 0.5f
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .height(8.dp)
            .background(Color.Black.copy(alpha = if (dark) 0.28f else 0.06f)),
    )
}
