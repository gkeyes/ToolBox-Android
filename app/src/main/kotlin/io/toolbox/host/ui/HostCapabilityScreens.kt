package io.toolbox.host.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.toolbox.core.ui.component.ToolBoxBusyIndicator
import io.toolbox.core.ui.component.ToolBoxIcon
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxModalDialog
import io.toolbox.core.ui.component.ToolBoxMotion
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxRuntimeScaffold
import io.toolbox.core.ui.component.ToolBoxSecondaryButton
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.component.ToolBoxWindowGlass
import io.toolbox.core.ui.component.rememberToolBoxWindowGlass
import io.toolbox.core.ui.component.toolBoxWindowGlassEffect
import io.toolbox.core.ui.component.toolBoxShape
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.runtime.RuntimeCloseUiState
import io.toolbox.host.runtime.RuntimeUiState
import io.toolbox.host.runtime.RuntimeViewModel
import kotlinx.coroutines.delay
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * A running mini-tool is fullscreen and draws everything itself: the host adds no title bar,
 * buttons or other chrome. The only host surfaces are the loading / error states before the page
 * is ready, the transient "back again to leave" capsule, and the save-before-exit sheet.
 */
@Composable
internal fun RuntimeShellScreen(
    viewModel: RuntimeViewModel,
    onBack: () -> Unit,
    onPresentationReady: () -> Unit,
    toolName: String?,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val closeState by viewModel.closeState.collectAsStateWithLifecycle()
    val exitRevision by viewModel.exitRevision.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is RuntimeUiState.Error) onPresentationReady()
        if ((state as? RuntimeUiState.Ready)?.mainEntryLoaded == true) onPresentationReady()
    }
    val ready = state as? RuntimeUiState.Ready
    ApplyRetainedRuntimeManifestUi(ready?.runtime?.installedManifest?.ui)
    val edgeToEdge = ready?.runtime?.installedManifest?.ui?.edgeToEdge == true
    if (ready != null && edgeToEdge) PublishRuntimeSafeAreaTop(ready.webView, ready.runtime.origin)

    closeState?.let { saving ->
        key(exitRevision) {
            RuntimeSaveDialog(saving, viewModel::retryClose, viewModel::cancelClose, viewModel::discardAndClose)
        }
    }
    val immersiveNavigationBar = isHyperOsGestureNavigation(LocalContext.current)
    ToolBoxRuntimeScaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(ToolBoxThemeTokens.colors.background)
            .testTag(HostTestTags.RuntimeShell),
        immersiveNavigationBar = immersiveNavigationBar,
        immersiveStatusBar = edgeToEdge,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            when (val current = state) {
                RuntimeUiState.Loading -> RuntimeCenteredState(runtimeLoadingTitle(toolName), "正在准备页面。")
                is RuntimeUiState.Error -> RuntimeErrorState(current.message, viewModel::retry)
                is RuntimeUiState.Ready -> key(current.webView) {
                    AndroidView(
                        factory = { context ->
                            android.widget.FrameLayout(context).also { container ->
                                (current.webView.parent as? android.view.ViewGroup)?.removeView(current.webView)
                                container.addView(
                                    current.webView,
                                    android.widget.FrameLayout.LayoutParams(
                                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { releasedView ->
                            (releasedView as? android.view.ViewGroup)?.removeAllViews()
                            viewModel.detached(current.webView)
                        },
                    )
                }
            }
            // Drawn above the page; it takes no touches, so the tool keeps every tap.
            key(exitRevision) {
                RuntimeExitConfirmation(
                    onConfirm = { viewModel.requestExit(onBack) },
                    toolName = toolName,
                    askPage = ready?.webView?.let { webView -> { answer: (Boolean) -> Unit -> askPageToHandleBack(webView, answer) } },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
                )
            }
        }
    }
}

/**
 * An edge-to-edge tool draws under the status bar, so it is told the bar's height as the CSS variable
 * `--toolbox-safe-area-top` (CSS px equal dp at the runtime's 100% zoom). A document-start script
 * sets it in every document the tool loads (reloads, other local pages); the current document is set
 * directly. Both are replaced whenever the inset changes.
 */
@Composable
private fun PublishRuntimeSafeAreaTop(webView: android.webkit.WebView, origin: String) {
    val density = LocalDensity.current
    val topPx = WindowInsets.statusBars.union(WindowInsets.displayCutout).getTop(density)
    val topDp = with(density) { topPx.toDp().value }
    DisposableEffect(webView, origin, topDp) {
        val value = String.format(java.util.Locale.ROOT, "%.2fpx", topDp)
        val script = "document.documentElement.style.setProperty('--toolbox-safe-area-top','$value')"
        val handler = if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(origin.removeSuffix("/"))) }.getOrNull()
        } else {
            null
        }
        webView.evaluateJavascript(script, null)
        onDispose { runCatching { handler?.remove() } }
    }
}

