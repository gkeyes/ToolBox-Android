package io.toolbox.host.update

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.ui.component.ToolBoxBanner
import io.toolbox.core.ui.component.ToolBoxBusyIndicator
import io.toolbox.core.ui.component.ToolBoxGroupedSurface
import io.toolbox.core.ui.component.ToolBoxIcon
import io.toolbox.core.ui.component.ToolBoxGlassIconButton
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxMotion
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxSecondaryButton
import io.toolbox.core.ui.component.ToolBoxText
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.component.ToolBoxTone
import io.toolbox.core.ui.component.rememberToolBoxHaptics
import io.toolbox.core.ui.component.toolBoxPressFeedback
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.settings.SettingsFooter
import io.toolbox.host.settings.SettingsIconTile
import io.toolbox.host.settings.SettingsIconTint
import io.toolbox.host.settings.SettingsNavRow
import io.toolbox.host.settings.SettingsRowSeparator
import io.toolbox.host.settings.SettingsSwitchRow
import io.toolbox.host.ui.AppText
import io.toolbox.host.ui.CatalogToolGlyph
import io.toolbox.host.ui.DetailScreen
import io.toolbox.host.ui.ContentSectionHeader
import io.toolbox.host.ui.mergePadding
import io.toolbox.host.ui.rememberTitleBoundListState
import io.toolbox.host.ui.toolVisual

