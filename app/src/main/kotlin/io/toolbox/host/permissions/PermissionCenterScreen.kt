package io.toolbox.host.permissions

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.ui.component.ToolBoxAppScaffold
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxSwitchSettingRow
import io.toolbox.core.ui.component.ToolBoxTopBar
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.ui.AppText
import io.toolbox.host.ui.HostTestTags
import io.toolbox.host.ui.SurfaceCard

@Composable
internal fun PermissionCenterScreen(viewModel: PermissionCenterViewModel, onBack: () -> Unit) {
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
    ToolBoxAppScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            ToolBoxTopBar(
                title = "${state.toolName}权限",
                navigationIcon = ToolBoxIconKey.Back,
                onNavigationClick = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ToolBoxThemeTokens.spacing.two,
                top = padding.calculateTopPadding() + ToolBoxThemeTokens.spacing.one,
                end = ToolBoxThemeTokens.spacing.two,
                bottom = padding.calculateBottomPadding() + ToolBoxThemeTokens.spacing.one,
            ),
            verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one),
        ) {
            state.message?.let { message ->
                item("message") {
                    SurfaceCard {
                        AppText(message)
                        if (state.showSystemSettings) {
                            ToolBoxPrimaryButton("前往系统设置", {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
                                        Uri.fromParts("package", context.packageName, null),
                                    ),
                                )
                            })
                        }
                    }
                }
            }
            if (state.loaded && state.items.isEmpty()) {
                item("empty") { SurfaceCard { AppText("这个工具没有声明权限。") } }
            }
            items(state.items, key = PermissionItem::capability) { item ->
                SurfaceCard(contentPadding = ToolBoxThemeTokens.spacing.half) {
                    ToolBoxSwitchSettingRow(
                        title = item.title,
                        summary = item.reason,
                        checked = item.enabled,
                        onCheckedChange = { viewModel.setEnabled(item.capability, it) },
                        modifier = Modifier.testTag(HostTestTags.PermissionRowPrefix + item.capability),
                    )
                }
            }
        }
    }
}
