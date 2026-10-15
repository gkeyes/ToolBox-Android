package io.toolbox.host.background

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.ui.component.ToolBoxAppScaffold
import io.toolbox.core.ui.component.ToolBoxGroupedSurface
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.toolBoxShape
import io.toolbox.host.settings.SettingsFooter
import io.toolbox.host.settings.SettingsIconTint
import io.toolbox.host.settings.SettingsNavRow
import io.toolbox.host.settings.SettingsRowSeparator
import io.toolbox.host.settings.SettingsSwitchRow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.component.ToolBoxDestructiveButton
import io.toolbox.core.ui.component.ToolBoxTopBar
import io.toolbox.core.ui.component.rememberToolBoxGlassState
import io.toolbox.core.ui.component.toolBoxBackdropSource
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.core.ui.component.toolBoxEntrance
import androidx.compose.animation.animateContentSize
import io.toolbox.host.runtime.RuntimeBackgroundSessionUi
import io.toolbox.host.catalog.RunningToolsViewModel
import io.toolbox.host.catalog.RunningToolsUiState
import io.toolbox.host.ui.RunningToolRow
import io.toolbox.host.ui.RunningToolsStopDialog
import io.toolbox.host.ui.FeedbackSurface
import io.toolbox.host.ui.FeedbackTone
import androidx.compose.runtime.key
import io.toolbox.host.settings.SettingsViewModel
import io.toolbox.host.settings.BackgroundSettingsFeedback
import io.toolbox.host.settings.backgroundProgressSummary
import io.toolbox.host.ui.AppText
import io.toolbox.host.ui.SectionHeader

@Composable
internal fun BackgroundSafeguardsScreen(
    viewModel: SettingsViewModel,
    safeguards: BackgroundSafeguardsViewModel,
    runningTools: RunningToolsViewModel,
    onBack: () -> Unit,
    onReady: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val runningState by runningTools.state.collectAsStateWithLifecycle()
    val safeguardsState by safeguards.state.collectAsStateWithLifecycle()

    LaunchedEffect(settings.loaded) {
        if (settings.loaded) onReady()
    }

    DisposableEffect(lifecycleOwner, safeguards) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) safeguards.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val openAppSettings = { context.openFirstSupported(appDetailsIntent(context)) }
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        safeguards.refresh()
        if (!granted) openAppSettings()
    }
    val foregroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (granted) backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else openAppSettings()
    }
    val openNotificationSettings = {
        context.openFirstSupported(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            appDetailsIntent(context),
        )
    }
    val activity = LocalActivity.current
    val notificationRationale = {
        activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true
    }
    var rationaleBeforeRequest by remember { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        safeguards.refresh()
        // After two denials Android answers false without showing a dialog (no rationale before or
        // after the request); only then send the user to settings, never after a first-time denial.
        if (!granted && !rationaleBeforeRequest && !notificationRationale()) openNotificationSettings()
    }

    BackgroundSafeguardsContent(
        settings = settings,
        runningState = runningState,
        systemState = safeguardsState.systemState,
        focusState = safeguardsState.focusState,
        onBack = onBack,
        onSetBackgroundEnabled = viewModel::setBackgroundEnabled,
        onRetryBackground = viewModel::retryBackgroundUpdate,
        onStopSession = { runningTools.requestStop(it.sessionId) },
        onStopAll = runningTools::requestStopAll,
        onCancelStop = runningTools::cancelStop,
        onConfirmStop = runningTools::confirmStop,
        onDismissFeedback = runningTools::dismissFeedback,
        onOpenNotifications = {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                rationaleBeforeRequest = notificationRationale()
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openNotificationSettings()
            }
        },
        onOpenBackgroundLocation = {
            val hasForeground = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (hasForeground) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                foregroundLocationLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ),
                )
            }
        },
        onOpenExactAlarms = {
            context.openFirstSupported(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:${context.packageName}")),
                appDetailsIntent(context),
            )
        },
        onOpenBatteryOptimization = {
            context.openFirstSupported(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                appDetailsIntent(context),
            )
        },
        onOpenHyperOsAutoStart = context::openHyperOsAutoStart,
        onOpenHyperOsBatteryPolicy = context::openHyperOsBatteryPolicy,
    )
}

