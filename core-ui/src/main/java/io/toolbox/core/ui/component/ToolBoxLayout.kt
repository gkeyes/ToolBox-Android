package io.toolbox.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar

data class ToolBoxNavigationItem(
    val id: String,
    val label: String,
    val icon: ToolBoxIconKey,
    val testTag: String? = null,
)

@Composable
fun ToolBoxAppScaffold(
    modifier: Modifier = Modifier,
    topBar: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    floatingActionButton: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val hasTopBar = topBar != null
    val hasBottomBar = bottomBar != null
    Scaffold(
        modifier = modifier,
        topBar = {
            topBar?.invoke()
        },
        bottomBar = {
            bottomBar?.invoke()
        },
        floatingActionButton = {
            floatingActionButton?.invoke()
        },
        containerColor = ToolBoxThemeTokens.colors.background,
        contentWindowInsets = toolBoxScaffoldContentInsets(
            hasTopBar = hasTopBar,
            hasBottomBar = hasBottomBar,
        ),
        content = content,
    )
}

@Composable
fun ToolBoxRuntimeScaffold(
    modifier: Modifier = Modifier,
    topBar: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    ToolBoxAppScaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = null,
        floatingActionButton = null,
        content = content,
    )
}

@Composable
private fun toolBoxScaffoldContentInsets(
    hasTopBar: Boolean,
    hasBottomBar: Boolean,
): WindowInsets {
    var insets = WindowInsets(0, 0, 0, 0)
    if (!hasTopBar) {
        insets = insets
            .union(WindowInsets.statusBars)
            .union(WindowInsets.displayCutout)
    }
    if (!hasBottomBar) {
        insets = insets.union(WindowInsets.navigationBars)
    }
    return insets
}

@Composable
fun ToolBoxTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    navigationIcon: ToolBoxIconKey? = null,
    navigationContentDescription: String = "返回",
    onNavigationClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val navigationSlot: @Composable () -> Unit = {
        if (navigationIcon != null && onNavigationClick != null) {
            ToolBoxIconButton(
                icon = navigationIcon,
                contentDescription = navigationContentDescription,
                onClick = onNavigationClick,
            )
        }
    }
    SmallTopAppBar(
        title = title,
        modifier = modifier.heightIn(min = ToolBoxThemeTokens.sizes.touchTarget),
        color = ToolBoxThemeTokens.colors.background,
        titleColor = ToolBoxThemeTokens.colors.textPrimary,
        subtitle = subtitle,
        subtitleColor = ToolBoxThemeTokens.colors.textSecondary,
        navigationIcon = navigationSlot,
        actions = actions,
        defaultWindowInsetsPadding = true,
    )
}

@Composable
fun ToolBoxLargeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    defaultWindowInsetsPadding: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = title,
        largeTitle = title,
        subtitle = subtitle,
        modifier = modifier,
        color = ToolBoxThemeTokens.colors.background,
        titleColor = ToolBoxThemeTokens.colors.textPrimary,
        largeTitleColor = ToolBoxThemeTokens.colors.textPrimary,
        subtitleColor = ToolBoxThemeTokens.colors.textSecondary,
        actions = actions,
        defaultWindowInsetsPadding = defaultWindowInsetsPadding,
    )
}

@Composable
fun ToolBoxRuntimeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: ToolBoxIconKey? = null,
    navigationContentDescription: String = "返回",
    onNavigationClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    ToolBoxTopBar(
        title = title,
        modifier = modifier,
        navigationIcon = navigationIcon,
        navigationContentDescription = navigationContentDescription,
        onNavigationClick = onNavigationClick,
        actions = actions,
    )
}

@Composable
fun ToolBoxNavigationBar(
    items: List<ToolBoxNavigationItem>,
    selectedId: String,
    onItemSelected: (ToolBoxNavigationItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val usesIconOnlyLayout = LocalDensity.current.fontScale >= 1.5f
    NavigationBar(
        modifier = modifier,
        color = ToolBoxThemeTokens.colors.surface,
        showDivider = false,
        defaultWindowInsetsPadding = true,
        mode = if (usesIconOnlyLayout) {
            NavigationBarDisplayMode.IconOnly
        } else {
            NavigationBarDisplayMode.IconAndText
        },
    ) {
        items.forEach { item ->
            val isSelected = item.id == selectedId
            NavigationBarItem(
                selected = isSelected,
                onClick = { onItemSelected(item) },
                icon = item.icon.asImageVector(),
                label = item.label,
                modifier = item.testTag?.let(Modifier::testTag) ?: Modifier,
            )
        }
    }
}

@Composable
fun ToolBoxFloatingActionButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ToolBoxIconKey = ToolBoxIconKey.Add,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Button
        },
        containerColor = ToolBoxThemeTokens.colors.primary,
    ) {
        ToolBoxIcon(
            icon = icon,
            contentDescription = null,
            tint = ToolBoxThemeTokens.colors.onPrimary,
        )
    }
}