@Composable
internal fun UpdateScreen(viewModel: UpdateViewModel, onBack: () -> Unit, onReady: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var canInstall by remember { mutableStateOf(ApkInstaller.canInstall(context)) }
    // The user may grant "install unknown apps" in system settings and come back.
    LifecycleResumeEffect(context) {
        canInstall = ApkInstaller.canInstall(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { onReady() }
    LaunchedEffect(state.pendingApk) {
        val apk = state.pendingApk ?: return@LaunchedEffect
        runCatching { ApkInstaller.install(context, apk) }
            .onSuccess { viewModel.apkHandedOff() }
            .onFailure { viewModel.apkHandOffFailed() }
    }
    UpdateContent(
        state = state,
        canInstallApk = canInstall,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onSelectChannel = viewModel::selectChannel,
        onInstallApp = viewModel::downloadApp,
        onGrantInstall = {
            runCatching { ApkInstaller.openInstallPermissionSettings(context) }
                .onFailure { viewModel.installSettingsUnavailable() }
        },
        onInstallTool = viewModel::installTool,
        onUpdateAll = viewModel::updateAllTools,
        onCheckOnLaunchChange = viewModel::setCheckOnLaunch,
        onDismissMessage = viewModel::dismissMessage,
    )
}

@Composable
internal fun UpdateContent(
    state: UpdateUiState,
    canInstallApk: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectChannel: (UpdateChannel) -> Unit,
    onInstallApp: () -> Unit,
    onGrantInstall: () -> Unit,
    onInstallTool: (String) -> Unit,
    onUpdateAll: () -> Unit,
    onCheckOnLaunchChange: (Boolean) -> Unit,
    onDismissMessage: () -> Unit,
) {
    val spacing = ToolBoxThemeTokens.spacing
    DetailScreen(
        title = "软件更新",
        onBack = onBack,
        actions = { glassState ->
            // Refresh is disabled while a check is already running.
            ToolBoxGlassIconButton(
                icon = ToolBoxIconKey.Refresh,
                contentDescription = "重新检查",
                onClick = onRefresh,
                glassState = glassState,
                modifier = Modifier.testTag("update_refresh"),
                enabled = !state.loading,
            )
        },
    ) { padding ->
        LazyColumn(
            state = rememberTitleBoundListState(),
            modifier = Modifier.fillMaxSize().testTag("update_page"),
            contentPadding = mergePadding(padding, PaddingValues(spacing.md)),
        ) {
            item("channel-title") { GroupHeader("更新渠道", first = true) }
            item("channel") {
                ToolBoxGroupedSurface(Modifier.selectableGroup()) {
                    UpdateChannel.entries.forEachIndexed { index, channel ->
                        if (index > 0) SettingsRowSeparator()
                        ChannelRow(
                            channel = channel,
                            selected = channel == state.channel,
                            onSelect = { onSelectChannel(channel) },
                            modifier = Modifier.testTag(
                                if (channel == UpdateChannel.STABLE) "update_channel_stable" else "update_channel_test",
                            ),
                        )
                    }
                }
            }
            item("channel-footer") {
                SettingsFooter(
                    when (state.channel) {
                        UpdateChannel.STABLE -> "来自 GitHub 发布的正式版本。"
                        UpdateChannel.TEST -> "来自 main 分支最新一次通过 CI 的构建，可能不稳定。"
                    },
                )
            }
            state.message?.let { message ->
                item("message") {
                    Column(Modifier.padding(top = 16.dp)) {
                        ToolBoxBanner(message, ToolBoxTone.Info, icon = ToolBoxIconKey.Check)
                        ToolBoxTextButton("知道了", onDismissMessage, outlined = false)
                    }
                }
            }
            state.error?.let { error ->
                item("error") {
                    Column(Modifier.padding(top = 16.dp)) {
                        ToolBoxBanner(error, ToolBoxTone.Danger)
                        ToolBoxTextButton("重试", onRefresh, outlined = false)
                    }
                }
            }
            if (state.loading && state.feed == null) {
                item("loading") {
                    Box(Modifier.fillMaxWidth().padding(spacing.lg), contentAlignment = Alignment.Center) {
                        ToolBoxBusyIndicator(Modifier.size(24.dp))
                    }
                }
            }
            item("app-title") { GroupHeader("ToolBox") }
            item("app") { AppSection(state, canInstallApk, onInstallApp, onGrantInstall) }
            state.feed?.let {
                val updatable = state.updatableTools
                item("tools-title") { GroupHeader("小工具") }
                if (updatable.isNotEmpty()) {
                    item("update-all") {
                        ToolBoxPrimaryButton(
                            "全部更新（${updatable.size}）", onUpdateAll,
                            Modifier.fillMaxWidth().padding(bottom = spacing.sm).testTag("update_all_tools"),
                            enabled = updatable.any { row -> row.available.slug !in state.progress },
                        )
                    }
                }
                val installed = state.tools.filter { row -> row.state != ToolRowState.NOT_INSTALLED }
                val available = state.tools.filter { row -> row.state == ToolRowState.NOT_INSTALLED }
                item("installed-tools") {
                    ToolBoxGroupedSurface {
                        if (installed.isEmpty()) {
                            SettingsNavRow(
                                "没有可对照的已安装小工具", ToolBoxIconKey.Tools, SettingsIconTint.Gray,
                                summary = "此渠道发布的小工具都尚未安装",
                            )
                        }
                        installed.forEachIndexed { index, row ->
                            if (index > 0) SettingsRowSeparator()
                            ToolRow(row, state.progress, onInstallTool)
                        }
                    }
                }
                if (available.isNotEmpty()) {
                    item("available-title") { GroupHeader("未安装") }
                    item("available-tools") {
                        ToolBoxGroupedSurface {
                            available.forEachIndexed { index, row ->
                                if (index > 0) SettingsRowSeparator()
                                ToolRow(row, state.progress, onInstallTool)
                            }
                        }
                    }
                }
            }
            item("options-title") { GroupHeader("自动检查") }
            item("options") {
                ToolBoxGroupedSurface {
                    SettingsSwitchRow(
                        title = "启动时检查更新",
                        summary = "最多每 6 小时检查一次，有更新时提示",
                        checked = state.checkOnLaunch,
                        onCheckedChange = onCheckOnLaunchChange,
                        icon = ToolBoxIconKey.Refresh,
                        tint = SettingsIconTint.Aqua,
                        modifier = Modifier.testTag("update_check_on_launch"),
                    )
                }
            }
        }
    }
}

/** Content-screen section heading, spaced 26dp above and 7dp to the card. */
@Composable
private fun GroupHeader(title: String, first: Boolean = false) {
    Box(Modifier.padding(top = if (first) 4.dp else 26.dp, bottom = 7.dp)) { ContentSectionHeader(title) }
}

/** iOS Settings choice row: coloured tile, title and summary, accent checkmark when chosen. */
@Composable
private fun ChannelRow(channel: UpdateChannel, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ToolBoxThemeTokens.colors
    val haptics = rememberToolBoxHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val stable = channel == UpdateChannel.STABLE
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .toolBoxPressFeedback(interactionSource, scale = ToolBoxMotion.RowPressScale)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.RadioButton,
                onClick = {
                    if (!selected) {
                        haptics.tick()
                        onSelect()
                    }
                },
            )
            .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsIconTile(
            if (stable) ToolBoxIconKey.Shield else ToolBoxIconKey.Code,
            if (stable) SettingsIconTint.Green else SettingsIconTint.Orange,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            ToolBoxText(channel.label, style = ToolBoxThemeTokens.textStyles.body.copy(color = colors.textPrimary))
            ToolBoxText(
                if (stable) "GitHub 发布" else "main 分支 CI 构建",
                style = ToolBoxThemeTokens.textStyles.sectionTitle.copy(color = colors.textSecondary),
            )
        }
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (selected) ToolBoxIcon(ToolBoxIconKey.Check, null, Modifier.size(22.dp), tint = colors.primary)
        }
    }
}