@Composable
internal fun BackgroundSafeguardsContent(
    settings: io.toolbox.host.settings.SettingsUiState,
    runningState: RunningToolsUiState,
    systemState: BackgroundSystemState,
    focusState: LiveNotificationSupportState,
    onBack: () -> Unit,
    onSetBackgroundEnabled: (Boolean) -> Unit,
    onStopSession: (RuntimeBackgroundSessionUi) -> Unit,
    onStopAll: () -> Unit,
    onCancelStop: () -> Unit,
    onConfirmStop: () -> Unit,
    onDismissFeedback: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenBackgroundLocation: () -> Unit,
    onOpenExactAlarms: () -> Unit,
    onOpenBatteryOptimization: () -> Unit,
    onOpenHyperOsAutoStart: () -> Unit,
    onOpenHyperOsBatteryPolicy: () -> Unit,
    onRetryBackground: () -> Unit = {},
) {
    val sessions = runningState.sessions
    val entrance = io.toolbox.core.ui.component.rememberToolBoxEntrance("background-safeguards")
    io.toolbox.host.ui.DetailScreen(title = "后台保障", onBack = onBack) { padding ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = io.toolbox.host.ui.rememberTitleBoundListState(),
                modifier = Modifier
                    .widthIn(max = ToolBoxThemeTokens.sizes.detailContentMaxWidth)
                    .fillMaxWidth()
                    .fillMaxSize()
                    .align(Alignment.TopCenter),
                contentPadding = PaddingValues(
                    start = ToolBoxThemeTokens.spacing.two,
                    top = padding.calculateTopPadding() + ToolBoxThemeTokens.spacing.one,
                    end = ToolBoxThemeTokens.spacing.two,
                    bottom = padding.calculateBottomPadding() + ToolBoxThemeTokens.spacing.one,
                ),
                verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one),
            ) {
            if (settings.backgroundError != null) {
                item("background-error") { BackgroundSettingsFeedback(settings, onRetryBackground) }
            }
            item("status") {
                SafeguardStatusCard(
                    backgroundEnabled = settings.settings.backgroundEnabled,
                    systemState = systemState,
                    modifier = Modifier.toolBoxEntrance(entrance, 0).padding(bottom = ToolBoxThemeTokens.spacing.one),
                )
            }
            item("master-title") { Box(Modifier.toolBoxEntrance(entrance, 0)) { SectionHeader("运行") } }
            item("master") {
                ToolBoxGroupedSurface(Modifier.toolBoxEntrance(entrance, 1)) {
                    SettingsSwitchRow(
                        title = "允许后台运行",
                        summary = settings.backgroundProgressSummary ?: "关闭会停止持续环境、计时器、后台任务和对应通知",
                        checked = settings.settings.backgroundEnabled,
                        onCheckedChange = onSetBackgroundEnabled,
                        icon = ToolBoxIconKey.Clock,
                        tint = SettingsIconTint.Blue,
                        enabled = settings.canChangeBackground,
                        modifier = Modifier.testTag("safeguards_background_enabled"),
                    )
                }
            }

            item("sessions-title") {
                Column(Modifier.toolBoxEntrance(entrance, 2).padding(top = ToolBoxThemeTokens.spacing.two)) { SectionHeader("正在运行", "${sessions.size} 个") }
            }
            runningState.feedback?.let { feedback ->
                item("stop-error") { FeedbackSurface(feedback.message, FeedbackTone.Error, true, onDismissFeedback) }
            }
            item("sessions") {
                ToolBoxGroupedSurface(Modifier.toolBoxEntrance(entrance, 3).animateContentSize(io.toolbox.core.ui.component.ToolBoxMotion.spatial())) {
                    if (sessions.isEmpty()) {
                        SystemStatusRow("没有持续运行环境", "运行页面主动启动后会显示在这里")
                    } else {
                        sessions.forEachIndexed { index, session ->
                            key(session.sessionId) {
                                RunningToolRow(
                                    session = session, tool = null,
                                    stopping = runningState.stoppingSessionId == session.sessionId,
                                    canStop = runningState.stoppingSessionId == null,
                                    onOpen = null, onStop = { onStopSession(session) },
                                )
                            }
                            if (index != sessions.lastIndex) SettingsRowSeparator(start = ToolBoxThemeTokens.spacing.md)
                        }
                        if (sessions.isNotEmpty()) {
                            SettingsRowSeparator(start = ToolBoxThemeTokens.spacing.md)
                            Box(Modifier.fillMaxWidth().padding(ToolBoxThemeTokens.spacing.oneHalf), contentAlignment = Alignment.CenterEnd) {
                                ToolBoxDestructiveButton(
                                    label = "全部停止", onClick = onStopAll,
                                    enabled = runningState.stoppingSessionId == null,
                                )
                            }
                        }
                    }
                }
            }

            item("permissions-title") {
                Column(Modifier.toolBoxEntrance(entrance, 4).padding(top = ToolBoxThemeTokens.spacing.two)) { SectionHeader("系统保障") }
            }
            item("permissions") {
                Column(Modifier.toolBoxEntrance(entrance, 5)) {
                    ToolBoxGroupedSurface {
                        SettingsNavRow(
                            title = "通知",
                            icon = ToolBoxIconKey.Notifications,
                            tint = SettingsIconTint.Red,
                            onClick = onOpenNotifications,
                            trailing = { SystemChip(if (systemState.notificationsAllowed) "已允许" else "未允许", systemState.notificationsAllowed) },
                        )
                        SettingsRowSeparator()
                        SettingsNavRow(
                            title = "后台定位",
                            icon = ToolBoxIconKey.Location,
                            tint = SettingsIconTint.Blue,
                            onClick = onOpenBackgroundLocation,
                            trailing = {
                                SystemChip(
                                    if (systemState.backgroundLocationAllowed) "已允许始终访问" else "未允许始终访问",
                                    systemState.backgroundLocationAllowed,
                                )
                            },
                        )
                        SettingsRowSeparator()
                        SettingsNavRow(
                            title = "精确闹钟",
                            icon = ToolBoxIconKey.Clock,
                            tint = SettingsIconTint.Orange,
                            onClick = onOpenExactAlarms,
                            trailing = { SystemChip(if (systemState.exactAlarmsAllowed) "已允许" else "未允许", systemState.exactAlarmsAllowed) },
                        )
                        SettingsRowSeparator()
                        SettingsNavRow(
                            title = "电池优化",
                            icon = ToolBoxIconKey.Device,
                            tint = SettingsIconTint.Mint,
                            onClick = onOpenBatteryOptimization,
                            trailing = {
                                SystemChip(
                                    if (systemState.ignoresBatteryOptimizations) "不受限制" else "受系统限制",
                                    systemState.ignoresBatteryOptimizations,
                                )
                            },
                        )
                    }
                    SettingsFooter("工具授权与 Android 系统权限分别生效。返回本页时会重新读取系统状态。")
                }
            }

            item("hyperos-title") {
                Column(Modifier.toolBoxEntrance(entrance, 6).padding(top = ToolBoxThemeTokens.spacing.two)) { SectionHeader("HyperOS") }
            }
            item("hyperos") {
                ToolBoxGroupedSurface(Modifier.toolBoxEntrance(entrance, 7)) {
                    SettingsNavRow(
                        title = "自启动设置",
                        summary = "由系统决定重启后能否自动恢复",
                        icon = ToolBoxIconKey.Refresh,
                        tint = SettingsIconTint.Aqua,
                        onClick = onOpenHyperOsAutoStart,
                    )
                    SettingsRowSeparator()
                    SettingsNavRow(
                        title = "后台省电设置",
                        summary = "允许系统为 ToolBox 调整后台策略",
                        icon = ToolBoxIconKey.Device,
                        tint = SettingsIconTint.Mint,
                        onClick = onOpenHyperOsBatteryPolicy,
                    )
                    SettingsRowSeparator()
                    SettingsNavRow(
                        title = "实时通知与超级岛",
                        summary = focusState.summary(),
                        icon = ToolBoxIconKey.Notifications,
                        tint = SettingsIconTint.Purple,
                    )
                }
            }
            }
        }
    }
    RunningToolsStopDialog(runningState, onCancelStop, onConfirmStop)
}

