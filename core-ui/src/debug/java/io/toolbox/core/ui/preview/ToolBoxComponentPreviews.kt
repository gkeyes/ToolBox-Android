package io.toolbox.core.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.component.ToolBoxCard
import io.toolbox.core.ui.component.ToolBoxGroupDivider
import io.toolbox.core.ui.component.ToolBoxGroupedSurface
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxNavigationBar
import io.toolbox.core.ui.component.ToolBoxNavigationItem
import io.toolbox.core.ui.component.ToolBoxSearchField
import io.toolbox.core.ui.component.ToolBoxSettingRow
import io.toolbox.core.ui.component.ToolBoxSwitchSettingRow
import io.toolbox.core.ui.component.ToolBoxText
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun ToolBoxRowsPreview() {
    ToolBoxTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ToolBoxSearchField(value = "", onValueChange = {}, placeholder = "搜索工具")
            ToolBoxGroupedSurface {
                ToolBoxSwitchSettingRow(
                    title = "写入剪贴板",
                    summary = "复制工具生成的内容",
                    checked = true,
                    onCheckedChange = {},
                    icon = ToolBoxIconKey.Clipboard,
                )
                ToolBoxGroupDivider()
                ToolBoxSettingRow(
                    title = "工具权限",
                    summary = "按工具管理已声明能力",
                    icon = ToolBoxIconKey.Shield,
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun ToolBoxCardAndNavigationPreview() {
    ToolBoxTheme {
        Column {
            ToolBoxCard(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                ToolBoxText(
                    text = "12 个工具，随用随开",
                    style = ToolBoxThemeTokens.textStyles.sectionTitle.copy(color = ToolBoxThemeTokens.colors.textPrimary),
                )
            }
            ToolBoxNavigationBar(
                items = listOf(
                    ToolBoxNavigationItem("tools", "工具", ToolBoxIconKey.Tools),
                    ToolBoxNavigationItem("settings", "设置", ToolBoxIconKey.Settings),
                ),
                selectedId = "tools",
                onItemSelected = {},
            )
        }
    }
}
