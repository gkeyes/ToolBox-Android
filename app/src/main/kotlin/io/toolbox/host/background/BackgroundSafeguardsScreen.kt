package io.toolbox.host.background

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.ui.component.ToolBoxAppScaffold
import io.toolbox.core.ui.component.ToolBoxGroupDivider
import io.toolbox.core.ui.component.ToolBoxGroupedSurface
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxSettingRow
import io.toolbox.core.ui.component.ToolBoxSwitchSettingRow
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.component.ToolBoxTopBar
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.runtime.RuntimeBackgroundSessionUi
import io.toolbox.host.runtime.RuntimeSessionManager
import io.toolbox.host.settings.SettingsViewModel
import io.toolbox.host.ui.AppText
import io.toolbox.host.ui.SectionHeader
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
internal fun BackgroundSafeguardsScreen(
    viewModel: SettingsViewModel,
    runtimeSessions: RuntimeSessionManager,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val sessions by runtimeSessions.sessions.collectAsStateWithLifecycle()
    var refreshGeneration by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshGeneration += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val systemState = remember(refreshGeneration) { readBackgroundSystemState(context) }
    val focusState by produceState(
        initialValue = FocusSupportState(protocolVersion = 0, supported = false, permissionGranted = false),
        key1 = refreshGeneration,
    ) {
        value = AndroidNotificationGateway(context).focusSupport()
    }
    val openAppSettings = { context.openFirstSupported(appDetailsIntent(context)) }
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshGeneration += 1
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
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshGeneration += 1 }

    ToolBoxAppScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            ToolBoxTopBar(
                title = "后台保障",
                navigationIcon = ToolBoxIconKey.Back,
                onNavigationClick = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ToolBoxThemeTokens.spacing.two,
                top = padding.calculateTopPadding() + ToolBoxThemeTokens.spacing.one,
                end = ToolBoxThemeTokens.spacing.two,
                bottom = padding.calculateBottomPadding() + ToolBoxThemeTokens.spacing.one,
            ),
            verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one),
        ) {
            item("master-title") { SectionHeader("运行") }
            item("master") {
                ToolBoxGroupedSurface {
                    ToolBoxSwitchSettingRow(
                        title = "允许后台运行",
                        summary = "关闭会停止持续环境、计时器、后台任务和对应通知",
                        checked = settings.settings.backgroundEnabled,
                        onCheckedChange = viewModel::setBackgroundEnabled,
                        icon = ToolBoxIconKey.Clock,
                        enabled = settings.loaded,
                    )
                }
            }

            item("sessions-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
            item("sessions-title") { SectionHeader("正在运行") }
            item("sessions") {
                ToolBoxGroupedSurface {
                    if (sessions.isEmpty()) {
                        SystemStatusRow("没有持续运行环境", "运行页面主动启动后会显示在这里")
                    } else {
                        sessions.forEachIndexed { index, session ->
                            ActiveSessionRow(
                                session = session,
                                onStop = { scope.launch { runtimeSessions.stopSession(session.sessionId) } },
                            )
                            if (index != sessions.lastIndex) ToolBoxGroupDivider(startPadding = ToolBoxThemeTokens.spacing.oneHalf)
                        }
                    }
                }
            }

            item("permissions-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
            item("permissions-title") { SectionHeader("系统保障") }
            item("permissions") {
                ToolBoxGroupedSurface {
                    ToolBoxSettingRow(
                        title = "通知",
                        summary = if (systemState.notificationsAllowed) "已允许" else "未允许",
                        icon = ToolBoxIconKey.Notifications,
                        onClick = {
                            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                context.openFirstSupported(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                                    appDetailsIntent(context),
                                )
                            }
                        },
                    )
                    ToolBoxGroupDivider()
                    ToolBoxSettingRow(
                        title = "后台定位",
                        summary = if (systemState.backgroundLocationAllowed) "已允许始终访问" else "未允许始终访问",
                        icon = ToolBoxIconKey.Location,
                        onClick = {
                            val hasForeground = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                                PackageManager.PERMISSION_GRANTED ||
                                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                                PackageManager.PERMISSION_GRANTED
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
                    )
                    ToolBoxGroupDivider()
                    ToolBoxSettingRow(
                        title = "精确闹钟",
                        summary = if (systemState.exactAlarmsAllowed) "已允许" else "未允许",
                        icon = ToolBoxIconKey.Clock,
                        onClick = {
                            context.openFirstSupported(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    .setData(Uri.parse("package:${context.packageName}")),
                                appDetailsIntent(context),
                            )
                        },
                    )
                    ToolBoxGroupDivider()
                    ToolBoxSettingRow(
                        title = "电池优化",
                        summary = if (systemState.ignoresBatteryOptimizations) "不受限制" else "受系统限制",
                        icon = ToolBoxIconKey.Device,
                        onClick = {
                            context.openFirstSupported(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                                appDetailsIntent(context),
                            )
                        },
                    )
                }
            }

            item("hyperos-gap") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one)) }
            item("hyperos-title") { SectionHeader("HyperOS") }
            item("hyperos") {
                ToolBoxGroupedSurface {
                    ToolBoxSettingRow(
                        title = "自启动设置",
                        summary = "由系统决定重启后能否自动恢复",
                        icon = ToolBoxIconKey.Refresh,
                        onClick = { context.openHyperOsAutoStart() },
                    )
                    ToolBoxGroupDivider()
                    ToolBoxSettingRow(
                        title = "后台省电设置",
                        summary = "允许系统为 ToolBox 调整后台策略",
                        icon = ToolBoxIconKey.Device,
                        onClick = { context.openHyperOsBatteryPolicy() },
                    )
                    ToolBoxGroupDivider()
                    SystemStatusRow(
                        title = "超级岛",
                        summary = focusState.summary(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveSessionRow(
    session: RuntimeBackgroundSessionUi,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ToolBoxThemeTokens.sizes.denseRow)
            .padding(horizontal = ToolBoxThemeTokens.spacing.oneHalf, vertical = ToolBoxThemeTokens.spacing.one),
    ) {
        AppText(session.toolName, textStyle = ToolBoxThemeTokens.textStyles.title)
        AppText(
            "开始于 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(session.startedAt))}",
            color = ToolBoxThemeTokens.colors.textSecondary,
            textStyle = ToolBoxThemeTokens.textStyles.metadata,
        )
        ToolBoxTextButton(
            label = "停止此环境",
            onClick = onStop,
            modifier = Modifier.fillMaxWidth(),
            contentColor = ToolBoxThemeTokens.colors.danger,
        )
    }
}

@Composable
private fun SystemStatusRow(title: String, summary: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ToolBoxThemeTokens.sizes.denseRow)
            .padding(horizontal = ToolBoxThemeTokens.spacing.oneHalf, vertical = ToolBoxThemeTokens.spacing.one),
    ) {
        AppText(title, textStyle = ToolBoxThemeTokens.textStyles.title)
        AppText(
            summary,
            color = ToolBoxThemeTokens.colors.textSecondary,
            textStyle = ToolBoxThemeTokens.textStyles.metadata,
        )
    }
}

