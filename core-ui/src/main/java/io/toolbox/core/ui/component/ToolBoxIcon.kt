package io.toolbox.core.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
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
    Tools,
    Settings,
    Add,
    Search,
    Back,
    ChevronRight,
    Shield,
    Refresh,
    Folder,
    Clipboard,
    Globe,
    Lock,
    Code,
    Calculator,
    Clock,
    Check,
    Close,
    Note,
    Palette,
    Device,
    Haptics,
    Notifications,
    Share,
    Camera,
    Location,
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
    tint: Color = ToolBoxThemeTokens.colors.textSecondary,
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
        ToolBoxIcon(icon = icon, contentDescription = null, tint = tint)
    }
}

internal fun ToolBoxIconKey.asImageVector(): ImageVector = when (this) {
    ToolBoxIconKey.Tools -> OpenDesignIcons.Tools
    ToolBoxIconKey.Settings -> OpenDesignIcons.Settings
    ToolBoxIconKey.Add -> Icons.Outlined.Add
    ToolBoxIconKey.Search -> Icons.Outlined.Search
    ToolBoxIconKey.Back -> OpenDesignIcons.Back
    ToolBoxIconKey.ChevronRight -> Icons.AutoMirrored.Outlined.KeyboardArrowRight
    ToolBoxIconKey.Shield -> Icons.Outlined.Shield
    ToolBoxIconKey.Refresh -> Icons.Outlined.Refresh
    ToolBoxIconKey.Folder -> Icons.Outlined.Folder
    ToolBoxIconKey.Clipboard -> Icons.Outlined.ContentPaste
    ToolBoxIconKey.Globe -> Icons.Outlined.Public
    ToolBoxIconKey.Lock -> Icons.Outlined.Lock
    ToolBoxIconKey.Code -> Icons.Outlined.Code
    ToolBoxIconKey.Calculator -> Icons.Outlined.Calculate
    ToolBoxIconKey.Clock -> Icons.Outlined.AccessTime
    ToolBoxIconKey.Check -> Icons.Outlined.Check
    ToolBoxIconKey.Close -> Icons.Outlined.Close
    ToolBoxIconKey.Note -> Icons.Outlined.Description
    ToolBoxIconKey.Palette -> Icons.Outlined.Palette
    ToolBoxIconKey.Device -> Icons.Outlined.Devices
    ToolBoxIconKey.Haptics -> Icons.Outlined.TouchApp
    ToolBoxIconKey.Notifications -> Icons.Outlined.NotificationsNone
    ToolBoxIconKey.Share -> Icons.Outlined.Share
    ToolBoxIconKey.Camera -> Icons.Outlined.CameraAlt
    ToolBoxIconKey.Location -> Icons.Outlined.LocationOn
}
