package io.toolbox.host.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.toolbox.core.ui.component.ToolBoxBusyIndicator
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.component.ToolBoxRuntimeScaffold
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.runtime.RuntimeUiState
import io.toolbox.host.runtime.RuntimeViewModel

/** Keep the existing call signature, including positional and trailing-lambda callers. */
@Composable
internal fun RuntimeShellScreen(
    viewModel: RuntimeViewModel,
    onBack: () -> Unit,
    onPresentationReady: () -> Unit,
) = RuntimeShellScreen(viewModel, onBack, onPresentationReady, toolName = null)

@Composable
internal fun RuntimeShellScreen(
    viewModel: RuntimeViewModel,
    onBack: () -> Unit,
    onPresentationReady: () -> Unit,
    toolName: String?,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is RuntimeUiState.Error) onPresentationReady()
        if ((state as? RuntimeUiState.Ready)?.mainEntryLoaded == true) onPresentationReady()
    }

    RuntimeExitConfirmation(onConfirm = onBack, toolName = toolName)
    ToolBoxRuntimeScaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(ToolBoxThemeTokens.colors.background)
            .testTag(HostTestTags.RuntimeShell),
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
        }
    }
}

@Composable
internal fun RuntimeExitConfirmation(onConfirm: () -> Unit) = RuntimeExitConfirmation(onConfirm, toolName = null)

/**
 * The first host-level Back is consumed and only shows a short hint. A second Back
 * within the confirmation window leaves the tool. The running WebView is untouched
 * until that second Back, so an accidental edge swipe never detaches the page.
 */
@Composable
internal fun RuntimeExitConfirmation(onConfirm: () -> Unit, toolName: String?) {
    val context = LocalContext.current
    val hintText = remember(toolName) { runtimeExitHint(toolName) }
    val exitHint = remember(context, hintText) {
        Toast.makeText(context.applicationContext, hintText, Toast.LENGTH_SHORT)
    }
    var lastBackAt by remember { mutableStateOf(0L) }
    var leaving by remember { mutableStateOf(false) }

    DisposableEffect(exitHint) {
        onDispose { exitHint.cancel() }
    }

    BackHandler(enabled = !leaving) {
        val now = SystemClock.elapsedRealtime()
        if (shouldExitRuntimeOnBack(lastBackAt, now)) {
            lastBackAt = 0L
            leaving = true
            exitHint.cancel()
            onConfirm()
        } else {
            lastBackAt = now
            exitHint.cancel()
            exitHint.show()
        }
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
            Box(Modifier.size(24.dp).progressSemantics(), contentAlignment = Alignment.Center) {
                ToolBoxBusyIndicator()
            }
            Spacer(Modifier.height(ToolBoxThemeTokens.spacing.oneHalf))
            AppText(
                title,
                modifier = Modifier.fillMaxWidth(),
                textStyle = ToolBoxThemeTokens.textStyles.sectionTitle,
                weight = FontWeight.Bold,
                align = TextAlign.Center,
            )
            Spacer(Modifier.height(ToolBoxThemeTokens.spacing.compact))
            AppText(
                detail,
                modifier = Modifier.fillMaxWidth(),
                textStyle = ToolBoxThemeTokens.textStyles.metadata,
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
        HostStatusCard("工具暂时无法打开", presentation.summary, onRetry, presentation.details)
    }
}

@Composable
fun HostBootstrapScreen(loading: Boolean, message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ToolBoxThemeTokens.colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(ToolBoxThemeTokens.spacing.twoHalf),
        contentAlignment = Alignment.Center,
    ) {
        // Startup and recovery warnings remain fully visible, never folded as runtime diagnostics.
        HostStatusCard(
            if (loading) "正在打开本机工具目录" else "ToolBox 暂时无法启动",
            message, onRetry.takeUnless { loading },
        )
    }
}

@Composable
private fun HostStatusCard(title: String, message: String, onRetry: (() -> Unit)?, details: String? = null) {
    // Short states stay centered; long errors remain reachable in landscape and at large font sizes.
    Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState())
        .testTag("host_status_scroll")) {
        SurfaceCard(Modifier.testTag("host_status_card")) {
            AppText(
                title,
                modifier = Modifier.fillMaxWidth(),
                textStyle = ToolBoxThemeTokens.textStyles.sectionTitle,
                weight = FontWeight.Bold,
                align = TextAlign.Center,
            )
            Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one))
            AppText(
                message,
                modifier = Modifier.fillMaxWidth(),
                textStyle = ToolBoxThemeTokens.textStyles.metadata,
                color = ToolBoxThemeTokens.colors.textSecondary,
                align = TextAlign.Center,
            )
            if (onRetry != null) {
                Spacer(Modifier.height(ToolBoxThemeTokens.spacing.oneHalf))
                ToolBoxPrimaryButton(
                    label = "重试",
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth().heightIn(min = ToolBoxThemeTokens.sizes.touchTarget),
                )
            }
            details?.let { fullMessage ->
                var expanded by rememberSaveable(fullMessage) { mutableStateOf(false) }
                ToolBoxTextButton(
                    label = if (expanded) "收起错误详情" else "查看错误详情",
                    onClick = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth().testTag("runtime_error_details").semantics {
                        stateDescription = if (expanded) "已展开" else "已收起"
                    },
                    outlined = false,
                )
                if (expanded) SelectionContainer {
                    AppText(fullMessage, modifier = Modifier.fillMaxWidth(),
                        textStyle = ToolBoxThemeTokens.textStyles.metadata,
                        color = ToolBoxThemeTokens.colors.textSecondary)
                }
            }
        }
    }
}