/**
 * Save-before-exit (or before reload): a glass sheet that waits for the tool's own save. Title and
 * activity indicator share the first line; a slow save adds an indeterminate bar and the choices to
 * keep waiting or to leave without saving. Capsule roles follow iOS 26: retry is prominent, keep
 * waiting is tinted, cancel is neutral and discarding is a solid destructive confirmation.
 */
@Composable
internal fun RuntimeSaveDialog(
    saving: RuntimeCloseUiState,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDiscard: () -> Unit,
) {
    var waiting by remember(saving.reloading) { mutableStateOf(false) }
    val colors = ToolBoxThemeTokens.colors
    val failed = saving.error != null
    ToolBoxModalDialog(onDismissRequest = onCancel) {
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize(ToolBoxMotion.spatial())
                .padding(horizontal = 4.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppText(
                    saving.error ?: if (waiting) "正在等待保存完成" else if (saving.slow) "仍在保存" else "正在保存",
                    modifier = Modifier.weight(1f),
                    textStyle = ToolBoxThemeTokens.textStyles.detailTitle.copy(
                        fontSize = 20.sp,
                        lineHeight = 25.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                if (failed) RuntimeSaveFailedBadge() else ToolBoxBusyIndicator()
            }
            Spacer(Modifier.height(6.dp))
            AppText(
                if (failed) "页面已保留。" else "保存完成后会自动${if (saving.reloading) "重载" else "退出"}。",
                modifier = Modifier.padding(horizontal = 4.dp),
                color = colors.textSecondary,
                textStyle = ToolBoxThemeTokens.textStyles.metadata.copy(fontSize = 15.sp, lineHeight = 20.sp),
            )
            if (saving.slow && !failed) {
                Spacer(Modifier.height(14.dp))
                RuntimeIndeterminateBar(Modifier.fillMaxWidth().padding(horizontal = 4.dp))
            }
            Spacer(Modifier.height(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (failed) ToolBoxPrimaryButton("重试保存", onRetry, Modifier.fillMaxWidth())
                if (saving.slow && !waiting && !failed) {
                    ToolBoxSecondaryButton("继续等待", { waiting = true }, Modifier.fillMaxWidth())
                }
                ToolBoxTextButton(
                    if (saving.reloading) "取消重载" else "取消退出",
                    onCancel,
                    Modifier.fillMaxWidth(),
                    contentColor = colors.textPrimary,
                )
                if (saving.slow || failed) ToolBoxPrimaryButton(
                    if (saving.reloading) "放弃保存并重载" else "放弃保存并退出",
                    onDiscard,
                    Modifier.fillMaxWidth(),
                    destructive = true,
                )
            }
        }
    }
}

/** Solid danger disc with an exclamation mark, in place of the activity indicator. */
@Composable
private fun RuntimeSaveFailedBadge() {
    val danger = ToolBoxThemeTokens.colors.danger
    Canvas(Modifier.size(30.dp).clearAndSetSemantics { }) {
        drawCircle(Brush.linearGradient(listOf(lerp(danger, Color.White, 0.25f), danger)))
        val stroke = 2.8.dp.toPx()
        val x = size.width / 2f
        drawLine(Color.White, Offset(x, size.height * 0.27f), Offset(x, size.height * 0.58f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(Color.White, radius = stroke * 0.62f, center = Offset(x, size.height * 0.74f))
    }
}

/**
 * Indeterminate 4dp capsule: an accent segment sweeps across a neutral track. Neither saving nor
 * startup reports a measurable percentage, so the bar never pretends to.
 */
@Composable
private fun RuntimeIndeterminateBar(modifier: Modifier = Modifier) {
    val colors = ToolBoxThemeTokens.colors
    val dark = colors.surface.luminance() < 0.5f
    val track = Color(0xFF767680).copy(alpha = if (dark) 0.24f else 0.12f)
    val accent = colors.primary
    val transition = rememberInfiniteTransition(label = "runtime-indeterminate")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing)),
        label = "runtime-indeterminate-sweep",
    )
    Box(
        modifier
            .height(4.dp)
            .progressSemantics()
            .clip(CircleShape)
            .background(track)
            .drawBehind {
                val segment = (size.width * 0.34f).coerceAtLeast(46.dp.toPx())
                val start = -segment + (size.width + segment) * sweep
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        listOf(accent.copy(alpha = 0f), accent, accent.copy(alpha = 0f)),
                        startX = start,
                        endX = start + segment,
                    ),
                    topLeft = Offset(start, 0f),
                    size = Size(segment, size.height),
                    cornerRadius = CornerRadius(size.height / 2f),
                )
            },
    )
}

