package io.toolbox.core.ui.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar

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
    val appBarModifier = modifier
        .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
        .heightIn(min = ToolBoxThemeTokens.sizes.touchTarget)
        .semantics(mergeDescendants = true) { heading() }
    SmallTopAppBar(
        title = title,
        modifier = appBarModifier,
        color = ToolBoxThemeTokens.colors.background,
        titleColor = ToolBoxThemeTokens.colors.textPrimary,
        subtitle = subtitle,
        subtitleColor = ToolBoxThemeTokens.colors.textSecondary,
        navigationIcon = navigationSlot,
        actions = actions,
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
    NavigationBar(
        modifier = modifier,
        color = ToolBoxThemeTokens.colors.surface,
        defaultWindowInsetsPadding = true,
        mode = NavigationBarDisplayMode.IconAndText,
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
fun ToolBoxPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = ToolBoxThemeTokens.sizes.touchTarget),
        enabled = enabled,
        minHeight = ToolBoxThemeTokens.sizes.touchTarget,
    ) {
        ToolBoxText(text = label, style = ToolBoxThemeTokens.textStyles.body)
    }
}
