package io.toolbox.host

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.host.ui.HomeScreen
import io.toolbox.host.ui.ImportReviewScreen
import io.toolbox.host.ui.PermissionCenterScreen
import io.toolbox.host.ui.ProductionHostState
import io.toolbox.host.ui.RuntimeShellScreen
import io.toolbox.host.ui.SettingsScreen
import io.toolbox.host.ui.ToolManagerScreen
import io.toolbox.host.preview.PreviewHostFixtures

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
            model = PreviewHostFixtures.home,
            onDestination = {},
            onImport = {},
            onLaunchTool = {},
        )
    }
}

@PreviewTest
@Preview(
    name = "Home fresh install compact phone",
    showBackground = true,
    device = "spec:width=411dp,height=891dp,dpi=420",
)
@Composable
fun HomeFreshInstallCompactScreenshot() {
    ToolBoxTheme {
        HomeScreen(
            model = ProductionHostState.freshInstall().home,
            onDestination = {},
            onImport = {},
            onLaunchTool = {},
        )
    }
}

@PreviewTest
@Preview(
    name = "Home fresh install large text",
    showBackground = true,
    device = "spec:width=411dp,height=891dp,dpi=420",
    fontScale = 2f,
)
@Composable
fun HomeFreshInstallLargeTextScreenshot() {
    ToolBoxTheme {
        HomeScreen(
            model = ProductionHostState.freshInstall().home,
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
            model = PreviewHostFixtures.toolManager,
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
        SettingsScreen(onDestination = {})
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
        RuntimeShellScreen(onBack = {})
    }
}
