package io.toolbox.host.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.toolbox.core.ui.theme.ToolBoxTheme

@Preview(name = "首页", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun HomePreview() = ToolBoxTheme { HomeScreen(ProductionHostState.freshInstall().home, {}, {}, {}) }

@Preview(name = "工具管理", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun ToolManagerPreview() = ToolBoxTheme { ToolManagerScreen(ProductionHostState.freshInstall().toolManager, {}, {}, {}) }

@Preview(name = "导入审核", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun ImportReviewPreview() = ToolBoxTheme { ImportReviewScreen {} }

@Preview(name = "权限中心", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun PermissionCenterPreview() = ToolBoxTheme { PermissionCenterScreen {} }

@Preview(name = "设置", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun SettingsPreview() = ToolBoxTheme { SettingsScreen {} }

@Preview(name = "运行外壳", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun RuntimeShellPreview() = ToolBoxTheme { RuntimeShellScreen {} }