@Composable
private fun AppSection(state: UpdateUiState, canInstallApk: Boolean, onInstallApp: () -> Unit, onGrantInstall: () -> Unit) {
    val spacing = ToolBoxThemeTokens.spacing
    val app = state.feed?.app
    val progress = state.progress[AppProgressKey]
    val downloading = AppProgressKey in state.progress
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        ToolBoxGroupedSurface {
            VersionRow(
                title = "当前版本",
                value = state.installed.describe(),
                icon = ToolBoxIconKey.Tools,
                tint = SettingsIconTint.Graphite,
            )
            if (app != null) {
                SettingsRowSeparator()
                VersionRow(
                    title = if (state.channel == UpdateChannel.TEST) "最新测试版" else "最新正式版",
                    value = app.describe(),
                    icon = ToolBoxIconKey.Refresh,
                    tint = SettingsIconTint.Blue,
                    summary = when (state.appState) {
                        AppUpdateState.NEWER -> "有新版本"
                        AppUpdateState.CURRENT -> "已是最新"
                        AppUpdateState.OTHER_BUILD -> "与当前版本号相同的另一个构建"
                        AppUpdateState.DOWNGRADE -> "版本号低于当前版本，需先卸载才能安装"
                        null -> null
                    },
                    valueColor = if (state.appState == AppUpdateState.NEWER) {
                        ToolBoxThemeTokens.colors.primary
                    } else {
                        ToolBoxThemeTokens.colors.textSecondary
                    },
                )
            }
        }
        if (app != null && state.appState != AppUpdateState.DOWNGRADE && state.appState != AppUpdateState.CURRENT) {
            when {
                !canInstallApk -> {
                    ToolBoxBanner("安装更新前，需要允许 ToolBox 安装应用。", ToolBoxTone.Warning, icon = ToolBoxIconKey.Shield)
                    ToolBoxSecondaryButton("前往允许", onGrantInstall, Modifier.fillMaxWidth().testTag("update_grant_install"))
                }
                downloading -> AppText(
                    progress?.let { "正在下载 ${(it * 100).toInt()}%" } ?: "正在下载…",
                    modifier = Modifier.padding(horizontal = spacing.md).testTag("update_app_progress"),
                    color = ToolBoxThemeTokens.colors.textSecondary,
                )
                state.appState == AppUpdateState.NEWER -> ToolBoxPrimaryButton(
                    "下载并安装", onInstallApp, Modifier.fillMaxWidth().testTag("update_install_app"),
                )
                else -> ToolBoxSecondaryButton(
                    "安装此构建", onInstallApp, Modifier.fillMaxWidth().testTag("update_install_app"),
                )
            }
        }
    }
}

