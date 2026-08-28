package io.toolbox.core.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import top.yukonga.miuix.kmp.basic.IconButton

enum class ToolBoxIconKey {
    Home,
    Tools,
    Settings,
    Add,
    Search,
    More,
    Back,
    Shield,
    Refresh,
    OpenInNew,
    Debug,
    Folder,
    Clipboard,
    Globe,
    Lock,
    Code,
    Calculator,
    QrCode,
    Text,
    Clock,
    Check,
}

@Composable
fun ToolBoxIcon(
    icon: ToolBoxIconKey,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = ToolBoxThemeTokens.colors.textSecondary,
) {
    Image(
        imageVector = icon.asImageVector(),
        contentDescription = contentDescription,
        modifier = modifier.size(24.dp),
        colorFilter = ColorFilter.tint(tint),
    )
}

@Composable
fun ToolBoxIconButton(
    icon: ToolBoxIconKey,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        enabled = enabled,
    ) {
        ToolBoxIcon(icon = icon, contentDescription = null)
    }
}

internal fun ToolBoxIconKey.asImageVector(): ImageVector = when (this) {
    ToolBoxIconKey.Home -> Icons.Outlined.Home
    ToolBoxIconKey.Tools -> Icons.Outlined.Apps
    ToolBoxIconKey.Settings -> Icons.Outlined.Settings
    ToolBoxIconKey.Add -> Icons.Outlined.Add
    ToolBoxIconKey.Search -> Icons.Outlined.Search
    ToolBoxIconKey.More -> Icons.Outlined.MoreVert
    ToolBoxIconKey.Back -> Icons.AutoMirrored.Outlined.ArrowBack
    ToolBoxIconKey.Shield -> Icons.Outlined.Shield
    ToolBoxIconKey.Refresh -> Icons.Outlined.Refresh
    ToolBoxIconKey.OpenInNew -> Icons.AutoMirrored.Outlined.OpenInNew
    ToolBoxIconKey.Debug -> Icons.Outlined.BugReport
    ToolBoxIconKey.Folder -> Icons.Outlined.Folder
    ToolBoxIconKey.Clipboard -> Icons.Outlined.ContentPaste
    ToolBoxIconKey.Globe -> Icons.Outlined.Public
    ToolBoxIconKey.Lock -> Icons.Outlined.Lock
    ToolBoxIconKey.Code -> Icons.Outlined.Code
    ToolBoxIconKey.Calculator -> Icons.Outlined.Calculate
    ToolBoxIconKey.QrCode -> Icons.Outlined.QrCode
    ToolBoxIconKey.Text -> Icons.Outlined.TextFields
    ToolBoxIconKey.Clock -> Icons.Outlined.AccessTime
    ToolBoxIconKey.Check -> Icons.Outlined.Check
}
