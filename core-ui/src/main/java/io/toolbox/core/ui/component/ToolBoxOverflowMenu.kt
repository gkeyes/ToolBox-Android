package io.toolbox.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.window.WindowListPopup

data class ToolBoxMenuAction(
    val label: String,
    val icon: ToolBoxIconKey,
    val testTag: String,
    val onClick: () -> Unit,
)

/** Anchored native menu; finish dismissing its window before opening another surface. */
@Composable
fun ToolBoxOverflowMenu(
    contentDescription: String,
    actions: List<ToolBoxMenuAction>,
    menuTestTag: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    Box {
        ToolBoxIconButton(
            icon = ToolBoxIconKey.More,
            contentDescription = contentDescription,
            onClick = { expanded = true },
            modifier = modifier.semantics { stateDescription = if (expanded) "已展开" else "已收起" },
            tint = ToolBoxThemeTokens.colors.primary,
        )
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
            ListPopupColumn {
                actions.forEach { action ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(action.testTag)
                            .clickable(enabled = expanded, role = Role.Button) {
                                pendingAction = action.onClick
                                expanded = false
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ToolBoxIcon(action.icon, null)
                        ToolBoxText(action.label, style = ToolBoxThemeTokens.textStyles.body.copy(
                            color = ToolBoxThemeTokens.colors.textPrimary,
                        ))
                    }
                }
            }
        }
    }
}
