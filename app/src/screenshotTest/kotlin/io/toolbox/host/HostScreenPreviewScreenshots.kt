package io.toolbox.host

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.host.ui.HomeScreen
import io.toolbox.host.ui.ImportReviewScreen
import io.toolbox.host.ui.PermissionCenterScreen
import io.toolbox.host.ui.RuntimeShellScreen
import io.toolbox.host.ui.SettingsScreen
import io.toolbox.host.ui.ToolManagerScreen

@PreviewTest
@Preview(
    name = "Home compact phone",
    showBackground = true,
    device = "spec:width=411dp,height=891dp,dpi=420",
)
@Composable
fun HomeCompactScreenshot() {
    ToolBoxTheme {
        HomeScreen(
            onDestination = {},
            onImport = {},
            onLaunchTool = {},
        )
    }
}

@PreviewTest
@Preview(
    name = "Tool manager compact phone",
    showBackground = true,
    device = "spec:width=411dp,height=891dp,dpi=420",
)
@Composable
fun ToolManagerCompactScreenshot() {
    ToolBoxTheme {
        ToolManagerScreen(
            onDestination = {},
            onImport = {},
            onLaunchTool = {},
        )
    }
}

@PreviewTest
@Preview(
    name = "Import review compact phone",
    showBackground = true,
    device = "spec:width=411dp,height=891dp,dpi=420",
)
@Composable
fun ImportReviewCompactScreenshot() {
    ToolBoxTheme {
        ImportReviewScreen(onBack = {})
    }
}

@PreviewTest
@Preview(
    name = "Permission center compact phone",
    showBackground = true,
    device = "spec:width=411dp,height=891dp,dpi=420",
)
@Composable
fun PermissionCenterCompactScreenshot() {
    ToolBoxTheme {
        PermissionCenterScreen(onBack = {})
    }
}

@PreviewTest
@Preview(
    name = "Settings compact phone",
    showBackground = true,
    device = "spec:width=411dp,height=891dp,dpi=420",
)
@Composable
fun SettingsCompactScreenshot() {
    ToolBoxTheme {
        SettingsScreen(onDestination = {}, onPermissionCenter = {})
    }
}

@PreviewTest
@Preview(
    name = "Runtime shell medium window",
    showBackground = true,
    device = "spec:width=700dp,height=1024dp,dpi=240",
)
@Composable
fun RuntimeShellMediumScreenshot() {
    ToolBoxTheme {
        RuntimeShellScreen(onBack = {}, onPermissionCenter = {})
    }
}
