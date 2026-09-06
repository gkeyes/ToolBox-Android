package io.toolbox.host

import android.content.res.Configuration
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import io.toolbox.core.data.ThemeMode
import io.toolbox.core.data.ThemeStyle
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.core.ui.theme.ToolBoxThemeMode
import io.toolbox.core.ui.theme.ToolBoxThemeStyle
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.catalog.CatalogAction
import io.toolbox.host.catalog.CatalogTool
import io.toolbox.host.catalog.CatalogUiState
import io.toolbox.host.importflow.ImportUiState
import io.toolbox.host.settings.SettingsContent
import io.toolbox.host.settings.AppearanceContent
import io.toolbox.host.settings.SettingsUiState
import io.toolbox.host.ui.ToolManagerScreen
import io.toolbox.host.ui.DetailScreen
import io.toolbox.host.ui.HostTestTags
import io.toolbox.host.ui.mergePadding
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
                            onAppearance = { destinations += "appearance" },
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
            val appearanceRow = composeRule.onNodeWithTag(HostTestTags.SettingsAppearance).performScrollTo()
            appearanceRow.assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
            composeRule.onNodeWithText("Liquid Glass · ${if (preference == ThemeMode.SYSTEM) "跟随系统" else "深色"}")
                .assertIsDisplayed()
            appearanceRow.performTouchInput { click(center) }

            // External failure state must remain visible and must not disable retry/other settings.
            composeRule.runOnIdle { settings.value = settings.value.copy(error = error) }
            composeRule.onNodeWithText(error).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag(HostTestTags.SettingsAppearance).performScrollTo().assertHasClickAction()
            listOf("后台保障", "工具权限", "开发帮助").forEach { label ->
                val target = composeRule.onNodeWithText(label).performScrollTo()
                target.assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
                target.performTouchInput { click(center) }
            }
            composeRule.onNodeWithText("关于 ToolBox").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("${BuildConfig.VERSION_NAME} · API 1.0").assertIsDisplayed()
            composeRule.runOnIdle {
                assertEquals(
                    List(index + 1) { listOf("appearance", "background", "permissions", "help") }.flatten(),
                    destinations,
                )
            }
        }
    }

    @Test
    fun appearanceChoicesRemainInteractiveAtTwoHundredPercentFontScale() {
        val state = mutableStateOf(SettingsUiState(loaded = true))
        val styles = mutableListOf<ThemeStyle>()
        val modes = mutableListOf<ThemeMode>()
        val transparency = mutableListOf<Boolean>()
        var retries = 0
        composeRule.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ToolBoxTheme(style = ToolBoxThemeStyle.LiquidGlass) {
                    Box(Modifier.width(360.dp)) {
                        DetailScreen(title = "外观", onBack = {}) { chromePadding ->
                            AppearanceContent(
                                state = state.value,
                                onThemeStyleSelected = { styles += it },
                                onThemeModeSelected = { modes += it },
                                onReduceTransparencyChanged = { transparency += it },
                                onRetry = { retries += 1 },
                                contentPadding = mergePadding(
                                    chromePadding,
                                    PaddingValues(
                                        horizontal = ToolBoxThemeTokens.spacing.two,
                                        vertical = ToolBoxThemeTokens.spacing.oneHalf,
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithTag(HostTestTags.AppearanceMiuix)
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(listOf(ThemeStyle.MIUIX), styles) }

        composeRule.onNodeWithTag(HostTestTags.AppearanceMode)
            .performScrollTo()
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        val darkChoice = composeRule.onNodeWithText("深色")
        composeRule.waitUntil(5_000) { darkChoice.isDisplayed() }
        darkChoice.performClick()
        composeRule.onNodeWithTag(HostTestTags.AppearanceSystemColor)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag(HostTestTags.AppearanceReduceTransparency)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle {
            assertEquals(listOf(ThemeMode.DARK, ThemeMode.MONET_SYSTEM), modes)
            assertEquals(listOf(true), transparency)
        }

        composeRule.runOnIdle {
            state.value = state.value.copy(
                settings = state.value.settings.copy(themeStyle = ThemeStyle.MIUIX),
                error = "设置未保存，请重试。",
                canRetry = true,
            )
        }
        composeRule.onNodeWithText("降低透明度").assertDoesNotExist()
        composeRule.onNodeWithText("重试").performScrollTo().assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun themeSwitchKeepsEmbeddedRuntimeSurfaceIdentity() {
        val style = mutableStateOf(ToolBoxThemeStyle.LiquidGlass)
        var created = 0
        var firstView: View? = null
        var currentView: View? = null
        composeRule.activity.setContent {
            ToolBoxTheme(style = style.value) {
                AndroidView(
                    factory = { context ->
                        View(context).also {
                            created += 1
                            firstView = it
                            currentView = it
                        }
                    },
                    update = { currentView = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule.runOnIdle {
            assertEquals(1, created)
            style.value = ToolBoxThemeStyle.Miuix
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(1, created)
            assertSame(firstView, currentView)
            style.value = ToolBoxThemeStyle.LiquidGlass
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(1, created)
            assertSame(firstView, currentView)
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
