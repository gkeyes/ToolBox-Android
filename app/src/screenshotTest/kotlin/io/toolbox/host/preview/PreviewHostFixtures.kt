package io.toolbox.host.preview

import io.toolbox.core.data.HostSettings
import io.toolbox.core.data.ThemeMode
import io.toolbox.host.catalog.CatalogTool
import io.toolbox.host.catalog.CatalogUiState
import io.toolbox.host.permissions.PermissionCenterUiState
import io.toolbox.host.permissions.PermissionItem
import io.toolbox.host.settings.SettingsUiState

internal object PreviewHostFixtures {
    val catalog = CatalogUiState(
        isLoaded = true,
        tools = listOf(
            tool("io.toolbox.positioncalculator", "仓位计算器", "1.0.0", 7_324L),
            tool("io.toolbox.quicknote", "快速笔记", "1.0.0", 6_128L),
            tool("io.toolbox.backgrounddemo", "后台任务演示", "1.0.0", 5_120L),
        ),
    )

    val permissionCenter = PermissionCenterUiState(
        toolName = "仓位计算器",
        loaded = true,
        items = listOf(
            PermissionItem("storage", "工具存储", "保存计算输入与配置", true, emptyList()),
            PermissionItem("clipboard.write", "写入剪贴板", "复制计算结果", true, emptyList()),
            PermissionItem("haptics", "触感反馈", "计算完成时提供轻触反馈", true, emptyList()),
        ),
    )

    val settings = SettingsUiState(
        settings = HostSettings(theme = ThemeMode.SYSTEM, backgroundEnabled = true),
        loaded = true,
    )

    private fun tool(id: String, name: String, version: String, bytes: Long) = CatalogTool(
        toolId = id,
        name = name,
        versionCode = 1,
        versionName = version,
        bundleBytes = bytes,
        lastOpenedAt = null,
    )
}
