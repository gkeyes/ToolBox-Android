package io.toolbox.host

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.core.ui.theme.ToolBoxThemeMode
import io.toolbox.host.catalog.CatalogTool
import io.toolbox.host.catalog.RunningToolsUiState
import io.toolbox.host.importflow.ImportUiState
import io.toolbox.host.preview.PreviewHostFixtures
import io.toolbox.host.ui.CatalogRunningToolsContent
import io.toolbox.host.ui.ToolManagerScreen

@Preview(name = "Running tools light", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "Running tools large text", device = "spec:width=411dp,height=891dp,dpi=420", fontScale = 2f)
@Preview(name = "Running tools medium", device = "spec:width=840dp,height=900dp,dpi=320")
@Composable
fun RunningToolsLightPreview() {
    ToolBoxTheme { RunningHomePreview() }
}

@Preview(name = "Running tools dark", device = "spec:width=411dp,height=891dp,dpi=420")
@Composable
fun RunningToolsDarkPreview() {
    ToolBoxTheme(mode = ToolBoxThemeMode.Dark) { RunningHomePreview() }
}

@Preview(name = "Stop running tool", device = "spec:width=411dp,height=891dp,dpi=420")
@Composable
fun StopRunningToolPreview() {
    ToolBoxTheme { RunningHomePreview(confirmStop = true) }
}

@Composable
private fun RunningHomePreview(confirmStop: Boolean = false) {
    val watcher = CatalogTool(
        toolId = "io.toolbox.githubactionswatcher",
        name = "GitHub 构建守望",
        versionCode = 5,
        versionName = "1.0.4",
        bundleBytes = 50_132L,
        lastOpenedAt = 1_788_246_000_001L,
    )
    val installed = listOf(watcher) + PreviewHostFixtures.catalog.tools
    val catalog = PreviewHostFixtures.catalog.copy(
        tools = installed,
        visibleTools = installed,
        recentTools = listOf(watcher, PreviewHostFixtures.catalog.recentTools.first()),
    )
    val sessions = PreviewHostFixtures.runtimeSessions.let { list ->
        listOf(
            list.first().copy(
                sessionId = "preview-watcher",
                toolId = "io.toolbox.githubactionswatcher",
                toolName = "GitHub 构建守望",
                notificationId = 0x550001,
            ),
            list.first(),
        )
    }
    ToolManagerScreen(
        state = catalog,
        importState = ImportUiState(),
        listState = rememberLazyListState(),
        onAction = {},
        onDestination = {},
        onImport = {},
        onInstallExamples = {},
        onDismissImport = {},
        onOpenDetails = {},
        runningTools = {
            CatalogRunningToolsContent(
                state = RunningToolsUiState(
                    sessions = sessions,
                    confirmation = sessions.first().takeIf { confirmStop },
                ),
                tools = catalog.tools,
                onOpen = {},
                onRequestStop = {},
                onCancelStop = {},
                onConfirmStop = {},
                onDismissFeedback = {},
            )
        },
    )
}