private data class BackgroundSystemState(
    val notificationsAllowed: Boolean,
    val backgroundLocationAllowed: Boolean,
    val exactAlarmsAllowed: Boolean,
    val ignoresBatteryOptimizations: Boolean,
)

private fun readBackgroundSystemState(context: Context): BackgroundSystemState {
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val powerManager = context.getSystemService(PowerManager::class.java)
    return BackgroundSystemState(
        notificationsAllowed = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED && notificationManager.areNotificationsEnabled(),
        backgroundLocationAllowed = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED,
        exactAlarmsAllowed = alarmManager.canScheduleExactAlarms(),
        ignoresBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName),
    )
}

private fun FocusSupportState.summary(): String = when {
    protocolVersion >= 3 && permissionGranted -> "超级岛可用 · 协议 $protocolVersion"
    protocolVersion >= 3 -> "支持超级岛 · 焦点通知权限未开启"
    supported && permissionGranted -> "焦点通知可用 · 协议 $protocolVersion；超级岛需要 OS3"
    supported -> "支持焦点通知 · 当前会使用普通通知"
    else -> "未检测到支持 · 使用普通通知"
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
    val selected = intents.firstOrNull { intent ->
        intent.resolveActivity(packageManager) != null
    } ?: return
    runCatching { startActivity(selected.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure {
            intents.lastOrNull()?.takeIf { it !== selected }?.let { fallback ->
                runCatching { startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
}

private fun appDetailsIntent(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    .setData(Uri.fromParts("package", context.packageName, null))
