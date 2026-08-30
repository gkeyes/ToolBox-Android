package io.toolbox.host

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.core.ui.theme.ToolBoxThemeMode
import io.toolbox.host.catalog.CatalogUiState
import io.toolbox.host.importflow.ImportUiState
import io.toolbox.host.permissions.PermissionCenterContent
import io.toolbox.host.preview.PreviewHostFixtures
import io.toolbox.host.settings.SettingsContent
import io.toolbox.host.ui.MainDestination
import io.toolbox.host.ui.PrimaryScreen
import io.toolbox.host.ui.RuntimeShellPreviewContent
import io.toolbox.host.ui.ToolDetailScreen
import io.toolbox.host.ui.ToolManagerScreen

private const val CompactPhone = "spec:width=411dp,height=891dp,dpi=420"

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
@Preview(name = "Settings", showBackground = true, device = CompactPhone)
@Composable
fun SettingsScreenshot() {
    ToolBoxTheme {
        PrimaryScreen(
            selected = MainDestination.Settings,
            onDestination = {},
            title = "设置",
            onImport = null,
        ) { contentPadding ->
            SettingsContent(
                state = PreviewHostFixtures.settings,
                contentPadding = contentPadding,
                onThemeSelected = {},
                onBackgroundEnabled = {},
                onToolPermissions = {},
                onDeveloperHelp = {},
            )
        }
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
