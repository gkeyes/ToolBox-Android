package io.toolbox.host.browser

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

/** Activity operations the browser UI triggers. Implemented by [BrowserActivity]. */
internal interface BrowserScreenActions {
    fun close()
    fun reload()
    fun performLoadAction()
    fun traverseHistory(forward: Boolean)
    fun share()
    fun openSystemBrowser()
    fun copyAddress()
    fun startMediaDiagnostics()
    fun copyMediaDiagnostics()
    fun changeUserAgentMode(mode: BrowserUserAgentMode)
    fun clearWebsiteData()
    fun restartUnresponsivePage()
    fun applySystemBarAppearance(light: Boolean)
}

@Composable
internal fun BrowserScreen(
    state: BrowserUiState,
    filters: BrowserFilterController,
    ssl: BrowserSslController,
    capabilities: BrowserCapabilityController,
    navigation: BrowserNavigationController,
    hasBrowserPrompt: () -> Boolean,
    actions: BrowserScreenActions,
) {
    val colors = ToolBoxThemeTokens.colors
    val lightSystemBars = colors.background.luminance() > 0.5f
    LaunchedEffect(lightSystemBars) {
        actions.applySystemBarAppearance(lightSystemBars)
    }
    val chromeLocked = hasBrowserPrompt() || state.fullScreenView != null || state.menu || state.mediaDiagnosticsSheet || state.userAgentSheet ||
        state.fullAddress || state.clearConfirmation || filters.sheet || filters.picker.active ||
        state.interaction.showRecoveryPrompt || state.error != null
    LaunchedEffect(chromeLocked) {
        if (chromeLocked) state.chrome.show()
    }
    val chromeVisible = !state.chrome.hidden || chromeLocked
    // Toolbars follow scroll direction with a critically damped spring: quick to respond,
    // retargets mid-flight without a jolt, and never overshoots into the page.
    val chromeShown by animateFloatAsState(
        targetValue = if (chromeVisible) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.001f),
        label = "browserChrome",
    )
    // The bars slide over the page on their own layers; the page is resized once per toggle
    // (at once when the bars return, after they are gone when they leave), never per frame.
    // The page runs to the top edge under the floating close button, as in the iOS 27 design.
    val reserveChrome by remember(chromeVisible) { derivedStateOf { chromeVisible || chromeShown > 0.001f } }
    var bottomBarHeight by remember { mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().clipToBounds()) {
            Box(
                Modifier.fillMaxSize().padding(
                    bottom = with(density) { if (reserveChrome) bottomBarHeight.toDp() else 0.dp },
                ),
            ) {
                state.webView?.let { page -> key(page) { AndroidView(factory = { page }, modifier = Modifier.fillMaxSize()) } }
                BrowserErrorOverlay(state, actions)
                BrowserLoadProgress(state)
            }
            if (reserveChrome) {
                if (state.fullScreenView == null) {
                    BrowserCloseButton(
                        actions,
                        Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 8.dp)
                            .graphicsLayer {
                                translationY = -(size.height + 8.dp.toPx()) * (1f - chromeShown)
                                alpha = chromeShown
                            },
                    )
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .onSizeChanged { bottomBarHeight = it.height }
                        .graphicsLayer { translationY = size.height * (1f - chromeShown) },
                ) {
                    BrowserFilterControls(filters, state.resumed)
                    if (state.fullScreenView == null) BrowserBottomBar(state, filters, actions)
                }
            }
        }
        state.fullScreenView?.let { view -> AndroidView(factory = { view }, modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) }
    }
    BrowserFilterSheet(
        filters,
        canPick = state.webView != null && state.error == null && !state.interaction.loading && !state.interaction.unresponsive && !state.clearing,
        resumed = state.resumed,
    )
    if (state.menu) BrowserMenuSheet(state, actions)
    if (state.mediaDiagnosticsSheet) BrowserMediaDiagnosticsSheet(state, actions)
    if (state.userAgentSheet) BrowserUserAgentSheet(state, actions)
    if (state.fullAddress) BrowserAddressSheet(state, actions)
    if (state.resumed) {
        ssl.Prompt()
        capabilities.Prompt(visible = !ssl.hasPrompt)
        if (!ssl.hasPrompt && !capabilities.hasPrompt) navigation.Prompt()
    }
    if (state.clearConfirmation) BrowserClearDataDialog(state, actions)
    // Avoid stacked dialogs and background-window prompts; a recovery callback removes this immediately.
    if (state.interaction.showRecoveryPrompt && state.resumed && !hasBrowserPrompt() && !state.menu && !state.mediaDiagnosticsSheet && !state.userAgentSheet &&
        !state.fullAddress && !state.clearConfirmation && !filters.sheet && !filters.picker.active) {
        BrowserRecoveryDialog(state, actions)
    }
}

@Composable
private fun BrowserLoadProgress(state: BrowserUiState) {
    val colors = ToolBoxThemeTokens.colors
    Box(Modifier.fillMaxWidth().height(2.dp)) {
        val showProgress = state.interaction.loading && state.loadProgress in 0..99
        // Progress glides between WebView updates instead of jumping in steps.
        val progress = androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (showProgress) (state.loadProgress / 100f).coerceAtLeast(0.02f) else 0f,
            animationSpec = io.toolbox.core.ui.component.ToolBoxMotion.effects(),
            label = "browser load progress",
        )
        if (showProgress) {
            Box(Modifier.fillMaxSize().drawBehind {
                val filled = size.width * progress.value.coerceIn(0f, 1f)
                val start = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - filled else 0f
                drawRect(colors.primary, topLeft = androidx.compose.ui.geometry.Offset(start, 0f), size = size.copy(width = filled))
            })
        }
    }
}

@Composable
private fun BrowserErrorOverlay(state: BrowserUiState, actions: BrowserScreenActions) {
    val colors = ToolBoxThemeTokens.colors
    androidx.compose.animation.AnimatedVisibility(
        visible = state.error != null,
        enter = androidx.compose.animation.fadeIn(io.toolbox.core.ui.component.ToolBoxMotion.effects()),
        exit = androidx.compose.animation.fadeOut(io.toolbox.core.ui.component.ToolBoxMotion.effects()),
    ) {
        // Keep the last message while fading out after a successful reload.
        val message = remember { mutableStateOf("") }.also { last -> state.error?.let { last.value = it } }.value
        Column(
            Modifier.fillMaxSize().background(colors.background)
                .padding(horizontal = ToolBoxThemeTokens.spacing.md, vertical = ToolBoxThemeTokens.spacing.lg)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            io.toolbox.core.ui.component.ToolBoxStatusState(
                title = "无法打开此网页",
                message = message,
                icon = ToolBoxIconKey.Globe,
                tone = io.toolbox.core.ui.component.ToolBoxStatusTone.Error,
            )
            Column(
                Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.xs),
            ) {
                io.toolbox.core.ui.component.ToolBoxPrimaryButton(
                    "重新加载", actions::reload, Modifier.fillMaxWidth(), enabled = !state.clearing && !state.interaction.restarting,
                )
                ToolBoxTextButton("使用系统浏览器", actions::openSystemBrowser, Modifier.fillMaxWidth(), enabled = !state.clearing, outlined = false)
            }
        }
    }
}