@Composable
private fun ToolRow(row: ToolUpdateRow, progress: Map<String, Float?>, onInstall: (String) -> Unit) {
    val slug = row.available.slug
    // Installed tools use their own icon; a tool not installed yet uses the id it will install under.
    val toolId = row.toolId ?: row.available.toolId
    SettingsNavRow(
        title = row.name,
        icon = ToolBoxIconKey.Tools,
        tint = when (row.state) {
            ToolRowState.UPDATE -> SettingsIconTint.Blue
            ToolRowState.CURRENT -> SettingsIconTint.Green
            ToolRowState.NOT_INSTALLED -> SettingsIconTint.Gray
        },
        summary = when (row.state) {
            ToolRowState.UPDATE -> "${row.installedVersion} → ${row.available.version}"
            ToolRowState.CURRENT -> "${row.installedVersion} · 已是最新"
            ToolRowState.NOT_INSTALLED -> "可安装 ${row.available.version}"
        },
        modifier = Modifier.testTag("update_tool_$slug"),
        leading = {
            CatalogToolGlyph(toolId, null, toolVisual(toolId, row.name, ToolBoxThemeTokens.colors.primary), size = 30.dp)
        },
        trailing = {
            when {
                slug in progress -> ToolBoxBusyIndicator(Modifier.size(24.dp))
                row.state == ToolRowState.UPDATE -> ToolBoxTextButton("更新", { onInstall(slug) })
                row.state == ToolRowState.NOT_INSTALLED -> ToolBoxTextButton("安装", { onInstall(slug) })
                else -> Unit
            }
        },
    )
}

/**
 * Version row: the title always shows in full, and the version with its build number sits beside
 * it when it fits, otherwise on its own line that scrolls sideways if still too long. The status note
 * runs under the title across the whole row.
 */
@Composable
private fun VersionRow(
    title: String,
    value: String,
    icon: ToolBoxIconKey,
    tint: SettingsIconTint,
    summary: String? = null,
    valueColor: Color = ToolBoxThemeTokens.colors.textSecondary,
) {
    val colors = ToolBoxThemeTokens.colors
    val textStyles = ToolBoxThemeTokens.textStyles
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (summary == null) 52.dp else 60.dp)
            .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
    // The version moves to its own line whenever it would not fit beside the title, so the build
    // number (what tells test builds apart) is never cut mid-glyph; on its own line it wraps instead.
    val fitsBeside = with(density) {
        val titleWidth = measurer.measure(title, textStyles.body, maxLines = 1).size.width.toDp()
        val valueWidth = measurer.measure(value, textStyles.body, maxLines = 1).size.width.toDp()
        30.dp + 12.dp + titleWidth + 12.dp + valueWidth <= maxWidth
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
        // Large text on a narrow phone: the version gets its own line so most of it stays visible.
        val stacked = !fitsBeside || LocalConfiguration.current.screenWidthDp / LocalDensity.current.fontScale < 260f
        val valueText = @Composable { modifier: Modifier ->
            ToolBoxText(
                value,
                modifier = modifier,
                style = textStyles.body.copy(color = valueColor),
                maxLines = if (stacked) 3 else 1,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsIconTile(icon, tint)
            ToolBoxText(
                title,
                modifier = if (stacked) Modifier.weight(1f) else Modifier,
                style = textStyles.body.copy(color = colors.textPrimary),
                maxLines = if (stacked) 2 else 1,
            )
            if (!stacked) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { valueText(Modifier) }
            }
        }
        if (stacked) valueText(Modifier.padding(start = 42.dp))
        summary?.let {
            ToolBoxText(
                it,
                modifier = Modifier.padding(start = 42.dp),
                style = textStyles.sectionTitle.copy(color = colors.textSecondary),
            )
        }
    }
    }
}

private fun InstalledBuild.describe(): String =
    if (runId > 0) "$versionName（构建 $runId）" else versionName

private fun AppPackage.describe(): String =
    if (runId != null) "$versionName（构建 $runId）" else versionName