/** Summary card: a ring of how many system safeguards are ready, and what to do next. */
@Composable
private fun SafeguardStatusCard(
    backgroundEnabled: Boolean,
    systemState: BackgroundSystemState,
    modifier: Modifier = Modifier,
) {
    val colors = ToolBoxThemeTokens.colors
    val checks = listOf(
        systemState.notificationsAllowed,
        systemState.backgroundLocationAllowed,
        systemState.exactAlarmsAllowed,
        systemState.ignoresBatteryOptimizations,
    )
    val okCount = checks.count { it }
    val allOk = okCount == checks.size
    val attention = checks.size - okCount
    val title = when {
        !backgroundEnabled -> "后台运行已关闭"
        allOk -> "后台保障良好"
        else -> "有 $attention 项需要处理"
    }
    val detail = when {
        !backgroundEnabled -> "持续环境、计时器和后台任务均已停止。"
        allOk -> "系统权限均已就绪，后台工具可以稳定运行。"
        else -> "开启下方系统权限，避免提醒和后台刷新被系统中断。"
    }
    val progress by animateFloatAsState(
        targetValue = okCount / checks.size.toFloat(),
        animationSpec = io.toolbox.core.ui.component.ToolBoxMotion.spatial(),
        label = "safeguard ring",
    )
    val ringColor = if (allOk) colors.success else colors.warning
    val track = colors.surfaceMuted
    Row(
        modifier
            .fillMaxWidth()
            .clip(toolBoxShape(ToolBoxThemeTokens.radii.card))
            .background(colors.surface)
            .semantics(mergeDescendants = true) {}
            .padding(ToolBoxThemeTokens.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.md),
    ) {
        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 7.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                drawArc(ringColor, -90f, 360f * progress, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            if (allOk) {
                io.toolbox.core.ui.component.ToolBoxIcon(ToolBoxIconKey.Check, null, Modifier.size(28.dp), tint = colors.success)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AppText("$okCount/${checks.size}", textStyle = ToolBoxThemeTokens.textStyles.headline)
                    AppText("已就绪", textStyle = ToolBoxThemeTokens.textStyles.label, color = colors.textSecondary)
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.xxs)) {
            SystemChip(if (allOk) "全部就绪" else "需要注意", allOk)
            AppText(title, textStyle = ToolBoxThemeTokens.textStyles.headline)
            AppText(detail, textStyle = ToolBoxThemeTokens.textStyles.sectionTitle, color = colors.textSecondary)
        }
    }
}

