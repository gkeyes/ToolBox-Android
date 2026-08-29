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
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxNavigationBar
import io.toolbox.core.ui.component.ToolBoxNavigationItem
import io.toolbox.core.ui.component.ToolBoxPermissionRow
import io.toolbox.core.ui.component.ToolBoxRiskBadge
import io.toolbox.core.ui.component.ToolBoxRiskLevel
import io.toolbox.core.ui.component.ToolBoxSearchField
import io.toolbox.core.ui.component.ToolBoxSettingRow
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
            ToolBoxSearchField(value = "", onValueChange = {}, placeholder = "搜索工具、分类或标签")
            ToolBoxPermissionRow(
                title = "写入剪贴板",
                summary = "复制计算结果，不读取现有内容",
                riskLevel = ToolBoxRiskLevel.Medium,
                icon = ToolBoxIconKey.Clipboard,
            )
            ToolBoxSettingRow(title = "未签名工具严格模式", summary = "限制直接网络与持久高风险授权")
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
                ToolBoxRiskBadge(level = ToolBoxRiskLevel.Trusted)
            }
            ToolBoxNavigationBar(
                items = listOf(
                    ToolBoxNavigationItem("home", "首页", ToolBoxIconKey.Home),
                    ToolBoxNavigationItem("tools", "工具", ToolBoxIconKey.Tools),
                    ToolBoxNavigationItem("settings", "设置", ToolBoxIconKey.Settings),
                ),
                selectedId = "home",
                onItemSelected = {},
            )
        }
    }
}
