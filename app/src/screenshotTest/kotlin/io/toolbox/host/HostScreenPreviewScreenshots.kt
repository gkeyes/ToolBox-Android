package io.toolbox.host

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.host.catalog.CatalogUiState
import io.toolbox.host.importflow.ImportReviewScreen
import io.toolbox.host.permissions.PermissionCenterScreen
import io.toolbox.host.preview.PreviewHostFixtures
import io.toolbox.host.settings.SettingsScreen
import io.toolbox.host.settings.SettingsUiState
import io.toolbox.host.ui.HomeScreen
import io.toolbox.host.ui.MainDestination
import io.toolbox.host.ui.PrimaryScreen
import io.toolbox.host.ui.ToolManagerScreen

@PreviewTest
@Preview(
    name = "Catalog fixture compact phone",
    showBackground = true,
    device = "spec:width=411dp,height=891dp,dpi=420",
)
@Composable
fun CatalogFixtureCompactScreenshot() {
    ToolBoxTheme {
        HomeScreen(
            state = PreviewHostFixtures.catalog,
            onAction = {},
            onDestination = {},
            onImport = {},
            onOpenDetails = {},
        )
    }
}

@PreviewTest
@Preview(
    name = "Fresh catalog large text",
    showBackground = true,
    device = "spec:width=411dp,height=891dp,dpi=420",
    fontScale = 2f,
)
@Composable
fun FreshCatalogLargeTextScreenshot() {
    ToolBoxTheme {
        HomeScreen(
            state = CatalogUiState(isLoaded = true),
            onAction = {},
            onDestination = {},
            onImport = {},
            onOpenDetails = {},
        )
    }
}

@PreviewTest
@Preview(
    name = "Tool manager fixture compact phone",
    showBackground = true,
    device = "spec:width=411dp,height=891dp,dpi=420",
)
@Composable
fun ToolManagerFixtureCompactScreenshot() {
    ToolBoxTheme {
        ToolManagerScreen(
            state = PreviewHostFixtures.catalog,
            onAction = {},
            onDestination = {},
            onImport = {},
            onOpenDetails = {},
        )
    }
}

@PreviewTest
@Preview(
    name = "Import review fixture compact phone",
    showBackground = true,
    device = "spec:width=411dp,height=891dp,dpi=420",
)
@Composable
fun ImportReviewFixtureCompactScreenshot() {
    ToolBoxTheme {
        ImportReviewScreen(
            state = PreviewHostFixtures.importReview,
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
}

@PreviewTest
@Preview(
    name = "Permission center fixture compact phone",
    showBackground = true,
    device = "spec:width=411dp,height=891dp,dpi=420",
)
@Composable
fun PermissionCenterFixtureCompactScreenshot() {
    ToolBoxTheme {
        PermissionCenterScreen(
            state = PreviewHostFixtures.permissionCenter,
            onBack = {},
            onRevoke = {},
            onExplainRuntimeGranting = {},
            onDismissFeedback = {},
        )
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
        PrimaryScreen(
            selected = MainDestination.Settings,
            onDestination = {},
            title = "设置",
            onImport = null,
        ) { contentPadding ->
            SettingsScreen(
                state = SettingsUiState(isLoaded = true, developerModeAvailable = false),
                onThemeSelected = {},
                onSecurityPolicySelected = {},
                onAuditRetentionSelected = {},
                onDefaultStorageQuotaSelected = {},
                onDeveloperModeChanged = {},
                contentPadding = contentPadding,
            )
        }
    }
}
