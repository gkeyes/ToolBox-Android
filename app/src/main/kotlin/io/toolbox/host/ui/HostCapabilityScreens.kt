package io.toolbox.host.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.ui.component.ToolBoxIconButton
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.runtime.RuntimeUiState
import io.toolbox.host.runtime.RuntimeViewModel
import io.toolbox.tool.runtime.HardenedRuntimeWebView
import io.toolbox.tool.runtime.RuntimeWebViewCallbacks
import io.toolbox.tool.runtime.RuntimeWebViewCreationResult

@Composable
fun RuntimeShellScreen(viewModel: RuntimeViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var activeWebView by remember { mutableStateOf<android.webkit.WebView?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ToolBoxThemeTokens.colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag(HostTestTags.RuntimeShell),
    ) {
        RuntimeControlRow(
            state = state,
            onBack = onBack,
            onReload = {
                val webView = activeWebView
                if (webView == null) viewModel.retry() else webView.reload()
            },
            onConfirmReady = viewModel::confirmReadyVersion,
        )
        when (val current = state) {
            RuntimeUiState.Loading -> RuntimeCenteredState("正在安全打开工具", "正在核对活动版本与入口文件。")
            is RuntimeUiState.Error -> RuntimeErrorState(current.message, viewModel::retry)
            is RuntimeUiState.Ready -> key(current.runtime.toolId, current.runtime.versionCode) {
                AndroidView(
                    factory = { context ->
                        when (val result = HardenedRuntimeWebView.create(
                            context = context,
                            runtime = current.runtime,
                            creationPermit = current.creationPermit,
                            callbacks = RuntimeWebViewCallbacks(
                                onMainEntryLoaded = { viewModel.mainEntryLoaded(current.runtime) },
                                onMainEntryFailed = viewModel::mainEntryFailed,
                                onRendererGone = viewModel::rendererGone,
                            ),
                        )) {
                            is RuntimeWebViewCreationResult.Created -> result.webView.also { webView ->
                                activeWebView = webView
                            }
                            is RuntimeWebViewCreationResult.Failed -> android.widget.FrameLayout(context).also {
                                viewModel.runtimeCreationFailed(result.message)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onRelease = { releasedView ->
                        (releasedView as? android.webkit.WebView)?.let { webView ->
                            if (activeWebView === webView) activeWebView = null
                            HardenedRuntimeWebView.release(webView)
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun RuntimeShellPreviewContent(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ToolBoxThemeTokens.colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        RuntimeControlRow(RuntimeUiState.Loading, onBack = onBack, onReload = {}, onConfirmReady = {})
        RuntimeCenteredState("安全运行容器", "实际运行时，工具 HTML 将占满这里的剩余空间。")
    }
}

@Composable
private fun RuntimeControlRow(
    state: RuntimeUiState,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onConfirmReady: () -> Unit,
) {
    val ready = state as? RuntimeUiState.Ready
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = ToolBoxThemeTokens.sizes.compactChrome)
            .background(ToolBoxThemeTokens.colors.surface)
            .padding(
                horizontal = ToolBoxThemeTokens.spacing.half,
                vertical = ToolBoxThemeTokens.spacing.half,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolBoxIconButton(ToolBoxIconKey.Back, "返回", onBack)
        Column(Modifier.weight(1f)) {
            AppText(
                ready?.runtime?.toolName ?: "工具运行",
                textStyle = ToolBoxThemeTokens.textStyles.body,
                weight = FontWeight.Bold,
                maxLines = 1,
            )
            AppText(
                text = if (ready == null) {
                    "ToolBox 原生 API 未启用"
                } else {
                    "独立 Origin · ${ready.statusMessage} · 原生 API 不可用"
                },
                textStyle = ToolBoxThemeTokens.textStyles.label,
                color = ToolBoxThemeTokens.colors.textSecondary,
                maxLines = 1,
            )
        }
        if (ready?.entryLoaded == true && ready.runtime.launchState == io.toolbox.core.data.LaunchState.PENDING) {
            ToolBoxIconButton(ToolBoxIconKey.Check, "确认工具可用", onConfirmReady)
        }
        ToolBoxIconButton(ToolBoxIconKey.Refresh, "重新加载工具", onReload)
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
