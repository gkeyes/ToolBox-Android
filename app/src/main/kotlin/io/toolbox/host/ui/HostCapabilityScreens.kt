package io.toolbox.host.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

@Composable
fun ImportReviewScreen(onBack: () -> Unit) {
    CapabilityUnavailableScreen(HostCapability.ImportTools, onBack, Modifier.testTag("import_review"))
}

@Composable
fun PermissionCenterScreen(onBack: () -> Unit) {
    CapabilityUnavailableScreen(
        HostCapability.PermissionCenter,
        onBack,
        Modifier.testTag(HostTestTags.PermissionCenter),
    )
}

@Composable
fun RuntimeShellScreen(onBack: () -> Unit) {
    CapabilityUnavailableScreen(HostCapability.Runtime, onBack, Modifier.testTag(HostTestTags.RuntimeShell))
}

@Composable
fun CapabilityUnavailableScreen(
    capability: HostCapability,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DetailScreen(title = capability.title, onBack = onBack, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .testTag(HostTestTags.CapabilityUnavailable),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ToolGlyph("…", size = 56.dp)
            Spacer(Modifier.height(16.dp))
            AppText("${capability.title}暂不可用", size = 18, weight = FontWeight.Bold, align = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            AppText(
                capability.unavailableMessage,
                size = 13,
                color = ToolBoxThemeTokens.colors.textSecondary,
                align = TextAlign.Center,
            )
        }
    }
}

private val HostCapability.title: String
    get() = when (this) {
        HostCapability.ImportTools -> "导入工具"
        HostCapability.PermissionCenter -> "权限中心"
        HostCapability.Runtime -> "工具运行"
    }

private val HostCapability.unavailableMessage: String
    get() = when (this) {
        HostCapability.ImportTools -> "安全导入尚未接入，当前不会选择、检查或安装任何工具包。"
        HostCapability.PermissionCenter -> "权限授权与撤销尚未接入，当前没有可管理的工具权限。"
        HostCapability.Runtime -> "安全运行容器尚未接入，当前不会尝试打开任何工具。"
    }
