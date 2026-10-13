package io.toolbox.host.browser

import androidx.compose.ui.platform.LocalDensity
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.toolbox.core.ui.component.ToolBoxIcon
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxText
import io.toolbox.core.ui.component.toolBoxPressFeedback
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

@Composable
internal fun BrowserToolbar(state: BrowserUiState, filters: BrowserFilterController, actions: BrowserScreenActions) {
    val colors = ToolBoxThemeTokens.colors
    val uri = remember(state.address) { Uri.parse(state.address) }
    val unencrypted = uri.scheme == "http"
    val action = state.interaction.loadAction(state.clearing)
    val filterState = filters.snapshot
    val filtering = filterState.active(filters.site)
    val addressColor = if (state.interaction.unresponsive) colors.danger else colors.textPrimary
    val shieldTint = colors.primary
    val filterTint = if (filtering) colors.primary else ToolBoxThemeTokens.disabledContent

    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag("browser_toolbar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BrowserToolbarButton(ToolBoxIconKey.Close, "关闭浏览器", actions::close, compact = true)
        Row(
            Modifier.weight(1f).height(42.dp)
                .clip(io.toolbox.core.ui.component.toolBoxShape(ToolBoxThemeTokens.radii.button))
                .background(colors.surface)
                .testTag("browser_address"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f)
                    .clickable(role = Role.Button, onClickLabel = "查看完整地址", onClick = { state.fullAddress = true })
                    .padding(start = 11.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolBoxIcon(
                    ToolBoxIconKey.Shield,
                    "连接状态",
                    modifier = Modifier.size(17.dp),
                    tint = shieldTint,
                )
                Spacer(Modifier.width(6.dp))
                ToolBoxIcon(
                    ToolBoxIconKey.Haptics,
                    if (filtering) "广告过滤已开启" else "广告过滤未开启",
                    modifier = Modifier.size(17.dp),
                    tint = filterTint,
                )
                Spacer(Modifier.width(8.dp))
                ToolBoxText(
                    (if (state.interaction.unresponsive) "未响应 · " else "") + uri.host.orEmpty(),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = ToolBoxThemeTokens.textStyles.body.copy(
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = addressColor,
                    ),
                )
            }
            Box(Modifier.width(1.dp).height(20.dp).background(colors.textSecondary.copy(alpha = 0.10f)))
            BrowserRefreshButton(
                label = when (action) {
                    BrowserLoadAction.Stop -> "停止加载"
                    BrowserLoadAction.Recover -> "查看网页恢复选项"
                    else -> "重新加载"
                },
                enabled = action != BrowserLoadAction.Disabled,
                spinning = state.interaction.loading,
                onClick = actions::performLoadAction,
            )
        }
    }
}

@Composable
private fun BrowserRefreshButton(
    label: String,
    enabled: Boolean,
    spinning: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) ToolBoxThemeTokens.colors.textSecondary else ToolBoxThemeTokens.disabledContent
    val interactionSource = remember { MutableInteractionSource() }
    // Animate only while loading; the angle is read in the draw layer, so frames skip recomposition.
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(spinning) {
        rotation.snapTo(0f)
        if (spinning) {
            rotation.animateTo(
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 720, easing = LinearEasing),
                ),
            )
        }
    }
    Box(
        Modifier.size(40.dp)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        ToolBoxIcon(
            ToolBoxIconKey.Refresh,
            contentDescription = null,
            modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation.value },
            tint = tint,
        )
    }
}

@Composable
internal fun BrowserBottomBar(state: BrowserUiState, filters: BrowserFilterController, actions: BrowserScreenActions) {
    val canInteract = !state.clearing && !state.interaction.unresponsive && !state.interaction.restarting
    val blocked = filters.blockedCount
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .height(54.dp)
            .clip(io.toolbox.core.ui.component.toolBoxShape(ToolBoxThemeTokens.radii.denseSurface))
            .background(ToolBoxThemeTokens.colors.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrowserDockButton(
            ToolBoxIconKey.Back,
            "后退",
            Modifier.weight(1f),
            enabled = (state.canBack || filters.picker.active) && canInteract,
        ) {
            if (filters.picker.active) filters.stopPicker() else actions.traverseHistory(forward = false)
        }
        BrowserDockButton(
            ToolBoxIconKey.ChevronRight,
            "前进",
            Modifier.weight(1f),
            enabled = state.canForward && canInteract,
        ) {
            actions.traverseHistory(forward = true)
        }
        BrowserDockButton(
            ToolBoxIconKey.Shield,
            if (blocked > 0) "屏蔽 $blocked" else "屏蔽",
            Modifier.weight(1f),
            highlighted = filters.snapshot.active(filters.site),
            enabled = canInteract,
        ) {
            filters.stopPicker()
            filters.refresh()
            filters.sheet = true
        }
        BrowserDockButton(
            ToolBoxIconKey.Share,
            "分享",
            Modifier.weight(1f),
            enabled = canInteract,
        ) {
            actions.share()
        }
        BrowserDockButton(
            ToolBoxIconKey.More,
            "更多",
            Modifier.weight(1f),
            enabled = canInteract,
        ) { state.menu = true }
    }
}

@Composable
private fun BrowserDockButton(
    icon: ToolBoxIconKey,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = ToolBoxThemeTokens.colors
    val tint = when {
        !enabled -> ToolBoxThemeTokens.disabledContent
        highlighted -> colors.primary
        else -> colors.textSecondary
    }
    val interaction = remember { MutableInteractionSource() }
    val shape = io.toolbox.core.ui.component.toolBoxShape(ToolBoxThemeTokens.radii.control)
    Column(
        modifier.height(48.dp)
            .toolBoxPressFeedback(interaction, enabled = enabled, shape = shape)
            .clip(shape)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Large text: icon only (like the tab bar) so labels never clip in the 48dp button.
        if (LocalDensity.current.fontScale >= 1.5f) {
            ToolBoxIcon(icon, contentDescription = label, modifier = Modifier.size(22.dp), tint = tint)
        } else {
            ToolBoxIcon(icon, contentDescription = null, modifier = Modifier.size(19.dp), tint = tint)
            Spacer(Modifier.height(1.dp))
            ToolBoxText(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = ToolBoxThemeTokens.textStyles.label.copy(
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = tint,
                ),
            )
        }
    }
}

@Composable
private fun BrowserToolbarButton(
    icon: ToolBoxIconKey,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    stop: Boolean = false,
    compact: Boolean = false,
) {
    val tint = if (enabled) ToolBoxThemeTokens.colors.textSecondary else ToolBoxThemeTokens.disabledContent
    val size = if (compact) 40.dp else 44.dp
    val interaction = remember { MutableInteractionSource() }
    val shape = io.toolbox.core.ui.component.toolBoxShape(if (compact) ToolBoxThemeTokens.radii.control else ToolBoxThemeTokens.radii.button)
    Box(
        Modifier.size(size)
            .toolBoxPressFeedback(interaction, enabled = enabled, shape = shape)
            .clip(shape)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (stop) Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(tint))
        else ToolBoxIcon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
    }
}
