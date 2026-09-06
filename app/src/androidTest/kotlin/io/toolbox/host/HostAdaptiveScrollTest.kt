package io.toolbox.host

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.toolbox.core.data.ThemeMode
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.core.ui.theme.ToolBoxThemeMode
import io.toolbox.host.catalog.CatalogAction
import io.toolbox.host.catalog.CatalogTool
import io.toolbox.host.catalog.CatalogUiState
import io.toolbox.host.importflow.ImportUiState
import io.toolbox.host.settings.SettingsContent
import io.toolbox.host.settings.SettingsUiState
import io.toolbox.host.settings.label
import io.toolbox.host.ui.ToolManagerScreen
import io.toolbox.host.ui.HostTestTags
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HostAdaptiveScrollTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun systemBarsFollowAllThemeModesAndLiveSystemConfiguration() {
        val theme = mutableStateOf(ToolBoxThemeMode.System)
        val systemDark = mutableStateOf(false)
        composeRule.activity.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (systemDark.value) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                composeRule.activity.ApplySystemBarAppearance(theme.value)
            }
        }
        for (mode in ToolBoxThemeMode.entries) {
            for (dark in listOf(false, true)) {
                composeRule.runOnIdle {
                    theme.value = mode
                    systemDark.value = dark
                }
                composeRule.waitForIdle()
                composeRule.runOnIdle {
                    val expectedLightIcons = when (mode) {
                        ToolBoxThemeMode.Dark, ToolBoxThemeMode.MonetDark -> true
                        ToolBoxThemeMode.Light, ToolBoxThemeMode.MonetLight -> false
                        ToolBoxThemeMode.System, ToolBoxThemeMode.MonetSystem -> dark
                    }
                    val window = composeRule.activity.window
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    assertEquals("status: $mode / systemDark=$dark", !expectedLightIcons, controller.isAppearanceLightStatusBars)
                    assertEquals("navigation: $mode / systemDark=$dark", !expectedLightIcons, controller.isAppearanceLightNavigationBars)
                }
            }
        }
    }

    @Test
    fun openAndManageRemainSeparateTouchTargetsOnNarrowLargeTextScreens() {
        val tool = CatalogTool(
            toolId = "io.toolbox.test.longname",
            name = "用于大字体换行验证的工具",
            versionCode = 1,
            versionName = "1.0.0",
            bundleBytes = 1024L,
            lastOpenedAt = null,
        )
        val actions = mutableListOf<CatalogAction>()
        val details = mutableListOf<String>()
        val theme = mutableStateOf(ToolBoxThemeMode.Light)
        composeRule.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ToolBoxTheme(mode = theme.value) {
                    Box(Modifier.width(360.dp)) {
                        ToolManagerScreen(
                            state = CatalogUiState(isLoaded = true, tools = listOf(tool), visibleTools = listOf(tool)),
                            importState = ImportUiState(),
                            listState = rememberLazyListState(),
                            onAction = { actions += it },
                            onDestination = {},
                            onImport = {},
                            onInstallExamples = {},
                            onDismissImport = {},
                            onOpenDetails = { details += it },
                        )
                    }
                }
            }
        }

        listOf(ToolBoxThemeMode.Light, ToolBoxThemeMode.Dark).forEachIndexed { index, mode ->
            composeRule.runOnIdle { theme.value = mode }
            val openTarget = composeRule.onNodeWithText(tool.name).performScrollTo()
            val manage = composeRule.onNodeWithContentDescription("管理${tool.name}")
            listOf(openTarget, manage).forEach { node ->
                node.assertIsDisplayed().assertHasClickAction()
                    .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            }
            assertFalse(
                "Opening and managing must not share a touch region",
                openTarget.fetchSemanticsNode().boundsInRoot.overlaps(manage.fetchSemanticsNode().boundsInRoot),
            )
            // Inject real pointer taps, not just semantic callbacks, to detect bubbling/double triggers.
            manage.performTouchInput { click(center) }
            composeRule.runOnIdle {
                assertEquals(List(index + 1) { tool.toolId }, details)
                assertEquals(List(index) { CatalogAction.RequestRuntimeLaunch(tool.toolId) }, actions)
            }
            openTarget.performTouchInput { click(center) }
            composeRule.runOnIdle {
                assertEquals(List(index + 1) { tool.toolId }, details)
                assertEquals(List(index + 1) { CatalogAction.RequestRuntimeLaunch(tool.toolId) }, actions)
            }
        }
    }

    @Test
    fun settingsChoicesErrorsAndDestinationsRemainReachableOnNarrowLargeTextScreens() {
        val mode = mutableStateOf(ToolBoxThemeMode.Light)
        val settings = mutableStateOf(SettingsUiState(loaded = true))
        val choices = mutableListOf<String>()
        val destinations = mutableListOf<String>()
        val error = "设置未保存，请重试。"
        composeRule.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ToolBoxTheme(mode = mode.value) {
                    Box(Modifier.width(360.dp)) {
                        SettingsContent(
                            state = settings.value,
                            contentPadding = PaddingValues(16.dp),
                            onThemeSelected = { choices += it },
                            onBackgroundSafeguards = { destinations += "background" },
                            onToolPermissions = { destinations += "permissions" },
                            onDeveloperHelp = { destinations += "help" },
                        )
                    }
                }
            }
        }

        listOf(ToolBoxThemeMode.Light, ToolBoxThemeMode.Dark).forEachIndexed { index, theme ->
            val preference = if (index == 0) ThemeMode.SYSTEM else ThemeMode.MONET_DARK
            composeRule.runOnIdle {
                mode.value = theme
                settings.value = settings.value.copy(
                    settings = settings.value.settings.copy(theme = preference),
                    error = null,
                )
            }
            val themeRow = composeRule.onNodeWithText("主题").performScrollTo()
            themeRow.assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
            val title = composeRule.onNodeWithText("主题", useUnmergedTree = true)
            val currentValue = composeRule.onNodeWithText(preference.label, useUnmergedTree = true)
            currentValue.assertIsDisplayed()
            assertFalse(title.fetchSemanticsNode().boundsInRoot.overlaps(currentValue.fetchSemanticsNode().boundsInRoot))
            themeRow.performTouchInput { click(center) }
            composeRule.onNodeWithText("主题只影响 ToolBox 宿主界面，不改变工具内部页面。").assertIsDisplayed()
            composeRule.onNodeWithText(ThemeMode.DARK.label).assertIsDisplayed().performClick()
            composeRule.runOnIdle { assertEquals(List(index + 1) { ThemeMode.DARK.name }, choices) }

            // External failure state must remain visible and must not disable retry/other settings.
            composeRule.runOnIdle { settings.value = settings.value.copy(error = error) }
            composeRule.onNodeWithText(error).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("主题").performScrollTo().assertHasClickAction()
            listOf("后台保障", "工具权限", "开发帮助").forEach { label ->
                val target = composeRule.onNodeWithText(label).performScrollTo()
                target.assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
                target.performTouchInput { click(center) }
            }
            composeRule.onNodeWithText("关于 ToolBox").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("${BuildConfig.VERSION_NAME} · API 1.0").assertIsDisplayed()
            composeRule.runOnIdle {
                assertEquals(List(index + 1) { listOf("background", "permissions", "help") }.flatten(), destinations)
            }
        }
    }

    @Test
    fun freshInstallRemainsReachableAtTwoHundredPercentFontScale() {
        composeRule.activity.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale = 2f)) {
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
        }

        composeRule.onNodeWithTag(HostTestTags.CatalogEmptyState).assertIsDisplayed()
        composeRule.onNodeWithText("导入 .tbx").assertIsDisplayed()

        val minimumTouchTargetPx = with(composeRule.density) { 48.dp.toPx() }
        val navigationItemHeightPx = with(composeRule.density) { 64.dp.toPx() }
        composeRule.onNodeWithTag(HostTestTags.BottomNavigationContainer).assertIsDisplayed()
        listOf(
            HostTestTags.BottomTools to "工具",
            HostTestTags.BottomSettings to "设置",
        ).forEach { (tag, label) ->
            composeRule.onNodeWithContentDescription(label).assertIsDisplayed()
            val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$label width must be at least 48dp", bounds.width >= minimumTouchTargetPx)
            assertTrue("$label height must be at least 48dp", bounds.height >= minimumTouchTargetPx)
            assertTrue(
                "$label content height must remain 64dp",
                abs(bounds.height - navigationItemHeightPx) <= 1f,
            )
        }
    }
}