@Composable
internal fun RuntimeExitConfirmation(onConfirm: () -> Unit) = RuntimeExitConfirmation(onConfirm, toolName = null)

/**
 * Back goes to the page first: the host dispatches a cancelable `toolboxback` event on the page's
 * window, and a page with something to close (a panel, a dialog) calls `preventDefault()` on it.
 * [answer] gets true when the page took it. A page that doesn't listen, or doesn't answer in time,
 * gets the usual exit confirmation.
 */
internal fun askPageToHandleBack(webView: android.webkit.WebView, answer: (Boolean) -> Unit) {
    var answered = false
    val reply = { handled: Boolean ->
        if (!answered) {
            answered = true
            answer(handled)
        }
    }
    webView.evaluateJavascript(RUNTIME_PAGE_BACK_SCRIPT) { result -> reply(result == "true") }
    webView.postDelayed({ reply(false) }, RUNTIME_PAGE_BACK_TIMEOUT_MS)
}

private const val RUNTIME_PAGE_BACK_SCRIPT =
    "(function(){try{var e=new Event('toolboxback',{cancelable:true});return !window.dispatchEvent(e);}catch(_){return false;}})()"

/** A page busy for longer than this is treated as not handling Back, so Back never feels dead. */
private const val RUNTIME_PAGE_BACK_TIMEOUT_MS = 400L

/** Backs in a row the page may take within the exit window before the host takes the next one. */
private const val PAGE_BACK_STREAK = 2

/**
 * Back is offered to the page first through [askPage] (see [askPageToHandleBack]); only a Back the
 * page leaves alone counts here. The first such Back is consumed and only shows a short hint. A
 * second one within the confirmation window leaves the tool. The running WebView is untouched
 * until that second Back, so an accidental edge swipe never detaches the page.
 *
 * The hint is a floating glass capsule over the tool (iOS 27 toast); it takes no touches and
 * disappears when the confirmation window closes.
 */
