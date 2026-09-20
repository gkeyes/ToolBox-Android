package io.toolbox.host

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.toolbox.core.data.*
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.core.ui.theme.ToolBoxThemeStyle
import io.toolbox.host.background.BackgroundTasksContent
import io.toolbox.host.background.BackgroundTasksPageModel
import io.toolbox.host.backup.*
import io.toolbox.host.help.*
import io.toolbox.host.runtime.RuntimeBackgroundSessionUi
import io.toolbox.host.settings.AboutScreen
import io.toolbox.host.settings.AppearanceContent
import io.toolbox.host.settings.SettingsUiState
import io.toolbox.host.ui.HostTestTags
import io.toolbox.tool.packagekit.backup.BackupContents
import io.toolbox.tool.packagekit.backup.PreparedBackup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(Parameterized::class)
class SecondaryPageBehaviorTest(private val style: ToolBoxThemeStyle) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun backgroundActionsAdaptAndKeepTheirOwnTargetsAndBusyStates() {
        val width = mutableStateOf(700.dp)
        val fontScale = mutableFloatStateOf(1f)
        val stopping = mutableStateOf<String?>(null)
        val cancelling = mutableStateOf<String?>(null)
        val stopped = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val session = RuntimeBackgroundSessionUi("session", "tool", "工具", 0L, 1)
        val task = BackgroundTask("task", "tool", 1, "同步任务", BackgroundOperation.HTTP_GET, "{}", false,
            null, TaskState.RUNNING, 0L, 0L, null, 0)
        var pixelsPerDp = 0f
        compose.activity.setContent {
            val density = LocalDensity.current
            val testDensity = Density(density.density * 0.5f, fontScale.floatValue)
            CompositionLocalProvider(LocalDensity provides testDensity) {
                SideEffect { pixelsPerDp = testDensity.density }
                ToolBoxTheme(style = style, reduceTransparency = true) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.width(width.value).fillMaxHeight()) {
                            BackgroundTasksContent(
                                BackgroundTasksPageModel(listOf(task), listOf(session)), null,
                                cancelling.value, stopping.value, {}, { null },
                                { stopped += it.sessionId; stopping.value = it.sessionId },
                                { cancelled += it.taskId; cancelling.value = it.taskId },
                            )
                        }
                    }
                }
            }
        }
        val stop = compose.onNodeWithTag("background_stop:session")
        val title = compose.onNodeWithText("持续运行环境")
        val row = compose.onNodeWithTag("background_session:session")
        stop.assertIsDisplayed()
        var actionBounds = stop.fetchSemanticsNode().boundsInRoot
        var titleBounds = title.fetchSemanticsNode().boundsInRoot
        assertTrue("The ordinary wide row keeps its action beside its information", actionBounds.left >= titleBounds.right)
        assertTrue(actionBounds.top < titleBounds.bottom && actionBounds.bottom > titleBounds.top)
        assertTrue(actionBounds.width < row.fetchSemanticsNode().boundsInRoot.width / 2)
        assertTrue(actionBounds.height >= 48f * pixelsPerDp - 1f)

        compose.runOnIdle { width.value = 320.dp }
        actionBounds = stop.fetchSemanticsNode().boundsInRoot
        titleBounds = title.fetchSemanticsNode().boundsInRoot
        assertTrue("A narrow row moves the action below its information", actionBounds.top >= titleBounds.bottom)
        assertTrue(actionBounds.width < row.fetchSemanticsNode().boundsInRoot.width)
        compose.runOnIdle { width.value = 700.dp; fontScale.floatValue = 1.6f }
        actionBounds = stop.fetchSemanticsNode().boundsInRoot
        titleBounds = title.fetchSemanticsNode().boundsInRoot
        assertTrue("Large text uses a separate action line even when the page is wide", actionBounds.top >= titleBounds.bottom)
        stop.performClick().assertIsNotEnabled()
        val cancel = compose.onNodeWithTag("background_cancel:task").performScrollTo()
        cancel.performClick().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf("session"), stopped)
            assertEquals(listOf("task"), cancelled)
        }
    }

    @Test fun backupStatesLeadWithCurrentTaskAndRetainRestoreWarningsAndActions() {
        val state = mutableStateOf<BackupUiState>(BackupUiState.Idle)
        var exports = 0
        var restores = 0
        var consents = 0
        var confirmations = 0
        var cancellations = 0
        compose.activity.setContent {
            ToolBoxTheme(style = style, reduceTransparency = true) {
                BackupContent(state.value, {}, { exports++ }, { restores++ }, { consents++ }, { confirmations++ }, { cancellations++ })
            }
        }
        compose.onNodeWithText("备份内容").assertExists()
        compose.onNodeWithTag("backup_page").performScrollToKey("actions")
        compose.onNodeWithTag("backup_export").performScrollTo().performClick()
        compose.onNodeWithTag("backup_restore").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, exports); assertEquals(1, restores); state.value = BackupUiState.ExportConsent }
        compose.onNodeWithText("备份内容").assertDoesNotExist()
        compose.onNodeWithText("ZIP 不加密，ToolBox 不会上传它。请只保存到可信的本地位置，不要公开分享。硬件密钥不导出；可解密数据将写入备份，并在恢复时重新加密。").assertExists()
        compose.onNodeWithTag("backup_export_consent").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, consents); state.value = BackupUiState.Progress("正在恢复", 42) }
        compose.onNodeWithText("备份内容").assertDoesNotExist()
        compose.onNodeWithTag("backup_progress_label").assertIsDisplayed().assertTextEquals("42% · 正在恢复")
        compose.onNodeWithText("取消操作").performClick()
        compose.runOnIdle { assertEquals(1, cancellations); state.value = BackupUiState.Progress("正在安全取消", 42, true) }
        compose.onNodeWithText("正在安全取消").assertIsNotEnabled()
        val preview = RestorePreview(
            PreparedBackup(File(compose.activity.cacheDir, "unopened-ui-preview"), BackupContents("test", 0, emptyList())),
            listOf(RestoreToolPlan("one", "恢复工具", "2.0", "1.0")) +
                List(100) { RestoreToolPlan("extra$it", "额外工具 $it", "1.0", null) } +
                RestoreToolPlan("two", "不兼容工具", "3.0", null, "不支持的数据版本"),
            emptyMap(), setOf("session"), setOf("task"), listOf("请重新获取外部文件授权。"),
        )
        compose.runOnIdle { state.value = BackupUiState.ConfirmRestore(preview) }
        compose.onNodeWithText("备份内容").assertDoesNotExist()
        compose.onNodeWithTag("backup_conflicts").assertTextContains("恢复 101 个工具，其中 1 个与本机冲突", substring = true)
        compose.onNodeWithText("当前有 1 个运行环境和 1 个待执行或运行任务。恢复将停止全部本机工具会话与任务，完成或取消后均需手动重新打开。").assertExists()
        compose.onNodeWithText("下方版本覆盖只确认一次；安装时仍执行原有校验。备份中的授权不会绕过当前权限模型。").assertExists()
        compose.onNodeWithText("不兼容工具").assertDoesNotExist()
        compose.onNodeWithTag("backup_page").performScrollToKey("tool-one")
        compose.onNodeWithText("1.0 → 2.0").assertIsDisplayed()
        compose.onNodeWithTag("backup_page").performScrollToKey("tool-two")
        compose.onNodeWithText("跳过：不支持的数据版本").assertIsDisplayed()
        compose.onNodeWithTag("backup_page").performScrollToKey("warning-0")
        compose.onNodeWithText("请重新获取外部文件授权。").assertIsDisplayed()
        compose.onNodeWithTag("backup_page").performScrollToKey("confirm-actions")
        compose.onNodeWithTag("backup_confirm_restore").performScrollTo().performClick()
        compose.onNodeWithText("取消恢复").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, confirmations)
            assertEquals(2, cancellations)
            state.value = BackupUiState.Result("恢复已完成", "已恢复数据", listOf("请重新打开工具。"))
        }
        compose.onNodeWithText("备份内容").assertDoesNotExist()
        compose.onNodeWithTag("backup_result").assertIsDisplayed().assertTextEquals("恢复已完成")
        compose.onNodeWithText("请重新打开工具。").assertExists()
        compose.onNodeWithTag("backup_page").performScrollToKey("actions")
        compose.onNodeWithTag("backup_export").performScrollTo().assertIsEnabled()
    }

    @Test fun shortAndMultilineHelpCodeRemainReadableAndCopyExactly() {
        val single = "ready()"
        val multiline = "const title = '标题';\nshow(title);"
        val copied = mutableListOf<String>()
        val document = HelpDocument("测试手册", "说明", listOf(HelpChapter("chapter", "接口", "", listOf(
            HelpArticle("article", "准备接口", listOf(HelpBlock(single, true), HelpBlock(multiline, true, "JavaScript")), "准备接口"),
        ))), "完整手册")
        var pixelsPerDp = 0f
        compose.activity.setContent {
            val density = LocalDensity.current
            SideEffect { pixelsPerDp = density.density }
            ToolBoxTheme(style = style, reduceTransparency = true) {
                DeveloperHelpContent(document, {}, Modifier.fillMaxSize(), onCopy = { copied += it })
            }
        }
        compose.onNodeWithTag(DeveloperHelpTestTags.chapter("chapter")).performClick()
        compose.onNodeWithTag(DeveloperHelpTestTags.article("article")).performScrollTo().performClick()
        compose.onNodeWithTag(DeveloperHelpTestTags.List).performScrollToKey("block:article:0")
        val copy = compose.onNodeWithTag("help_code_copy:article:0").performScrollTo()
        val codeBounds = compose.onNodeWithText(single).fetchSemanticsNode().boundsInRoot
        val copyBounds = copy.fetchSemanticsNode().boundsInRoot
        assertTrue("Short code shares its row with the copy action", copyBounds.left >= codeBounds.right)
        assertTrue(copyBounds.top < codeBounds.bottom && copyBounds.bottom > codeBounds.top)
        assertTrue(copyBounds.height >= 48f * pixelsPerDp - 1f)
        copy.performClick()
        compose.onNodeWithTag(DeveloperHelpTestTags.List).performScrollToKey("block:article:1")
        compose.onNodeWithTag("help_code_copy:article:1").performScrollTo().performClick()
        compose.onNodeWithText(multiline).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(single, multiline), copied) }
    }

    @Test fun combinedAppearanceControlsKeepIndependentValuesAndAboutNamesTheActiveStyle() {
        val hostStyle = if (style == ToolBoxThemeStyle.Miuix) ThemeStyle.MIUIX else ThemeStyle.LIQUID_GLASS
        val state = mutableStateOf(SettingsUiState(HostSettings(theme = ThemeMode.LIGHT, themeStyle = hostStyle), loaded = true))
        val about = mutableStateOf(false)
        compose.activity.setContent {
            ToolBoxTheme(style = style, reduceTransparency = true) {
                if (about.value) AboutScreen({}, {}) else AppearanceContent(
                    state = state.value,
                    onThemeModeSelected = { state.value = state.value.copy(settings = state.value.settings.copy(theme = it)) },
                    onReduceTransparencyChanged = { state.value = state.value.copy(settings = state.value.settings.copy(reduceTransparency = it)) },
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithTag(HostTestTags.AppearanceSystemColor).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ThemeMode.MONET_LIGHT, state.value.settings.theme); assertFalse(state.value.settings.reduceTransparency) }
        if (style == ToolBoxThemeStyle.LiquidGlass) {
            compose.onNodeWithTag(HostTestTags.AppearanceReduceTransparency).performScrollTo().performClick()
            compose.runOnIdle { assertTrue(state.value.settings.reduceTransparency); assertEquals(ThemeMode.MONET_LIGHT, state.value.settings.theme) }
        } else compose.onNodeWithTag(HostTestTags.AppearanceReduceTransparency).assertDoesNotExist()
        compose.runOnIdle { about.value = true }
        compose.onNodeWithText(if (style == ToolBoxThemeStyle.Miuix) "Miuix" else "Liquid Glass").assertExists()
        compose.onNodeWithText("Liquid Glass · 唯一主题").assertDoesNotExist()
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun themes(): List<Array<Any>> = ToolBoxThemeStyle.entries.map { arrayOf<Any>(it) }
    }
}
