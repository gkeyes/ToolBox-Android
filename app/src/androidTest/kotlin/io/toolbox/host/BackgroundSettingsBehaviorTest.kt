package io.toolbox.host

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import io.toolbox.core.data.HostSettings
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.core.ui.theme.ToolBoxThemeStyle
import io.toolbox.host.background.BackgroundSafeguardsContent
import io.toolbox.host.background.BackgroundSystemState
import io.toolbox.host.background.LiveNotificationSupportState
import io.toolbox.host.catalog.RunningToolsUiState
import io.toolbox.host.settings.BackgroundSettingsOperation
import io.toolbox.host.settings.SettingsContent
import io.toolbox.host.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class BackgroundSettingsBehaviorTest(private val style: ToolBoxThemeStyle) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsAndSafeguardsKeepTheSwitchOffAndExposeOnlyThePendingRetry() {
        val safeguards = mutableStateOf(false)
        val state = mutableStateOf(SettingsUiState(
            settings = HostSettings(backgroundEnabled = false), loaded = true,
            backgroundOperation = BackgroundSettingsOperation.STOP,
            backgroundError = "后台运行已关闭，但部分任务或运行环境未能停止。请重试停止。",
        ))
        var retries = 0
        var toggles = 0
        val retry = { retries++; state.value = state.value.copy(backgroundWorking = true) }
        compose.activity.setContent {
            ToolBoxTheme(style = style, reduceTransparency = true) {
                if (safeguards.value) {
                    BackgroundSafeguardsContent(
                        settings = state.value, runningState = RunningToolsUiState(),
                        systemState = BackgroundSystemState(false, false, false, false),
                        focusState = LiveNotificationSupportState(0, false, false, false, false),
                        onBack = {}, onSetBackgroundEnabled = { toggles++ },
                        onStopSession = {}, onStopAll = {}, onCancelStop = {}, onConfirmStop = {},
                        onDismissFeedback = {}, onOpenNotifications = {}, onOpenBackgroundLocation = {},
                        onOpenExactAlarms = {}, onOpenBatteryOptimization = {},
                        onOpenHyperOsAutoStart = {}, onOpenHyperOsBatteryPolicy = {},
                        onRetryBackground = retry,
                    )
                } else {
                    SettingsContent(
                        state = state.value, contentPadding = PaddingValues(16.dp),
                        onAppearance = {}, onBackgroundSafeguards = {}, onToolPermissions = {}, onDeveloperHelp = {},
                        onBackgroundEnabledChange = { toggles++ }, onRetryBackground = retry,
                    )
                }
            }
        }

        for (showSafeguards in listOf(false, true)) {
            compose.runOnIdle { safeguards.value = showSafeguards; state.value = state.value.copy(backgroundWorking = false) }
            val toggleTag = if (showSafeguards) "safeguards_background_enabled" else "settings_background_enabled"
            compose.onNodeWithTag(toggleTag).performScrollTo().assertIsOff().assertIsNotEnabled()
            compose.onNodeWithText("后台运行已关闭，但部分任务或运行环境未能停止。请重试停止。")
                .performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            compose.onNodeWithText("重试停止").performScrollTo().performClick().assertIsNotEnabled()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "正在停止后台运行…"))
            compose.onNodeWithText("后台运行已关闭，但部分任务或运行环境未能停止。请重试停止。").assertExists()
            compose.runOnIdle { assertEquals(0, toggles) }
        }
        compose.runOnIdle { assertEquals(2, retries) }

        // A failed write uses the same action in both destinations, without pretending the switch changed.
        for (showSafeguards in listOf(false, true)) {
            compose.runOnIdle {
                safeguards.value = showSafeguards
                state.value = state.value.copy(
                    settings = HostSettings(backgroundEnabled = true), backgroundWorking = false,
                    backgroundOperation = BackgroundSettingsOperation.SAVE, backgroundError = "后台设置未保存，请重试保存。",
                )
            }
            val toggleTag = if (showSafeguards) "safeguards_background_enabled" else "settings_background_enabled"
            compose.onNodeWithTag(toggleTag).performScrollTo().assertIsOn().assertIsNotEnabled()
            compose.onNodeWithText("重试保存").performScrollTo().performClick().assertIsNotEnabled()
        }
        compose.runOnIdle { assertEquals(4, retries); assertEquals(0, toggles) }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun styles() = ToolBoxThemeStyle.entries.map { arrayOf(it) }
    }
}