@Composable
internal fun RuntimeExitConfirmation(
    onConfirm: () -> Unit,
    toolName: String?,
    modifier: Modifier = Modifier,
    askPage: ((answer: (Boolean) -> Unit) -> Unit)? = null,
) {
    val hintText = remember(toolName) { runtimeExitHint(toolName) }
    var lastBackAt by remember { mutableLongStateOf(0L) }
    var leaving by remember { mutableStateOf(false) }
    var hintRevision by remember { mutableIntStateOf(0) }
    var hintVisible by remember { mutableStateOf(false) }
    val hostView = LocalView.current
    val glass = rememberToolBoxWindowGlass(hostView, active = false)
    val realBlur = ToolBoxThemeTokens.materials.realBlurEnabled

    LaunchedEffect(hintRevision) {
        if (hintRevision == 0) return@LaunchedEffect
        // Copy the page before the capsule shows: the capsule refracts that copy.
        if (realBlur && !glass.ready) glass.capture(hostView)
        hintVisible = true
        // The confirmation window started at the Back press, not after the copy: hide on time.
        delay((RUNTIME_EXIT_BACK_WINDOW_MS - (SystemClock.elapsedRealtime() - lastBackAt)).coerceAtLeast(0L))
        hintVisible = false
        delay(400L)
        if (!hintVisible) glass.release()
    }

    // While the page is deciding, one further Back waits for its answer instead of being lost.
    var askingPage by remember { mutableStateOf(false) }
    var pendingBackAt by remember { mutableLongStateOf(0L) }
    // When the page took the last two Backs within the window, the next one goes to the host, so a
    // page that cancels every Back can't trap the user without a way out.
    val pageTookBackAt = remember { LongArray(PAGE_BACK_STREAK) }
    var disposed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { disposed = true } }
    val currentAskPage by rememberUpdatedState(askPage)
    fun exitBack(now: Long) {
        if (leaving || disposed) return
        if (shouldExitRuntimeOnBack(lastBackAt, now)) {
            lastBackAt = 0L
            leaving = true
            hintVisible = false
            onConfirm()
        } else {
            lastBackAt = now
            hintRevision++
        }
    }

    fun offerBack(now: Long) {
        val ask = currentAskPage
        val pageKeepsBack = pageTookBackAt.all { it != 0L && now - it <= RUNTIME_EXIT_BACK_WINDOW_MS }
        // While the exit hint shows, the next Back confirms it rather than going to the page again.
        val hintShowing = shouldExitRuntimeOnBack(lastBackAt, now)
        if (ask == null || pageKeepsBack || hintShowing) {
            pageTookBackAt.fill(0L)
            exitBack(now)
            return
        }
        askingPage = true
        ask { handled ->
            if (disposed) return@ask
            askingPage = false
            if (handled) {
                pageTookBackAt.copyInto(pageTookBackAt, 0, 1)
                pageTookBackAt[PAGE_BACK_STREAK - 1] = now
            } else {
                pageTookBackAt.fill(0L)
                exitBack(now)
            }
            val pending = pendingBackAt
            if (pending != 0L) {
                pendingBackAt = 0L
                if (!leaving) offerBack(pending)
            }
        }
    }

    BackHandler(enabled = !leaving) {
        // The press time, not the answer time, counts towards the confirmation window.
        val now = SystemClock.elapsedRealtime()
        if (askingPage) pendingBackAt = now else offerBack(now)
    }

    AnimatedVisibility(
        visible = hintVisible,
        modifier = modifier,
        enter = fadeIn() + scaleIn(initialScale = 0.9f) + slideInVertically { it / 2 },
        exit = fadeOut() + scaleOut(targetScale = 0.96f),
    ) {
        RuntimeExitHintCapsule(hintText, glass)
    }
}

