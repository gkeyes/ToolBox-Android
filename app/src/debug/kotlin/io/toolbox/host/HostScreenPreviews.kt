package io.toolbox.host

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.core.ui.theme.ToolBoxThemeMode
import io.toolbox.host.background.BackgroundSafeguardsContent
import io.toolbox.host.background.BackgroundTasksContent
import io.toolbox.host.catalog.CatalogUiState
import io.toolbox.host.importflow.ImportUiState
import io.toolbox.host.help.DeveloperHelpPage
import io.toolbox.host.help.HelpLoadState
import io.toolbox.host.help.parseHelpDocument
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

// IDE-only previews; screenshot tests and their Gradle plugin were removed by request.
@Preview(name = "Installed tools light", showBackground = true, device = CompactPhone)
@Composable
fun InstalledToolsLightPreview() {
    ToolBoxTheme {
        InstalledToolsPreview()
    }
}

@Preview(name = "Installed tools dark", showBackground = true, device = CompactPhone)
@Composable
fun InstalledToolsDarkPreview() {
    ToolBoxTheme(mode = ToolBoxThemeMode.Dark) {
        InstalledToolsPreview()
    }
}

@Preview(
    name = "Installed tools narrow large text",
    showBackground = true,
    device = "spec:width=360dp,height=800dp,dpi=320",
    fontScale = 2f,
)
@Composable
fun InstalledToolsNarrowLargeTextPreview() {
    val tools = PreviewHostFixtures.catalog.tools.mapIndexed { index, tool ->
        if (index == 0) tool.copy(name = "用于大字体换行验证的工具") else tool
    }
    ToolBoxTheme {
        ToolManagerScreen(
            state = CatalogUiState(isLoaded = true, tools = tools, visibleTools = tools),
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

@Preview(name = "Tool search", showBackground = true, device = CompactPhone)
@Composable
fun ToolSearchPreview() {
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

@Preview(name = "Empty tools large text", showBackground = true, device = CompactPhone, fontScale = 2f)
@Composable
fun EmptyToolsLargeTextPreview() {
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

@Preview(name = "Tool detail", showBackground = true, device = CompactPhone)
@Composable
fun ToolDetailPreview() {
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

@Preview(name = "Permission center", showBackground = true, device = CompactPhone)
@Composable
fun PermissionCenterPreview() {
    ToolBoxTheme {
        PermissionCenterContent(
            state = PreviewHostFixtures.permissionCenter,
            onBack = {},
            onSetEnabled = { _, _ -> },
            onOpenSystemSettings = {},
        )
    }
}

@Preview(name = "Permission center large text", showBackground = true, device = CompactPhone, fontScale = 2f)
@Composable
fun PermissionCenterLargeTextPreview() {
    ToolBoxTheme {
        PermissionCenterContent(
            state = PreviewHostFixtures.permissionCenter,
            onBack = {},
            onSetEnabled = { _, _ -> },
            onOpenSystemSettings = {},
        )
    }
}

@Preview(name = "Settings", showBackground = true, device = CompactPhone)
@Composable
fun SettingsPreview() {
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

@Preview(name = "Background safeguards", showBackground = true, device = CompactPhone)
@Composable
fun BackgroundSafeguardsPreview() {
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

@Preview(name = "Background tasks", showBackground = true, device = CompactPhone)
@Composable
fun BackgroundTasksPreview() {
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

@Preview(name = "Medium tools", showBackground = true, device = MediumDevice)
@Composable
fun MediumToolsPreview() {
    ToolBoxTheme { InstalledToolsPreview() }
}

@Preview(name = "Medium settings dark", showBackground = true, device = MediumDevice)
@Composable
fun MediumSettingsDarkPreview() {
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

@Preview(name = "Developer help", showBackground = true, device = CompactPhone)
@Composable
fun DeveloperHelpLightPreview() {
    ToolBoxTheme {
        DeveloperHelpPreview()
    }
}

@Preview(name = "Developer help dark", showBackground = true, device = CompactPhone)
@Composable
fun DeveloperHelpDarkPreview() {
    ToolBoxTheme(mode = ToolBoxThemeMode.Dark) {
        DeveloperHelpPreview()
    }
}

@Preview(name = "Developer help large text", showBackground = true, device = CompactPhone, fontScale = 2f)
@Composable
fun DeveloperHelpLargeTextPreview() {
    ToolBoxTheme {
        DeveloperHelpPreview()
    }
}

@Composable
private fun DeveloperHelpPreview() {
    val context = LocalContext.current
    val document = remember(context) {
        context.assets.open("manual.md").bufferedReader(Charsets.UTF_8).use {
            parseHelpDocument(it.readText())
        }
    }
    DeveloperHelpPage(
        state = HelpLoadState.Loaded(document),
        onBack = {},
        onInstallExamples = {},
        onRetry = {},
    )
}

@Preview(name = "Runtime shell", showBackground = true, device = CompactPhone)
@Composable
fun RuntimeShellPreview() {
    ToolBoxTheme {
        RuntimeShellPreviewContent(onBack = {})
    }
}

@Preview(name = "Runtime shell dark", showBackground = true, device = CompactPhone)
@Composable
fun RuntimeShellDarkPreview() {
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
