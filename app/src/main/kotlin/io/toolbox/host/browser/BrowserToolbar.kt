package io.toolbox.host.browser

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import io.toolbox.core.ui.component.toolBoxSolidGlass
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

/** Round glass close button floating over the page's top-left corner, as in the iOS 27 design. */
@Composable
internal fun BrowserCloseButton(actions: BrowserScreenActions, modifier: Modifier = Modifier) {
    BrowserGlassCircle(ToolBoxIconKey.Close, "关闭浏览器", modifier.testTag("browser_close"), size = 44.dp, onClick = actions::close)
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

/**
 * Floating glass toolbar: a back circle, the address capsule (ad filter shield with the blocked
 * count, connection icon and host, reload) and a "more" circle. Forward, share and copy link live
 * in the more menu.
 */
@Composable
internal fun BrowserBottomBar(state: BrowserUiState, filters: BrowserFilterController, actions: BrowserScreenActions) {
    val canInteract = !state.clearing && !state.interaction.unresponsive && !state.interaction.restarting
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp).height(52.dp)
            .testTag("browser_toolbar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BrowserGlassCircle(
            ToolBoxIconKey.Back,
            "后退",
            enabled = (state.canBack || filters.picker.active) && canInteract,
        ) {
            if (filters.picker.active) filters.stopPicker() else actions.traverseHistory(forward = false)
        }
        BrowserAddressCapsule(state, filters, actions, canInteract, Modifier.weight(1f))
        BrowserGlassCircle(ToolBoxIconKey.More, "更多", enabled = canInteract) { state.menu = true }
    }
}

@Composable
private fun BrowserAddressCapsule(
    state: BrowserUiState,
    filters: BrowserFilterController,
    actions: BrowserScreenActions,
    canInteract: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = ToolBoxThemeTokens.colors
    val uri = remember(state.address) { Uri.parse(state.address) }
    val unencrypted = uri.scheme == "http"
    // A page loaded past a certificate error, or with plain-HTTP content, is no safer than HTTP.
    val insecure = unencrypted || state.certificateBypassed || state.mixedContent
    val action = state.interaction.loadAction(state.clearing)
    val filtering = filters.snapshot.active(filters.site)
    val blocked = filters.blockedCount
    val hostColor = if (state.interaction.unresponsive || insecure) colors.danger else colors.textPrimary
    Row(
        modifier.fillMaxHeight().shadow(10.dp, RoundedCornerShape(26.dp), clip = false)
            .toolBoxSolidGlass(RoundedCornerShape(26.dp))
            .padding(horizontal = 4.dp)
            .testTag("browser_address"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shieldInteraction = remember { MutableInteractionSource() }
        // The badge sits outside the clipped circle so the circle can't cut it off.
        Box(Modifier.size(42.dp)) {
            Box(
                Modifier.matchParentSize()
                    .toolBoxPressFeedback(shieldInteraction, enabled = canInteract, shape = CircleShape)
                    .clip(CircleShape)
                    .background(if (filtering && canInteract) colors.primary.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable(enabled = canInteract, interactionSource = shieldInteraction, indication = null, role = Role.Button) {
                        filters.stopPicker()
                        filters.refresh()
                        filters.sheet = true
                    }
                    .semantics { contentDescription = if (blocked > 0) "屏蔽 $blocked" else "屏蔽" },
                contentAlignment = Alignment.Center,
            ) {
                ToolBoxIcon(
                    ToolBoxIconKey.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = when {
                        !canInteract -> ToolBoxThemeTokens.disabledContent
                        filtering -> colors.primary
                        else -> colors.textSecondary
                    },
                )
            }
            if (blocked > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).clearAndSetSemantics {}.offset(x = 1.dp, y = 3.dp).heightIn(min = 16.dp).widthIn(min = 18.dp)
                        .clip(RoundedCornerShape(8.dp)).background(colors.primary).padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ToolBoxText(
                        if (blocked > 99) "99+" else blocked.toString(),
                        maxLines = 1,
                        style = ToolBoxThemeTokens.textStyles.label.copy(
                            fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        ),
                    )
                }
            }
        }
        Row(
            Modifier.weight(1f).fillMaxHeight()
                .clickable(role = Role.Button, onClickLabel = "查看完整地址", onClick = { state.fullAddress = true })
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            // On narrow phones the lock gives its room to the domain; an insecure page keeps its warning globe.
            val narrow = LocalConfiguration.current.screenWidthDp < 360
            if (insecure || !narrow) {
                ToolBoxIcon(
                    if (insecure) ToolBoxIconKey.Globe else ToolBoxIconKey.Lock,
                    when {
                        unencrypted -> "HTTP 页面"
                        state.certificateBypassed -> "证书有问题的页面"
                        state.mixedContent -> "含不安全内容的页面"
                        else -> "HTTPS 页面"
                    },
                    modifier = Modifier.size(14.dp),
                    tint = hostColor,
                )
                Spacer(Modifier.width(4.dp))
            }
            // The domain shrinks to fit rather than being cut, down to a floor that ignores the system
            // font scale so "example.com" stays whole at 320dp; tapping shows the full address. Anything
            // still too long is cut from the start, so the end of the domain is what stays visible.
            val hostStyle = ToolBoxThemeTokens.textStyles.body.copy(
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = hostColor,
            )
            // Below a 0.67 font scale the 10dp floor would exceed the 15sp maximum, which StepBased rejects.
            val minHostSize = with(LocalDensity.current) { 10.dp.toSp() }.let { if (it.value > 15f) 15.sp else it }
            // Its own text, so cutting the domain from the start can never hide the status. It follows the
            // system font scale up to 13sp and shrinks to the same floor as the domain when space runs out.
            if (state.interaction.unresponsive) {
                // Shares the row with the domain instead of taking all of it at large font scales.
                BasicText(
                    "未响应 · ",
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = if (minHostSize.value > 13f) 13.sp else minHostSize, maxFontSize = 13.sp, stepSize = 0.5.sp),
                    style = hostStyle,
                )
            }
            BasicText(
                // Checked first: a lookup before the list loads would latch OkHttp's failure for the process.
                if (BrowserPublicSuffix.ready) browserDisplayHost(uri.host.orEmpty()) else uri.host.orEmpty(),
                modifier = Modifier.weight(1f, fill = false).testTag("browser_address_host"),
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = minHostSize, maxFontSize = 15.sp, stepSize = 0.5.sp),
                style = hostStyle,
            )
        }
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

@Composable
private fun BrowserGlassCircle(
    icon: ToolBoxIconKey,
    label: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 52.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = if (enabled) ToolBoxThemeTokens.colors.textPrimary else ToolBoxThemeTokens.disabledContent
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.size(size)
            .toolBoxPressFeedback(interaction, enabled = enabled, shape = CircleShape)
            .shadow(10.dp, CircleShape, clip = false)
            .toolBoxSolidGlass(CircleShape)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        ToolBoxIcon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = tint)
    }
}
