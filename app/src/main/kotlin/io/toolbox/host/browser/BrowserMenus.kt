package io.toolbox.host.browser

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.toolbox.core.ui.component.ToolBoxActionSheet
import io.toolbox.core.ui.component.ToolBoxActionSheetHeader
import io.toolbox.core.ui.component.ToolBoxIcon
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxModalDialog
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxSecondaryButton
import io.toolbox.core.ui.component.ToolBoxText
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.component.toolBoxPressFeedback
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

@Composable
internal fun BrowserMenuSheet(state: BrowserUiState, actions: BrowserScreenActions) {
    // The page behind keeps playing, so a frozen frosted copy would not match it.
    ToolBoxActionSheet(title = "浏览器菜单", onDismissRequest = { state.menu = false }, frostedBackdrop = false) {
        Column(Modifier.fillMaxWidth()) {
            ToolBoxActionSheetHeader {
                Spacer(Modifier.height(2.dp))
            }
            BrowserMenuAction("使用系统浏览器", ToolBoxIconKey.Globe) {
                state.menu = false
                actions.openSystemBrowser()
            }
            BrowserMenuAction("复制链接", ToolBoxIconKey.Clipboard) {
                actions.copyAddress()
                state.menu = false
            }
            BrowserMenuAction(
                "网站兼容性 · ${state.userAgentMode.shortLabel}",
                ToolBoxIconKey.Device,
            ) {
                state.menu = false
                state.userAgentSheet = true
            }
            BrowserMenuAction(
                "媒体诊断",
                ToolBoxIconKey.Code,
                enabled = state.webView != null && !state.clearing && !state.interaction.restarting && !state.interaction.unresponsive,
            ) {
                state.menu = false
                actions.startMediaDiagnostics()
            }
            BrowserMenuAction(
                "清除浏览器网站数据",
                ToolBoxIconKey.Shield,
                destructive = true,
                enabled = !state.clearing && !state.interaction.restarting,
            ) {
                state.menu = false
                state.clearConfirmation = true
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
internal fun BrowserMediaDiagnosticsSheet(state: BrowserUiState, actions: BrowserScreenActions) {
    val colors = ToolBoxThemeTokens.colors
    ToolBoxActionSheet(
        title = "媒体诊断",
        onDismissRequest = { state.mediaDiagnosticsSheet = false },
        containerColor = colors.background,
    ) {
        Column(Modifier.fillMaxWidth()) {
            ToolBoxActionSheetHeader {
                Column(Modifier.fillMaxWidth()) {
                    ToolBoxText(
                        if (state.mediaDiagnosticsRunning) "正在诊断…" else "诊断结果",
                        style = ToolBoxThemeTokens.textStyles.title.copy(
                            color = colors.textPrimary,
                            fontSize = 18.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    ToolBoxText(
                        "只记录播放器能力、媒体相关资源错误和 DOM 状态；不会记录网页正文、Cookie 或请求参数。",
                        style = ToolBoxThemeTokens.textStyles.body.copy(
                            color = colors.textSecondary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        ),
                    )
                }
            }
            Column(
                Modifier.fillMaxWidth()
                    .clip(io.toolbox.core.ui.component.toolBoxShape(ToolBoxThemeTokens.radii.button))
                    .background(colors.surface)
                    .padding(12.dp),
            ) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    ToolBoxText(
                        state.mediaDiagnosticsReport,
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(min = 140.dp, max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        style = ToolBoxThemeTokens.textStyles.metadata.copy(
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BrowserAddressSheetAction(
                    label = if (state.mediaDiagnosticsRunning) "诊断中…" else "重新诊断",
                    icon = ToolBoxIconKey.Refresh,
                    highlighted = true,
                    modifier = Modifier.weight(1f),
                ) {
                    if (!state.mediaDiagnosticsRunning) actions.startMediaDiagnostics()
                }
                BrowserAddressSheetAction(
                    label = "复制报告",
                    icon = ToolBoxIconKey.Clipboard,
                    modifier = Modifier.weight(1f),
                ) {
                    actions.copyMediaDiagnostics()
                }
            }
        }
    }
}

@Composable
internal fun BrowserUserAgentSheet(state: BrowserUiState, actions: BrowserScreenActions) {
    val colors = ToolBoxThemeTokens.colors
    ToolBoxActionSheet(
        title = "网站兼容性",
        onDismissRequest = { state.userAgentSheet = false },
        containerColor = colors.background,
    ) {
        Column(Modifier.fillMaxWidth()) {
            ToolBoxActionSheetHeader {
                Column(Modifier.fillMaxWidth()) {
                    ToolBoxText(
                        "网页身份",
                        style = ToolBoxThemeTokens.textStyles.title.copy(
                            color = colors.textPrimary,
                            fontSize = 18.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    ToolBoxText(
                        "切换后会重新加载当前网页。默认使用 Chrome Mobile，提高视频和登录页兼容性。",
                        style = ToolBoxThemeTokens.textStyles.body.copy(
                            color = colors.textSecondary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        ),
                    )
                }
            }
            BrowserUserAgentOption(
                state,
                actions,
                mode = BrowserUserAgentMode.ChromeMobile,
                label = "Chrome Mobile",
                description = "默认 · 适合大多数移动网页和视频播放器",
            )
            BrowserUserAgentOption(
                state,
                actions,
                mode = BrowserUserAgentMode.AndroidWebView,
                label = "Android WebView",
                description = "原始身份 · 用于兼容性排查",
            )
            BrowserUserAgentOption(
                state,
                actions,
                mode = BrowserUserAgentMode.DesktopChrome,
                label = "Desktop Chrome",
                description = "桌面身份 · 请求桌面版网页",
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
internal fun BrowserAddressSheet(state: BrowserUiState, actions: BrowserScreenActions) {
    val colors = ToolBoxThemeTokens.colors
    val uri = remember(state.address) { Uri.parse(state.address) }
    val host = uri.host.orEmpty().ifBlank { "网页地址" }
    val connectionLabel = when (uri.scheme?.lowercase()) {
        "https" -> "HTTPS 页面"
        "http" -> "HTTP 页面"
        else -> "网页地址"
    }
    ToolBoxActionSheet(
        title = "网页地址",
        onDismissRequest = { state.fullAddress = false },
        containerColor = colors.background,
    ) {
        Column(Modifier.fillMaxWidth()) {
            ToolBoxActionSheetHeader {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(36.dp)
                            .clip(io.toolbox.core.ui.component.toolBoxShape(ToolBoxThemeTokens.radii.control))
                            .background(colors.softPrimary),
                        contentAlignment = Alignment.Center,
                    ) {
                        ToolBoxIcon(
                            ToolBoxIconKey.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = colors.primary,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        ToolBoxText(
                            host,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = ToolBoxThemeTokens.textStyles.title.copy(
                                color = colors.textPrimary,
                                fontSize = 18.sp,
                                lineHeight = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                        Spacer(Modifier.height(2.dp))
                        ToolBoxText(
                            connectionLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = ToolBoxThemeTokens.textStyles.label.copy(
                                color = colors.textSecondary,
                            ),
                        )
                    }
                    Box(
                        Modifier.size(36.dp)
                            .clip(io.toolbox.core.ui.component.toolBoxShape(ToolBoxThemeTokens.radii.control))
                            .background(colors.surfaceMuted)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "关闭地址详情",
                                onClick = { state.fullAddress = false },
                            )
                            .semantics { contentDescription = "关闭地址详情" },
                        contentAlignment = Alignment.Center,
                    ) {
                        ToolBoxIcon(
                            ToolBoxIconKey.Close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = colors.textSecondary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.fillMaxWidth()
                    .clip(io.toolbox.core.ui.component.toolBoxShape(ToolBoxThemeTokens.radii.denseSurface))
                    .background(colors.surface)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                ToolBoxText(
                    "完整地址",
                    style = ToolBoxThemeTokens.textStyles.label.copy(
                        color = colors.textSecondary,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                androidx.compose.foundation.text.selection.SelectionContainer {
                    ToolBoxText(
                        state.address,
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(max = 150.dp)
                            .verticalScroll(rememberScrollState()),
                        style = ToolBoxThemeTokens.textStyles.body.copy(
                            color = colors.textSecondary,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BrowserAddressSheetAction(
                    label = "复制链接",
                    icon = ToolBoxIconKey.Clipboard,
                    highlighted = true,
                    modifier = Modifier.weight(1f).testTag("browser_copy_address"),
                    onClick = actions::copyAddress,
                )
                BrowserAddressSheetAction(
                    label = "使用系统浏览器",
                    icon = ToolBoxIconKey.Globe,
                    modifier = Modifier.weight(1f),
                ) {
                    state.fullAddress = false
                    actions.openSystemBrowser()
                }
            }
        }
    }
}

@Composable
internal fun BrowserClearDataDialog(state: BrowserUiState, actions: BrowserScreenActions) {
    val colors = ToolBoxThemeTokens.colors
    ToolBoxModalDialog(onDismissRequest = { if (!state.clearing) state.clearConfirmation = false }) {
        ToolBoxText(
            "清除浏览器网站数据",
            modifier = Modifier.semantics { heading() },
            style = ToolBoxThemeTokens.textStyles.title.copy(
                color = colors.textPrimary, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
            ),
        )
        Spacer(Modifier.height(16.dp))
        ToolBoxText(
            "清除所有工具共用的浏览器网站登录、缓存和网站存储。NextFlux 的账号及工具数据会保留。",
            style = ToolBoxThemeTokens.textStyles.body.copy(color = colors.textSecondary),
        )
        Spacer(Modifier.height(24.dp))
        ToolBoxPrimaryButton(
            if (state.clearing) "正在清除…" else "清除", actions::clearWebsiteData,
            modifier = Modifier.fillMaxWidth(), enabled = !state.clearing,
            destructive = true,
        )
        Spacer(Modifier.height(12.dp))
        ToolBoxSecondaryButton(
            "取消", { state.clearConfirmation = false },
            modifier = Modifier.fillMaxWidth(), enabled = !state.clearing,
        )
    }
}

@Composable
internal fun BrowserRecoveryDialog(state: BrowserUiState, actions: BrowserScreenActions) {
    val colors = ToolBoxThemeTokens.colors
    ToolBoxModalDialog(onDismissRequest = { state.interaction = state.interaction.keepWaiting() }) {
        ToolBoxText("网页暂未响应", modifier = Modifier.semantics { heading() },
            style = ToolBoxThemeTokens.textStyles.title.copy(color = colors.textPrimary))
        Spacer(Modifier.height(12.dp))
        ToolBoxText(
            "可以继续等待。重新加载会重启网页进程，未提交的内容可能丢失，其他内置浏览器页面也可能需要重新加载。小工具数据不受影响。",
            style = ToolBoxThemeTokens.textStyles.body.copy(color = colors.textSecondary),
        )
        Spacer(Modifier.height(20.dp))
        ToolBoxSecondaryButton("继续等待", { state.interaction = state.interaction.keepWaiting() }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        ToolBoxTextButton("重新加载", actions::restartUnresponsivePage, Modifier.fillMaxWidth(), outlined = false)
    }
}

@Composable
private fun BrowserAddressSheetAction(
    label: String,
    icon: ToolBoxIconKey,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = ToolBoxThemeTokens.colors
    val background = if (highlighted) colors.softPrimary else colors.surface
    val tint = if (highlighted) colors.primary else colors.textPrimary
    val interaction = remember { MutableInteractionSource() }
    val shape = io.toolbox.core.ui.component.toolBoxShape(ToolBoxThemeTokens.radii.button)
    Row(
        modifier
            .height(ToolBoxThemeTokens.sizes.primaryButton)
            .toolBoxPressFeedback(interaction, shape = shape)
            .clip(shape)
            .background(background)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        ToolBoxIcon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = tint,
        )
        Spacer(Modifier.width(7.dp))
        ToolBoxText(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = ToolBoxThemeTokens.textStyles.body.copy(
                color = tint,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
private fun BrowserUserAgentOption(
    state: BrowserUiState,
    actions: BrowserScreenActions,
    mode: BrowserUserAgentMode,
    label: String,
    description: String,
) {
    val colors = ToolBoxThemeTokens.colors
    val selected = state.userAgentMode == mode
    Row(
        Modifier.fillMaxWidth()
            .clip(io.toolbox.core.ui.component.toolBoxShape(ToolBoxThemeTokens.radii.button))
            .background(if (selected) colors.softPrimary else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(
                enabled = !state.clearing && !state.interaction.restarting,
                role = Role.Button,
                onClick = { actions.changeUserAgentMode(mode) },
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp)
                .clip(io.toolbox.core.ui.component.toolBoxShape(ToolBoxThemeTokens.radii.control))
                .background(if (selected) colors.primary.copy(alpha = 0.12f) else colors.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            ToolBoxIcon(
                ToolBoxIconKey.Device,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = if (selected) colors.primary else colors.textSecondary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            ToolBoxText(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = ToolBoxThemeTokens.textStyles.body.copy(
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.height(2.dp))
            ToolBoxText(
                description,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = ToolBoxThemeTokens.textStyles.metadata.copy(
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
            )
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            ToolBoxIcon(
                ToolBoxIconKey.Check,
                contentDescription = "当前网页身份",
                modifier = Modifier.size(19.dp),
                tint = colors.primary,
            )
        }
    }
}

@Composable
private fun BrowserMenuAction(
    label: String,
    icon: ToolBoxIconKey,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = ToolBoxThemeTokens.colors
    val contentColor = when {
        !enabled -> ToolBoxThemeTokens.disabledContent
        destructive -> colors.onSoftDanger
        else -> colors.textPrimary
    }
    val interaction = remember { MutableInteractionSource() }
    val shape = io.toolbox.core.ui.component.toolBoxShape(ToolBoxThemeTokens.radii.denseSurface)
    Row(
        modifier = Modifier.fillMaxWidth().height(ToolBoxThemeTokens.sizes.denseRow)
            .toolBoxPressFeedback(interaction, enabled = enabled, shape = shape, scale = io.toolbox.core.ui.component.ToolBoxMotion.RowPressScale)
            .clip(shape)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolBoxIcon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = when {
                !enabled -> ToolBoxThemeTokens.disabledContent
                destructive -> colors.danger
                else -> colors.textSecondary
            },
        )
        Spacer(Modifier.width(12.dp))
        ToolBoxText(
            label,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = ToolBoxThemeTokens.textStyles.body.copy(
                fontSize = 16.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Normal,
                color = if (destructive && enabled) colors.danger else contentColor,
            ),
        )
    }
}