@Composable
private fun RuntimeExitHintCapsule(text: String, glass: ToolBoxWindowGlass) {
    val colors = ToolBoxThemeTokens.colors
    val materials = ToolBoxThemeTokens.materials
    val dark = colors.surface.luminance() < 0.5f
    val shape = CircleShape
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .heightIn(min = 50.dp)
            .shadow(
                elevation = 18.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = if (dark) 0.55f else 0.18f),
                spotColor = Color.Black.copy(alpha = if (dark) 0.55f else 0.18f),
            )
            .clip(shape)
            .toolBoxWindowGlassEffect(glass, 25.dp)
            .border(0.5.dp, materials.overlayBorder, shape)
            .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(lerp(colors.primary, Color.White, 0.28f), colors.primary))),
            contentAlignment = Alignment.Center,
        ) {
            ToolBoxIcon(ToolBoxIconKey.Back, null, Modifier.size(16.dp), tint = Color.White)
        }
        AppText(
            text,
            maxLines = 2,
            textStyle = ToolBoxThemeTokens.textStyles.headline.copy(fontSize = 15.sp, lineHeight = 20.sp),
        )
    }
}

@Composable
internal fun RuntimeShellPreviewContent() = RuntimeShellPreviewContent(toolName = null)

@Composable
internal fun RuntimeShellPreviewContent(toolName: String?) {
    ToolBoxRuntimeScaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(ToolBoxThemeTokens.colors.background),
        immersiveNavigationBar = isHyperOsGestureNavigation(LocalContext.current),
    ) {
        RuntimeCenteredState(runtimeLoadingTitle(toolName), "正在准备页面。")
    }
}

