package io.toolbox.host

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.core.ui.theme.ToolBoxThemeMode
import io.toolbox.host.background.BackgroundSafeguardsContent
import io.toolbox.host.background.BackgroundTasksContent
import io.toolbox.host.catalog.CatalogUiState
import io.toolbox.host.importflow.ImportUiState
import io.toolbox.host.help.DeveloperHelpScreen
import io.toolbox.host.permissions.PermissionCenterContent
import io.toolbox.host.preview.PreviewHostFixtures
import io.toolbox.host.settings.SettingsContent
import io.toolbox.host.ui.MainDestination
import io.toolbox.host.ui.PrimaryScreen
import io.toolbox.host.ui.RuntimeShellPreviewContent
import io.toolbox.host.ui.ToolDetailScreen
import io.toolbox.host.ui.ToolManagerScreen

private const val CompactPhone = "spec:width=411dp,height=891dp,dpi=420"
private const val MediumDevice = "spec:width=840dp,height=900dp,dpi=320"

@PreviewTest
@Preview(name = "Installed tools light", showBackground = true, device = CompactPhone)
@Composable
fun InstalledToolsLightScreenshot() {
    ToolBoxTheme {
        InstalledToolsPreview()
    }
}

@PreviewTest
@Preview(name = "Installed tools dark", showBackground = true, device = CompactPhone)
@Composable
fun InstalledToolsDarkScreenshot() {
    ToolBoxTheme(mode = ToolBoxThemeMode.Dark) {
        InstalledToolsPreview()
    }
}

@PreviewTest
@Preview(name = "Tool search", showBackground = true, device = CompactPhone)
@Composable
fun ToolSearchScreenshot() {
    ToolBoxTheme {
        ToolManagerScreen(
            state = PreviewHostFixtures.searchCatalog,
            importState = ImportUiState(),
            listState = rememberLazyListState(),
            onAction = {},
            onDestination = {},
            onImport = {},
            onInstallExamples = {},
            onDismissImport = {},
            onOpenDetails = {},
        )
    }
}

@PreviewTest
@Preview(name = "Empty tools large text", showBackground = true, device = CompactPhone, fontScale = 2f)
@Composable
fun EmptyToolsLargeTextScreenshot() {
    ToolBoxTheme {
        ToolManagerScreen(
            state = CatalogUiState(isLoaded = true),
            importState = ImportUiState(),
            listState = rememberLazyListState(),
            onAction = {},
            onDestination = {},
            onImport = {},
            onInstallExamples = {},
            onDismissImport = {},
            onOpenDetails = {},
        )
    }
}

@PreviewTest
@Preview(name = "Tool detail", showBackground = true, device = CompactPhone)
@Composable
fun ToolDetailScreenshot() {
    ToolBoxTheme {
        ToolDetailScreen(
            toolId = PreviewHostFixtures.catalog.tools.first().toolId,
            state = PreviewHostFixtures.catalog,
            onAction = {},
            onBack = {},
            onPermissions = {},
            onBackground = {},
        )
    }
}

@PreviewTest
@Preview(name = "Permission center", showBackground = true, device = CompactPhone)
@Composable
fun PermissionCenterScreenshot() {
    ToolBoxTheme {
        PermissionCenterContent(
            state = PreviewHostFixtures.permissionCenter,
            onBack = {},
            onSetEnabled = { _, _ -> },
            onOpenSystemSettings = {},
        )
    }
}

@PreviewTest
@Preview(name = "Permission center large text", showBackground = true, device = CompactPhone, fontScale = 2f)
@Composable
fun PermissionCenterLargeTextScreenshot() {
    ToolBoxTheme {
        PermissionCenterContent(
            state = PreviewHostFixtures.permissionCenter,
            onBack = {},
            onSetEnabled = { _, _ -> },
            onOpenSystemSettings = {},
        )
    }
}

