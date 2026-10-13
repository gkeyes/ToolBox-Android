package io.toolbox.host.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.toolbox.core.data.BackgroundOperation
import io.toolbox.core.data.BackgroundTask
import io.toolbox.core.data.CatalogGroup
import io.toolbox.core.data.CatalogLayout
import io.toolbox.core.data.TaskState
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.host.background.BackgroundSafeguardsContent
import io.toolbox.host.background.BackgroundSystemState
import io.toolbox.host.background.BackgroundTasksContent
import io.toolbox.host.background.BackgroundTasksPageModel
import io.toolbox.host.background.LiveNotificationSupportState
import io.toolbox.host.browser.BrowserBottomBar
import io.toolbox.host.browser.BrowserFilterController
import io.toolbox.host.browser.BrowserScreenActions
import io.toolbox.host.browser.BrowserToolbar
import io.toolbox.host.browser.BrowserUiState
import io.toolbox.host.browser.BrowserUserAgentMode
import io.toolbox.host.catalog.CatalogTool
import io.toolbox.host.catalog.CatalogUiState
import io.toolbox.host.catalog.RunningToolsUiState
import io.toolbox.host.catalog.withCatalogTools
import io.toolbox.host.importflow.ImportUiState
import io.toolbox.host.permissions.PermissionCenterContent
import io.toolbox.host.permissions.PermissionCenterUiState
import io.toolbox.host.permissions.PermissionItem
import io.toolbox.host.permissions.PermissionLoadState
import io.toolbox.host.runtime.RuntimeBackgroundSessionUi
import io.toolbox.host.settings.SettingsContent
import io.toolbox.host.settings.SettingsUiState
import io.toolbox.host.ui.CatalogGroupFolderOverlay
import io.toolbox.host.ui.MainDestination
import io.toolbox.host.ui.PrimaryScreen
import io.toolbox.host.ui.RuntimeShellPreviewContent
import io.toolbox.host.ui.ToolManagerContent
import io.toolbox.host.update.AppPackage
import io.toolbox.host.update.ChannelFeed
import io.toolbox.host.update.InstalledBuild
import io.toolbox.host.update.ReleaseAsset
import io.toolbox.host.update.ToolPackage
import io.toolbox.host.update.ToolRowState
import io.toolbox.host.update.ToolUpdateRow
import io.toolbox.host.update.UpdateChannel
import io.toolbox.host.update.UpdateContent
import io.toolbox.host.update.UpdateUiState
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the main host screens on a narrow phone (320dp) at normal and the largest font size,
 * saves PNGs for review, and fails when text is clipped without an ellipsis or squeezed into a
 * sliver (one or two characters per line). Run with `-Ptoolbox.screenshots=true`.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h1400dp-xxhdpi")