@Composable
private fun RuntimeCenteredState(title: String, detail: String) {
    Box(
        Modifier.fillMaxSize().padding(ToolBoxThemeTokens.spacing.twoHalf),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally) {
            // The runtime exposes readiness, not a measurable percentage. Do not invent progress.
            Box(Modifier.size(ToolBoxThemeTokens.sizes.emptyStateIcon).progressSemantics(), contentAlignment = Alignment.Center) {
                ToolBoxBusyIndicator()
            }
            Spacer(Modifier.height(ToolBoxThemeTokens.spacing.oneHalf))
            AppText(
                title,
                modifier = Modifier.fillMaxWidth(),
                textStyle = ToolBoxThemeTokens.textStyles.headline,
                align = TextAlign.Center,
                maxLines = 2,
            )
            Spacer(Modifier.height(ToolBoxThemeTokens.spacing.xxs))
            AppText(
                detail,
                modifier = Modifier.fillMaxWidth(),
                textStyle = ToolBoxThemeTokens.textStyles.metadata.copy(fontSize = 15.sp, lineHeight = 20.sp),
                color = ToolBoxThemeTokens.colors.textSecondary,
                align = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RuntimeErrorState(message: String, onRetry: () -> Unit) {
    val presentation = remember(message) { runtimeErrorPresentation(message) }
    Box(
        Modifier.fillMaxSize().padding(ToolBoxThemeTokens.spacing.twoHalf),
        contentAlignment = Alignment.Center,
    ) {
        HostStatusCard(
            title = "工具暂时无法打开",
            message = presentation.summary,
            onRetry = onRetry,
            details = presentation.details,
            mark = { RuntimeErrorMark() },
        )
    }
}

@Composable
fun HostBootstrapScreen(loading: Boolean, message: String, onRetry: () -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ToolBoxThemeTokens.colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val roomForFooter = maxHeight >= 480.dp
        Box(
            Modifier
                .fillMaxSize()
                .padding(ToolBoxThemeTokens.spacing.twoHalf)
                .padding(bottom = if (roomForFooter) 32.dp else 0.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Startup and recovery warnings remain fully visible, never folded as runtime diagnostics.
            HostStatusCard(
                title = if (loading) "正在打开本机工具目录" else "ToolBox 暂时无法启动",
                message = message,
                onRetry = onRetry.takeUnless { loading },
                loading = loading,
                mark = { ToolBoxStartupMark(error = !loading) },
            )
        }
        if (roomForFooter) {
            AppText(
                "ToolBox",
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).clearAndSetSemantics { },
                color = ToolBoxThemeTokens.colors.textTertiary,
                textStyle = ToolBoxThemeTokens.textStyles.label.copy(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                ),
            )
        }
    }
}

/**
 * Centered status (iOS 27 launch / recovery): a mark, the title, a short explanation and, for
 * errors, a prominent retry capsule. No card; the content sits directly on the grouped background.
 */
@Composable
private fun HostStatusCard(
    title: String,
    message: String,
    onRetry: (() -> Unit)?,
    details: String? = null,
    loading: Boolean = false,
    mark: @Composable () -> Unit,
) {
    // Short states stay centered; long errors remain reachable in landscape and at large font sizes.
    Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState())
        .testTag("host_status_scroll")) {
        Column(
            Modifier.fillMaxWidth().testTag("host_status_card"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            mark()
            Spacer(Modifier.height(ToolBoxThemeTokens.spacing.md))
            AppText(
                title,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                textStyle = if (loading) {
                    ToolBoxThemeTokens.textStyles.headline
                } else {
                    ToolBoxThemeTokens.textStyles.detailTitle.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
                },
                align = TextAlign.Center,
            )
            if (message.isNotBlank()) {
                Spacer(Modifier.height(ToolBoxThemeTokens.spacing.xs))
                AppText(
                    message,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = ToolBoxThemeTokens.spacing.xs),
                    textStyle = ToolBoxThemeTokens.textStyles.metadata.copy(fontSize = 15.sp, lineHeight = 21.sp),
                    color = ToolBoxThemeTokens.colors.textSecondary,
                    align = TextAlign.Center,
                )
            }
            if (loading) {
                Spacer(Modifier.height(ToolBoxThemeTokens.spacing.md))
                RuntimeIndeterminateBar(Modifier.width(132.dp))
            }
            if (onRetry != null) {
                Spacer(Modifier.height(18.dp))
                ToolBoxPrimaryButton(
                    label = "重试",
                    onClick = onRetry,
                    modifier = Modifier.widthIn(min = 220.dp).heightIn(min = ToolBoxThemeTokens.sizes.touchTarget),
                )
            }
            details?.let { fullMessage ->
                var expanded by rememberSaveable(fullMessage) { mutableStateOf(false) }
                Spacer(Modifier.height(ToolBoxThemeTokens.spacing.xs))
                ToolBoxTextButton(
                    label = if (expanded) "收起错误详情" else "查看错误详情",
                    onClick = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth().testTag("runtime_error_details").semantics {
                        stateDescription = if (expanded) "已展开" else "已收起"
                    },
                    outlined = false,
                )
                AnimatedVisibility(expanded, enter = ToolBoxMotion.ExpandIn, exit = ToolBoxMotion.CollapseOut) {
                    SelectionContainer {
                        AppText(fullMessage, modifier = Modifier.fillMaxWidth(),
                            textStyle = ToolBoxThemeTokens.textStyles.metadata,
                            color = ToolBoxThemeTokens.colors.textSecondary)
                    }
                }
            }
        }
    }
}

/** Runtime failure: a soft danger disc with the retry glyph. */
@Composable
private fun RuntimeErrorMark() {
    Box(
        Modifier.size(ToolBoxThemeTokens.sizes.emptyStateIcon).clip(CircleShape).background(ToolBoxThemeTokens.colors.softDanger),
        contentAlignment = Alignment.Center,
    ) {
        ToolBoxIcon(ToolBoxIconKey.Refresh, null, tint = ToolBoxThemeTokens.colors.onSoftDanger)
    }
}

/**
 * ToolBox launch mark: the app tile (blue gradient, four rounded squares) resting on a glass disc
 * with a soft glow. On failure the tile desaturates, the glow turns red and a retry badge appears.
 */