@Composable
fun ToolBoxCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(ToolBoxThemeTokens.spacing.two),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.then(
            if (onClick != null || onLongClick != null) {
                Modifier.sizeIn(minHeight = ToolBoxThemeTokens.sizes.touchTarget)
            } else {
                Modifier
            },
        ),
        cornerRadius = ToolBoxThemeTokens.radii.card,
        insideMargin = contentPadding,
        onClick = onClick,
        onLongPress = onLongClick,
        content = content,
    )
}

@Composable
fun ToolBoxGroupedSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = ToolBoxThemeTokens.radii.denseSurface,
        insideMargin = PaddingValues(0.dp),
        content = content,
    )
}

@Composable
fun ToolBoxGroupDivider(
    modifier: Modifier = Modifier,
    startPadding: Dp = 60.dp,
    endPadding: Dp = 0.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startPadding, end = endPadding)
            .height(ToolBoxThemeTokens.sizes.divider)
            .background(ToolBoxThemeTokens.colors.divider),
    )
}

@Composable
fun ToolBoxPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val colors = ToolBoxThemeTokens.colors
    val containerColor = if (destructive) colors.danger else colors.primary
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = ToolBoxThemeTokens.sizes.touchTarget),
        enabled = enabled,
        minHeight = ToolBoxThemeTokens.sizes.touchTarget,
        colors = ButtonDefaults.buttonColorsPrimary(
            color = containerColor,
            contentColor = colors.onPrimary,
        ),
    ) {
        ToolBoxText(
            text = label,
            style = ToolBoxThemeTokens.textStyles.body.copy(
                color = colors.onPrimary.copy(alpha = if (enabled) 1f else 0.46f),
            ),
        )
    }
}

@Composable
fun ToolBoxDestructiveButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = ToolBoxThemeTokens.colors
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = ToolBoxThemeTokens.sizes.touchTarget),
        enabled = enabled,
        minHeight = ToolBoxThemeTokens.sizes.touchTarget,
        colors = ButtonDefaults.buttonColorsPrimary(
            color = colors.softDanger,
            contentColor = colors.onSoftDanger,
        ),
    ) {
        ToolBoxText(
            text = label,
            style = ToolBoxThemeTokens.textStyles.body.copy(
                color = colors.onSoftDanger.copy(alpha = if (enabled) 1f else 0.46f),
            ),
        )
    }
}

@Composable
fun ToolBoxTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = ToolBoxThemeTokens.colors.primary,
) {
    TextButton(
        text = label,
        onClick = onClick,
        modifier = modifier.heightIn(min = ToolBoxThemeTokens.sizes.touchTarget),
        enabled = enabled,
        minHeight = ToolBoxThemeTokens.sizes.touchTarget,
        colors = ButtonDefaults.textButtonColors(
            color = Color.Transparent,
            disabledColor = Color.Transparent,
            textColor = contentColor,
            disabledTextColor = contentColor.copy(alpha = 0.46f),
        ),
        textStyle = ToolBoxThemeTokens.textStyles.body.copy(
            color = contentColor.copy(alpha = if (enabled) 1f else 0.46f),
        ),
    )
}

@Composable
fun ToolBoxRunningStatusButton(
    stopping: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = ToolBoxThemeTokens.colors
    Button(
        onClick = onClick,
        modifier = modifier.sizeIn(
            minWidth = ToolBoxThemeTokens.sizes.touchTarget,
            minHeight = ToolBoxThemeTokens.sizes.touchTarget,
        ),
        enabled = enabled,
        minHeight = ToolBoxThemeTokens.sizes.touchTarget,
        colors = ButtonDefaults.buttonColorsPrimary(
            color = colors.softSuccess,
            contentColor = colors.onSoftSuccess,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!stopping) {
                Box(Modifier.size(ToolBoxThemeTokens.spacing.compact).background(colors.onSoftSuccess, CircleShape))
                Spacer(Modifier.width(ToolBoxThemeTokens.spacing.compact))
            }
            ToolBoxText(
                text = if (stopping) "停止中" else "运行中",
                style = ToolBoxThemeTokens.textStyles.metadata.copy(color = colors.onSoftSuccess),
            )
        }
    }
}