class LayoutScreenshotTest(private val fontScale: Float) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "font{0}")
        fun scales() = listOf(arrayOf<Any>(1f), arrayOf<Any>(2f))
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val outputDir = File(System.getProperty("toolbox.screenshots.dir") ?: "build/screenshots")
    private val now = 1_760_000_000_000L
    private val pixelCopy = RoborazziOptions(captureType = RoborazziOptions.CaptureType.Screenshot())

    @Before fun setFontScale() {
        RuntimeEnvironment.setFontScale(fontScale)
    }

    private fun render(name: String, dialog: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { ToolBoxTheme { content() } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val file = File(outputDir, "${name}_font${fontScale}.png")
        // Screenshot capture goes through PixelCopy, so RenderNode layers (animateItem, glass) draw too.
        if (dialog) captureScreenRoboImage(file.path, roborazziOptions = pixelCopy)
        else compose.onRoot().captureRoboImage(file.path, roborazziOptions = pixelCopy)
        assertTextFits(name)
    }

    private fun assertTextFits(name: String) {
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
        val problems = nodes.mapNotNull { node -> node.layoutProblem() }
        assertTrue("$name @${fontScale}x has text layout problems:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    private fun SemanticsNode.layoutProblem(): String? {
        val results = mutableListOf<TextLayoutResult>()
        config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        val layout = results.firstOrNull() ?: return null
        val text = config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: layout.layoutInput.text.text
        if (text.isBlank()) return null
        val fontPx = with(layout.layoutInput.density) { layout.layoutInput.style.fontSize.toPx() }
        // Text hidden without an ellipsis: lines dropped by maxLines/height, or a line wider than its box.
        val last = layout.lineCount - 1
        val hidden = layout.didOverflowWidth || layout.getLineEnd(last, visibleEnd = false) < layout.layoutInput.text.length
        val cut = hidden && !layout.isLineEllipsized(last) && layout.layoutInput.overflow != TextOverflow.Ellipsis
        // A label narrower than ~2.5 glyphs that still wraps is the "one character per line" failure.
        val squeezed = layout.lineCount > 1 && fontPx > 0 && layout.size.width < fontPx * 2.5f
        return when {
            cut -> "clipped: \"$text\" (${layout.size.width}x${layout.size.height}px, ${layout.lineCount} lines)"
            squeezed -> "squeezed: \"$text\" (${layout.size.width}px wide, ${layout.lineCount} lines)"
            else -> null
        }
    }

    // ---- Fixtures ----

    private val tools = listOf(
        CatalogTool("io.toolbox.nextflux", "NextFlux 阅读器", 12, "1.4.2", 1_200_000, now - 60_000, now - 9_000_000),
        CatalogTool("io.toolbox.healthrecords", "健康记录与用药提醒", 7, "0.9.0", 800_000, now - 120_000, now - 8_000_000),
        CatalogTool("io.toolbox.calculator", "计算器", 3, "2.0.0", 90_000, now - 300_000, now - 7_000_000),
        CatalogTool("io.toolbox.qrcode", "二维码扫描", 2, "1.1.0", 150_000, null, now - 6_000_000),
        CatalogTool("io.toolbox.unitconvert", "单位换算", 4, "1.3.0", 70_000, null, now - 5_000_000),
        CatalogTool("io.toolbox.timer", "番茄钟专注计时", 5, "1.0.5", 60_000, now - 900_000, now - 4_000_000),
        CatalogTool("io.toolbox.notes", "快速笔记", 6, "3.2.1", 210_000, null, now - 3_000_000),
    )
    private val groups = listOf(
        CatalogGroup("g-work", "工作效率", listOf("io.toolbox.calculator", "io.toolbox.timer", "io.toolbox.notes", "io.toolbox.unitconvert"), color = "blue"),
        CatalogGroup("g-life", "生活", listOf("io.toolbox.healthrecords", "io.toolbox.qrcode"), color = "orange"),
    )
    private val catalog = CatalogUiState(
        isLoaded = true,
        layout = CatalogLayout(favorites = listOf("io.toolbox.nextflux", "io.toolbox.healthrecords", "io.toolbox.calculator"), groups = groups),
    ).withCatalogTools(tools)

    private val sessions = listOf(
        RuntimeBackgroundSessionUi("s1", "io.toolbox.timer", "番茄钟专注计时", now - 600_000, 11, statusText = "专注中 · 剩余 18 分钟"),
        RuntimeBackgroundSessionUi("s2", "io.toolbox.nextflux", "NextFlux 阅读器", now - 1_800_000, 12),
    )

    // ---- Screens ----

    @Composable private fun Primary(destination: MainDestination) {
        val home = rememberLazyListState()
        PrimaryScreen(destination, {}, destination.label, onImport = {}, onOrganize = {}, onCreateGroup = {}) { page, padding, _ ->
            if (page == MainDestination.Settings) {
                SettingsContent(SettingsUiState(loaded = true), padding, {}, {}, {}, {})
            } else {
                ToolManagerContent(
                    state = catalog, importState = ImportUiState(), listState = home, contentPadding = padding,
                    onAction = {}, onImport = {}, onInstallExamples = {}, onDismissImport = {}, onOpenDetails = {},
                    home = page == MainDestination.Home,
                )
            }
        }
    }

    @Test fun home() = render("home") { Primary(MainDestination.Home) }

    @Test fun allTools() = render("tools") { Primary(MainDestination.Tools) }

    @Test fun settings() = render("settings") { Primary(MainDestination.Settings) }

    @Test fun groupFolder() = render("group_folder", dialog = true) {
        Box(Modifier.fillMaxSize()) { Primary(MainDestination.Home) }
        CatalogGroupFolderOverlay(groups[0], tools.filter { it.toolId in groups[0].members }, editing = false, {}, {}, {}, {})
    }

    @Test fun softwareUpdate() = render("update") {
        val asset = ReleaseAsset("toolbox-v0.8.32-dev.apk", "https://example.invalid/a.apk", 12_000_000, null)
        val tool = { slug: String -> ToolPackage(slug, "1.4.3", asset.copy(name = "$slug-v1.4.3.tbx")) }
        UpdateContent(
            state = UpdateUiState(
                installed = InstalledBuild("0.8.32", 65, 18_412_345_678),
                channel = UpdateChannel.TEST,
                feed = ChannelFeed(
                    UpdateChannel.TEST,
                    AppPackage("0.8.33", 66, "95bfa0f", 18_419_876_543, "2026-10-13T16:49:00Z", "修正首页分组弹层透明", asset),
                    listOf(tool("nextflux"), tool("health-records"), tool("calculator")),
                ),
                tools = listOf(
                    ToolUpdateRow(tool("nextflux"), "io.toolbox.nextflux", "NextFlux 阅读器", "1.4.2", ToolRowState.UPDATE),
                    ToolUpdateRow(tool("health-records"), "io.toolbox.healthrecords", "健康记录与用药提醒", "1.4.3", ToolRowState.CURRENT),
                    ToolUpdateRow(tool("calculator"), null, "计算器", null, ToolRowState.NOT_INSTALLED),
                ),
            ),
            canInstallApk = true, onBack = {}, onRefresh = {}, onSelectChannel = {}, onInstallApp = {}, onGrantInstall = {},
            onInstallTool = {}, onUpdateAll = {}, onCheckOnLaunchChange = {}, onDismissMessage = {},
        )
    }

    @Test fun backgroundSafeguards() = render("background_safeguards") {
        BackgroundSafeguardsContent(
            settings = SettingsUiState(loaded = true),
            runningState = RunningToolsUiState(sessions = sessions),
            systemState = BackgroundSystemState(notificationsAllowed = true, backgroundLocationAllowed = false, exactAlarmsAllowed = false, ignoresBatteryOptimizations = false),
            focusState = LiveNotificationSupportState(3, hyperOsSupported = true, hyperOsPermissionReported = false, androidLiveAvailable = true, androidLiveAllowed = false),
            onBack = {}, onSetBackgroundEnabled = {}, onStopSession = {}, onStopAll = {}, onCancelStop = {}, onConfirmStop = {},
            onDismissFeedback = {}, onOpenNotifications = {}, onOpenBackgroundLocation = {}, onOpenExactAlarms = {},
            onOpenBatteryOptimization = {}, onOpenHyperOsAutoStart = {}, onOpenHyperOsBatteryPolicy = {},
        )
    }

    @Test fun backgroundTasks() = render("background_tasks") {
        val task = { id: String, tool: String, periodic: Boolean, state: TaskState ->
            BackgroundTask(id, tool, 1, "sync-$id", BackgroundOperation.HTTP_GET, "{}", periodic, if (periodic) 15 else null, state, now - 3_600_000, now - 60_000, now + 600_000, 0)
        }
        BackgroundTasksContent(
            page = BackgroundTasksPageModel(
                tasks = listOf(
                    task("t1", "io.toolbox.nextflux", true, TaskState.QUEUED),
                    task("t2", "io.toolbox.healthrecords", false, TaskState.RUNNING),
                    task("t3", "io.toolbox.timer", false, TaskState.COMPLETED),
                ),
                runtimeSessions = sessions,
            ),
            message = null, cancellingTaskId = null, stoppingSessionId = null, onBack = {},
            resultFor = { null }, onStopSession = {}, onCancelTask = {},
        )
    }

    @Test fun permissions() = render("permissions") {
        PermissionCenterContent(
            state = PermissionCenterUiState(
                toolName = "健康记录与用药提醒",
                loadState = PermissionLoadState.Ready,
                items = listOf(
                    PermissionItem("notifications", "通知", "提醒你按时用药和复诊", true, listOf("android.permission.POST_NOTIFICATIONS")),
                    PermissionItem("camera", "相机", "扫描药盒上的条形码", false, listOf("android.permission.CAMERA")),
                    PermissionItem("location", "位置", "记录就诊医院的位置", false, listOf("android.permission.ACCESS_FINE_LOCATION")),
                    PermissionItem("network", "网络", "同步健康数据到你自己的服务器", true, emptyList()),
                    PermissionItem("storage", "存储", "导出 PDF 报告", true, emptyList()),
                ),
            ),
            onBack = {}, onSetEnabled = { _, _ -> }, onOpenSystemSettings = {}, exactAlarmsAllowed = false,
        )
    }

    @Test fun runtimePage() = render("runtime") { RuntimeShellPreviewContent("健康记录与用药提醒") }

    @Test fun browserBars() = render("browser") {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val state = androidx.compose.runtime.remember {
            BrowserUiState().apply {
                address = "https://news.example.com/articles/2026/10/13/liquid-glass"
                title = "Liquid Glass"
                canBack = true
            }
        }
        val filters = androidx.compose.runtime.remember { BrowserFilterController(context, scope) }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.statusBarsPadding()) { BrowserToolbar(state, filters, NoBrowserActions) }
            Box(Modifier.navigationBarsPadding()) { BrowserBottomBar(state, filters, NoBrowserActions) }
        }
    }

    private object NoBrowserActions : BrowserScreenActions {
        override fun close() = Unit
        override fun reload() = Unit
        override fun performLoadAction() = Unit
        override fun traverseHistory(forward: Boolean) = Unit
        override fun share() = Unit
        override fun openSystemBrowser() = Unit
        override fun copyAddress() = Unit
        override fun startMediaDiagnostics() = Unit
        override fun copyMediaDiagnostics() = Unit
        override fun changeUserAgentMode(mode: BrowserUserAgentMode) = Unit
        override fun clearWebsiteData() = Unit
        override fun restartUnresponsivePage() = Unit
        override fun applySystemBarAppearance(light: Boolean) = Unit
    }
}
