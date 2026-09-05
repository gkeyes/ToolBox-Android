package io.toolbox.host.permissions

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.ui.component.ToolBoxAppScaffold
import io.toolbox.core.ui.component.ToolBoxGroupDivider
import io.toolbox.core.ui.component.ToolBoxGroupedSurface
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxTextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import io.toolbox.core.ui.component.ToolBoxSwitchSettingRow
import io.toolbox.core.ui.component.ToolBoxTopBar
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.ui.AppText
import io.toolbox.host.ui.HostTestTags
import io.toolbox.host.ui.SurfaceCard
import io.toolbox.host.ui.CatalogStatusState
import io.toolbox.host.ui.SectionHeader

@Composable
internal fun PermissionCenterScreen(
    viewModel: PermissionCenterViewModel,
    onBack: () -> Unit,
    onReady: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pending by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val granted = if (pending == "location") {
            results["android.permission.ACCESS_COARSE_LOCATION"] == true ||
                results["android.permission.ACCESS_FINE_LOCATION"] == true
        } else {
            results.values.all { it }
        }
        viewModel.systemPermissionResult(pending, granted)
    }
    LaunchedEffect(viewModel) {
        viewModel.requests.collect { request ->
            pending = request.capability
            launcher.launch(request.permissions.toTypedArray())
        }
    }
    LaunchedEffect(state.loaded, state.message) {
        if (state.loaded || state.message != null) onReady()
    }
    PermissionCenterContent(
        state = state,
        onBack = onBack,
        onSetEnabled = viewModel::setEnabled,
        onOpenSystemSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        },
    )
}

@Composable
internal fun PermissionCenterContent(
    state: PermissionCenterUiState,
    onBack: () -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    val permissionGroups = remember(state.items) { state.items.permissionGroups() }
    var confirmSecureWipe by remember { mutableStateOf(false) }
    LaunchedEffect(state.loadState) {
        if (state.loadState != PermissionLoadState.Ready) confirmSecureWipe = false
    }
    OverlayDialog(
        show = confirmSecureWipe,
        title = "关闭并清除安全存储？",
        summary = "将删除此工具保存的密钥、令牌等安全数据，重新开启也无法恢复。普通工具数据不受影响。",
        onDismissRequest = { confirmSecureWipe = false },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one)) {
            ToolBoxTextButton("取消", { confirmSecureWipe = false }, modifier = Modifier.weight(1f))
            ToolBoxPrimaryButton(
                "关闭并清除",
                { confirmSecureWipe = false; onSetEnabled("storage.secure", false) },
                modifier = Modifier.weight(1f),
                destructive = true,
            )
        }
    }
    ToolBoxAppScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            ToolBoxTopBar(
                title = "工具权限",
                subtitle = state.toolName.takeUnless { it == "权限" }.orEmpty(),
                navigationIcon = ToolBoxIconKey.Back,
                onNavigationClick = onBack,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = ToolBoxThemeTokens.sizes.detailContentMaxWidth)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .align(Alignment.TopCenter),
                contentPadding = PaddingValues(
                    start = ToolBoxThemeTokens.spacing.two,
                    top = padding.calculateTopPadding() + ToolBoxThemeTokens.spacing.one,
                    end = ToolBoxThemeTokens.spacing.two,
                    bottom = padding.calculateBottomPadding() + ToolBoxThemeTokens.spacing.one,
                ),
            ) {
                item("explanation") {
                    AppText(
                        text = "只管理此工具已声明的能力。关闭后，对应功能会立即不可用。",
                        color = ToolBoxThemeTokens.colors.textSecondary,
                        textStyle = ToolBoxThemeTokens.textStyles.metadata,
                    )
                }
                item("after-explanation") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.oneHalf)) }
                state.message?.let { message ->
                    item("message") {
                        SurfaceCard {
                            AppText(message)
                            if (state.showSystemSettings) {
                                ToolBoxPrimaryButton("前往系统设置", onOpenSystemSettings)
                            }
                        }
                    }
                    item("after-message") { Spacer(Modifier.height(ToolBoxThemeTokens.spacing.oneHalf)) }
                }
                when (val loadState = state.loadState) {
                    PermissionLoadState.Loading -> item("loading") { CatalogStatusState("正在读取权限") }
                    PermissionLoadState.NotInstalled -> item("not-installed") {
                        SurfaceCard { AppText("工具已不存在。") }
                    }
                    is PermissionLoadState.Failed -> item("load-failed") {
                        SurfaceCard {
                            AppText("无法读取工具权限")
                            AppText(loadState.message, color = ToolBoxThemeTokens.colors.textSecondary)
                        }
                    }
                    PermissionLoadState.Ready -> if (state.items.isEmpty()) {
                        item("empty") { SurfaceCard { AppText("这个工具没有声明权限。") } }
                    } else permissionGroups.forEachIndexed { groupIndex, (title, items) ->
                        item("permission-title:$title") { SectionHeader("$title · ${items.size}") }
                        item("permission-title-gap:$title") {
                            Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one))
                        }
                        item("permission-group:$title") {
                            ToolBoxGroupedSurface {
                                items.forEachIndexed { index, item ->
                                    ToolBoxSwitchSettingRow(
                                        title = item.title,
                                        summary = item.reason,
                                        checked = item.enabled,
                                        onCheckedChange = { enabled ->
                                            if (item.capability == "storage.secure" && !enabled) {
                                                confirmSecureWipe = true
                                            } else {
                                                onSetEnabled(item.capability, enabled)
                                            }
                                        }
                                        icon = item.capability.capabilityIcon(),
                                        modifier = Modifier.testTag(HostTestTags.PermissionRowPrefix + item.capability),
                                    )
                                    if (index != items.lastIndex) ToolBoxGroupDivider()
                                }
                            }
                        }
                        if (groupIndex != permissionGroups.lastIndex) {
                            item("permission-group-gap:$title") {
                                Spacer(Modifier.height(ToolBoxThemeTokens.spacing.two))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun List<PermissionItem>.permissionGroups(): List<Pair<String, List<PermissionItem>>> {
    val grouped = groupBy { it.category() }
    return listOf("基础能力", "内容与文件", "网络与系统", "后台与位置")
        .mapNotNull { title -> grouped[title]?.takeIf { it.isNotEmpty() }?.let { title to it } }
}

private fun PermissionItem.category(): String = when (capability) {
    "storage", "storage.secure", "device.basic", "haptics" -> "基础能力"
    "clipboard.write", "clipboard.read", "share", "files.open", "files.save", "camera" -> "内容与文件"
    "network", "notifications", "shortcuts" -> "网络与系统"
    else -> "后台与位置"
}

private fun String.capabilityIcon(): ToolBoxIconKey = when (this) {
    "storage", "files.open", "files.save" -> ToolBoxIconKey.Folder
    "storage.secure" -> ToolBoxIconKey.Lock
    "clipboard.write", "clipboard.read" -> ToolBoxIconKey.Clipboard
    "share" -> ToolBoxIconKey.Share
    "network" -> ToolBoxIconKey.Globe
    "device.basic" -> ToolBoxIconKey.Device
    "haptics" -> ToolBoxIconKey.Haptics
    "notifications" -> ToolBoxIconKey.Notifications
    "shortcuts" -> ToolBoxIconKey.Tools
    "camera" -> ToolBoxIconKey.Camera
    "location", "location.background" -> ToolBoxIconKey.Location
    "background.tasks", "background.runtime", "alarms" -> ToolBoxIconKey.Clock
    else -> ToolBoxIconKey.Shield
}
