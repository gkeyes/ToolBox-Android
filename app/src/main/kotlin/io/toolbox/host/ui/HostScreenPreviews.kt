package io.toolbox.host.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.tooling.preview.Preview
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.host.catalog.CatalogUiState
import io.toolbox.host.catalog.HomeScreenState
import io.toolbox.host.importflow.ImportReviewUiState
import io.toolbox.host.permissions.PermissionCenterUiState
import io.toolbox.host.settings.SettingsUiState

@Preview(name = "首页", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun HomePreview() = ToolBoxTheme {
    HomeScreen(
        state = HomeScreenState(isLoaded = true),
        listState = rememberLazyListState(),
        onAction = {},
        onDestination = {},
        onImport = {},
        onOpenDetails = {},
    )
}

@Preview(name = "工具管理", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun ToolManagerPreview() = ToolBoxTheme {
    ToolManagerScreen(
        state = CatalogUiState(isLoaded = true),
        listState = rememberLazyListState(),
        onAction = {},
        onDestination = {},
        onImport = {},
        onOpenDetails = {},
    )
}

@Preview(name = "导入审核", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun ImportReviewPreview() = ToolBoxTheme {
    io.toolbox.host.importflow.ImportReviewScreen(
        state = ImportReviewUiState(),
        onBack = {},
        onPick = {},
        onRecover = {},
        onResume = {},
        onGrantChanged = { _, _ -> },
        onConfirmReview = {},
        onInstall = {},
        onDismissError = {},
    )
}

@Preview(name = "权限中心", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun PermissionCenterPreview() = ToolBoxTheme {
    io.toolbox.host.permissions.PermissionCenterScreen(
        state = PermissionCenterUiState("io.toolbox.example", isLoaded = true),
        onBack = {},
        onRevoke = {},
        onExplainRuntimeGranting = {},
        onDismissFeedback = {},
    )
}

@Preview(name = "设置", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun SettingsPreview() = ToolBoxTheme {
    PrimaryScreen(
        selected = MainDestination.Settings,
        onDestination = {},
        title = "设置",
        onImport = null,
    ) { contentPadding ->
        io.toolbox.host.settings.SettingsScreen(
            state = SettingsUiState(isLoaded = true, developerModeAvailable = false),
            onThemeSelected = {},
            onAuditRetentionSelected = {},
            contentPadding = contentPadding,
        )
    }
}

@Preview(name = "运行外壳", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun RuntimeShellPreview() = ToolBoxTheme {
    RuntimeShellPreviewContent(onBack = {})
}