@Composable
private fun SystemChip(label: String, ok: Boolean) {
    io.toolbox.core.ui.component.ToolBoxStatusChip(
        label,
        if (ok) io.toolbox.core.ui.component.ToolBoxTone.Success else io.toolbox.core.ui.component.ToolBoxTone.Warning,
    )
}

@Composable
private fun SystemStatusRow(title: String, summary: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ToolBoxThemeTokens.sizes.denseRow)
            .padding(horizontal = ToolBoxThemeTokens.spacing.md, vertical = ToolBoxThemeTokens.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.xxs),
    ) {
        AppText(title, textStyle = ToolBoxThemeTokens.textStyles.title)
        AppText(
            summary,
            color = ToolBoxThemeTokens.colors.textSecondary,
            textStyle = ToolBoxThemeTokens.textStyles.metadata,
        )
    }
}

private fun LiveNotificationSupportState.summary(): String {
    val android = when {
        !androidLiveAvailable -> "Android 实时更新不可用"
        androidLiveAllowed -> "Android 实时更新可请求"
        else -> "Android 实时更新未获系统允许"
    }
    val hyperOs = if (hyperOsSupported) {
        val permission = if (hyperOsPermissionReported) "系统报告焦点权限已开" else "系统未报告焦点权限"
        "检测到超级岛协议 $hyperOsProtocolVersion，仍会提交完整数据（$permission）"
    } else {
        "未检测到超级岛协议"
    }
    return "$android；$hyperOs。普通持续通知始终作为基础显示。"
}

private fun Context.openHyperOsAutoStart() {
    openFirstSupported(
        Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        ),
        appDetailsIntent(this),
    )
}

private fun Context.openHyperOsBatteryPolicy() {
    openFirstSupported(
        Intent().setComponent(
            ComponentName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            ),
        ).putExtra("package_name", packageName).putExtra("package_label", applicationInfo.loadLabel(packageManager)),
        Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
        appDetailsIntent(this),
    )
}

private fun Context.openFirstSupported(vararg intents: Intent) {
    // Start each candidate directly: resolveActivity can miss vendor pages hidden by package
    // visibility, so an unresolvable intent is detected by its start failure instead.
    for (intent in intents) {
        if (runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

private fun appDetailsIntent(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    .setData(Uri.fromParts("package", context.packageName, null))
