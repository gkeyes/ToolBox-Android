package io.toolbox.host.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxRuntimeScaffold
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.runtime.RuntimeUiState
import io.toolbox.host.runtime.RuntimeViewModel

@Composable
internal fun RuntimeShellScreen(
    viewModel: RuntimeViewModel,
    onBack: () -> Unit,
    onPresentationReady: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is RuntimeUiState.Error) onPresentationReady()
        if ((state as? RuntimeUiState.Ready)?.mainEntryLoaded == true) onPresentationReady()
    }

    RuntimeExitConfirmation(onConfirm = onBack)
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
                RuntimeUiState.Loading -> RuntimeCenteredState("正在打开工具", "正在准备页面。")
                is RuntimeUiState.Error -> RuntimeErrorState(current.message, viewModel::retry)
                is RuntimeUiState.Ready -> {
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
                            viewModel.detached()
                        },
                    )
                }
            }
        }
    }
}

/** Confirms host-level Back without detaching or replacing the running WebView. */
@Composable
internal fun RuntimeExitConfirmation(onConfirm: () -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    BackHandler {
        if (!leaving) visible = !visible
    }
    if (visible) {
        // Runtime has no Miuix Scaffold popup host. Use a window above the retained WebView.
        Dialog(onDismissRequest = { visible = false }) {
            SurfaceCard {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AppText(
                        "返回 ToolBox？",
                        modifier = Modifier.semantics { heading() },
                        textStyle = ToolBoxThemeTokens.textStyles.title,
                    )
                    Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one))
                    AppText(
                        "即将离开当前小工具，返回 ToolBox。请确认需要保留的内容已保存。",
                        color = ToolBoxThemeTokens.colors.textSecondary,
                    )
                    Spacer(Modifier.height(ToolBoxThemeTokens.spacing.two))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one),
                    ) {
                        ToolBoxTextButton(
                            label = "继续使用",
                            onClick = { visible = false },
                            modifier = Modifier.weight(1f),
                            contentColor = ToolBoxThemeTokens.colors.textPrimary,
                        )
                        ToolBoxPrimaryButton(
                            label = "返回 ToolBox",
                            onClick = {
                                if (visible && !leaving) {
                                    visible = false
                                    leaving = true
                                    onConfirm()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun RuntimeShellPreviewContent() {
    ToolBoxRuntimeScaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(ToolBoxThemeTokens.colors.background),
    ) {
        RuntimeCenteredState("正在打开工具", "正在准备页面。")
    }
}

@Composable
private fun RuntimeCenteredState(title: String, detail: String) {
    Box(
        Modifier.fillMaxSize().padding(ToolBoxThemeTokens.spacing.twoHalf),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppText(
                title,
                textStyle = ToolBoxThemeTokens.textStyles.sectionTitle,
                weight = FontWeight.Bold,
                align = TextAlign.Center,
            )
            Spacer(Modifier.height(ToolBoxThemeTokens.spacing.compact))
            AppText(
                detail,
                textStyle = ToolBoxThemeTokens.textStyles.metadata,
                color = ToolBoxThemeTokens.colors.textSecondary,
                align = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RuntimeErrorState(message: String, onRetry: () -> Unit) {
    Box(
        Modifier.fillMaxSize().padding(ToolBoxThemeTokens.spacing.twoHalf),
        contentAlignment = Alignment.Center,
    ) {
        SurfaceCard {
            AppText(
                "工具暂时无法打开",
                textStyle = ToolBoxThemeTokens.textStyles.sectionTitle,
                weight = FontWeight.Bold,
                align = TextAlign.Center,
            )
            Spacer(Modifier.height(ToolBoxThemeTokens.spacing.compact))
            AppText(
                message,
                textStyle = ToolBoxThemeTokens.textStyles.metadata,
                color = ToolBoxThemeTokens.colors.textSecondary,
                align = TextAlign.Center,
            )
            Spacer(Modifier.height(ToolBoxThemeTokens.spacing.oneHalf))
            ToolBoxPrimaryButton("重试", onClick = onRetry, modifier = Modifier.fillMaxWidth())
        }
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
        SurfaceCard {
            AppText(
                if (loading) "正在打开本机工具目录" else "ToolBox 暂时无法启动",
                textStyle = ToolBoxThemeTokens.textStyles.sectionTitle,
                weight = FontWeight.Bold,
                align = TextAlign.Center,
            )
            Spacer(Modifier.height(ToolBoxThemeTokens.spacing.one))
            AppText(
                message,
                textStyle = ToolBoxThemeTokens.textStyles.metadata,
                color = ToolBoxThemeTokens.colors.textSecondary,
                align = TextAlign.Center,
            )
            if (!loading) {
                Spacer(Modifier.height(ToolBoxThemeTokens.spacing.oneHalf))
                ToolBoxPrimaryButton(
                    label = "重试",
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth().heightIn(min = ToolBoxThemeTokens.sizes.touchTarget),
                )
            }
        }
    }
}