@Composable
private fun ToolBoxStartupMark(error: Boolean, tileSize: Dp = 104.dp) {
    val colors = ToolBoxThemeTokens.colors
    val materials = ToolBoxThemeTokens.materials
    val discSize = tileSize * (168f / 104f)
    val glowSize = tileSize * (260f / 104f)
    val glowAlpha = if (error) {
        1f
    } else {
        val pulse = rememberInfiniteTransition(label = "startup-glow")
        val value by pulse.animateFloat(
            initialValue = 0.7f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Reverse),
            label = "startup-glow-alpha",
        )
        value
    }
    val glow = if (error) Color(0xFFFF6961) else Color(0xFF4A9BFF)
    // The disc is glass over the glow: the glow is recorded as its backdrop.
    val glassLive = materials.realBlurEnabled && LocalView.current.isHardwareAccelerated
    val glowBackdrop = rememberLayerBackdrop()
    val discTint = materials.glassTint
    Box(Modifier.size(discSize).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .requiredSize(glowSize)
                .then(if (glassLive) Modifier.layerBackdrop(glowBackdrop) else Modifier)
                .graphicsLayer { alpha = glowAlpha }
                .drawBehind {
                    drawCircle(
                        Brush.radialGradient(
                            0f to glow.copy(alpha = if (error) 0.30f else 0.50f),
                            0.6f to glow.copy(alpha = if (error) 0.10f else 0.18f),
                            1f to glow.copy(alpha = 0f),
                        ),
                    )
                },
        )
        Box(
            Modifier
                .size(discSize)
                .then(
                    if (glassLive) {
                        Modifier.drawBackdrop(
                            backdrop = glowBackdrop,
                            shape = { CircleShape },
                            effects = {
                                vibrancy()
                                blur(6.dp.toPx())
                                lens(16.dp.toPx(), 32.dp.toPx())
                            },
                            onDrawSurface = { drawRect(discTint) },
                        )
                    } else {
                        Modifier.clip(CircleShape).background(discTint)
                    },
                )
                .border(0.75.dp, materials.glassBorder, CircleShape),
        )
        Box(Modifier.size(tileSize)) {
            val tileShape = toolBoxShape(tileSize * (28f / 104f))
            val desaturate = if (error) 0.45f else 0f
            val gray = Color(0xFF8E8E93)
            val top = lerp(Color(0xFF5AA8FF), gray, desaturate)
            val middle = lerp(Color(0xFF0A5BD6), gray, desaturate)
            val bottom = lerp(Color(0xFF0846A8), gray, desaturate)
            Canvas(
                Modifier
                    .fillMaxSize()
                    .shadow(
                        elevation = 16.dp,
                        shape = tileShape,
                        clip = false,
                        ambientColor = middle,
                        spotColor = middle,
                    )
                    .clip(tileShape),
            ) {
                drawRect(Brush.linearGradient(0f to top, 0.7f to middle, 1f to bottom, start = Offset(size.width * 0.3f, 0f), end = Offset(size.width * 0.7f, size.height)))
                drawRect(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0f)), endY = size.height / 2f),
                    size = Size(size.width, size.height / 2f),
                )
                // 24-unit glyph scaled to 56/104 of the tile, centred.
                val unit = size.width * (56f / 104f) / 24f
                val origin = (size.width - unit * 24f) / 2f
                fun cell(x: Float, y: Float, radius: Float, alpha: Float) = drawRoundRect(
                    color = Color.White.copy(alpha = alpha),
                    topLeft = Offset(origin + x * unit, origin + y * unit),
                    size = Size(8f * unit, 8f * unit),
                    cornerRadius = CornerRadius(radius * unit),
                )
                cell(3f, 3f, 2.4f, 1f)
                cell(13f, 3f, 2.4f, 0.62f)
                cell(3f, 13f, 2.4f, 0.62f)
                cell(13f, 13f, 4f, 0.9f)
            }
            if (error) {
                val badge = tileSize * (38f / 104f)
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 10.dp, y = 10.dp)
                        .size(badge + 8.dp)
                        .clip(CircleShape)
                        .background(colors.background)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(colors.danger),
                    contentAlignment = Alignment.Center,
                ) {
                    ToolBoxIcon(ToolBoxIconKey.Refresh, null, Modifier.size(badge * 0.5f), tint = Color.White)
                }
            }
        }
    }
}