@PreviewTest
@Preview(name = "Settings", showBackground = true, device = CompactPhone)
@Composable
fun SettingsScreenshot() {
    ToolBoxTheme {
        PrimaryScreen(
            selected = MainDestination.Settings,
            onDestination = {},
            title = "设置",
            subtitle = "外观、后台与开发支持",
            onImport = null,
        ) { contentPadding, _ ->
            SettingsContent(
                state = PreviewHostFixtures.settings,
                contentPadding = contentPadding,
                onThemeSelected = {},
                onBackgroundSafeguards = {},
                onToolPermissions = {},
                onDeveloperHelp = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "Background safeguards", showBackground = true, device = CompactPhone)
@Composable
fun BackgroundSafeguardsScreenshot() {
    ToolBoxTheme {
        BackgroundSafeguardsContent(
            settings = PreviewHostFixtures.settings,
            sessions = PreviewHostFixtures.runtimeSessions,
            systemState = PreviewHostFixtures.backgroundSystemState,
            focusState = PreviewHostFixtures.liveNotificationSupport,
            onBack = {},
            onSetBackgroundEnabled = {},
            onStopSession = {},
            onOpenNotifications = {},
            onOpenBackgroundLocation = {},
            onOpenExactAlarms = {},
            onOpenBatteryOptimization = {},
            onOpenHyperOsAutoStart = {},
            onOpenHyperOsBatteryPolicy = {},
        )
    }
}

@PreviewTest
@Preview(name = "Background tasks", showBackground = true, device = CompactPhone)
@Composable
fun BackgroundTasksScreenshot() {
    ToolBoxTheme {
        BackgroundTasksContent(
            page = PreviewHostFixtures.backgroundTasks,
            message = "后台运行环境与任务状态已同步。",
            cancellingTaskId = null,
            stoppingSessionId = null,
            onBack = {},
            resultFor = { task -> PreviewHostFixtures.taskResults[task.taskId] },
            onStopSession = {},
            onCancelTask = {},
        )
    }
}

@PreviewTest
@Preview(name = "Medium tools", showBackground = true, device = MediumDevice)
@Composable
fun MediumToolsScreenshot() {
    ToolBoxTheme { InstalledToolsPreview() }
}

@PreviewTest
@Preview(name = "Medium settings dark", showBackground = true, device = MediumDevice)
@Composable
fun MediumSettingsDarkScreenshot() {
    ToolBoxTheme(mode = ToolBoxThemeMode.Dark) {
        PrimaryScreen(
            selected = MainDestination.Settings,
            onDestination = {},
            title = "设置",
            subtitle = "外观、后台与开发支持",
            onImport = null,
        ) { contentPadding, _ ->
            SettingsContent(
                state = PreviewHostFixtures.settings,
                contentPadding = contentPadding,
                onThemeSelected = {},
                onBackgroundSafeguards = {},
                onToolPermissions = {},
                onDeveloperHelp = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "Developer help", showBackground = true, device = CompactPhone)
@Composable
fun DeveloperHelpScreenshot() {
    ToolBoxTheme {
        DeveloperHelpScreen(onBack = {}, onInstallExamples = {})
    }
}

@PreviewTest
@Preview(name = "Runtime shell", showBackground = true, device = CompactPhone)
@Composable
fun RuntimeShellScreenshot() {
    ToolBoxTheme {
        RuntimeShellPreviewContent(onBack = {})
    }
}

@PreviewTest
@Preview(name = "Runtime shell dark", showBackground = true, device = CompactPhone)
@Composable
fun RuntimeShellDarkScreenshot() {
    ToolBoxTheme(mode = ToolBoxThemeMode.Dark) {
        RuntimeShellPreviewContent(title = "通知实验室", onBack = {})
    }
}

@Composable
private fun InstalledToolsPreview() {
    ToolManagerScreen(
        state = PreviewHostFixtures.catalog,
        importState = ImportUiState(),
        listState = rememberLazyListState(),
        onAction = {},
        onDestination = {},
        onImport = {},
        onInstallExamples = {},
        onDismissImport = {},
        onOpenDetails = {},
    )
}
