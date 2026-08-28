package io.toolbox.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.NavigationBar
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
    val ownership = ToolBoxAppScaffoldInsetPolicy.resolve(
        hasTopBar = topBar != null,
        hasBottomBar = bottomBar != null,
        hasFloatingActionButton = floatingActionButton != null,
    )
    val topBarInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout)
    val bottomBarInsets = WindowInsets.navigationBars.union(WindowInsets.ime)
    val contentWindowInsets = WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .union(WindowInsets.ime)

    Scaffold(
        modifier = modifier,
        topBar = {
            if (topBar != null) {
                val topBarModifier = if (ownership.statusBars == ToolBoxInsetOwner.TopBar) {
                    Modifier.windowInsetsPadding(topBarInsets)
                } else {
                    Modifier
                }
                Box(topBarModifier) {
                    topBar()
                }
            }
        },
        bottomBar = {
            if (bottomBar != null) {
                val bottomBarModifier = if (ownership.navigationBars == ToolBoxInsetOwner.BottomBar) {
                    Modifier.windowInsetsPadding(bottomBarInsets)
                } else {
                    Modifier
                }
                Box(bottomBarModifier) {
                    bottomBar()
                }
            }
        },
        floatingActionButton = {
            if (floatingActionButton != null) {
                Box {
                    floatingActionButton()
                }
            }
        },
        containerColor = ToolBoxThemeTokens.colors.background,
        contentWindowInsets = contentWindowInsets,
        content = content,
    )
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
    val topBarMinHeight = toolBoxTopBarMinHeight(LocalDensity.current.fontScale)
    SmallTopAppBar(
        title = title,
        subtitle = subtitle,
        modifier = modifier.heightIn(min = topBarMinHeight),
        color = ToolBoxThemeTokens.colors.surface,
        titleColor = ToolBoxThemeTokens.colors.textPrimary,
        subtitleColor = ToolBoxThemeTokens.colors.textSecondary,
        navigationIcon = {
            if (navigationIcon != null && onNavigationClick != null) {
                ToolBoxIconButton(
                    icon = navigationIcon,
                    contentDescription = navigationContentDescription,
                    onClick = onNavigationClick,
                )
            }
        },
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
    val itemMinHeight = toolBoxNavigationItemMinHeight(LocalDensity.current.fontScale)
    NavigationBar(
        modifier = modifier,
        color = ToolBoxThemeTokens.colors.surface,
    ) {
        items.forEach { item ->
            NavigationBarItem(
                selected = item.id == selectedId,
                onClick = { onItemSelected(item) },
                icon = item.icon.asImageVector(),
                label = item.label,
                modifier = (item.testTag?.let(Modifier::testTag) ?: Modifier)
                    .heightIn(min = itemMinHeight),
            )
        }
    }
}

internal fun toolBoxNavigationItemMinHeight(fontScale: Float) = 64.dp * fontScale.coerceAtLeast(1f)

internal fun toolBoxTopBarMinHeight(fontScale: Float) = 64.dp * fontScale.coerceAtLeast(1f)

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
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.then(
            if (onClick != null || onLongClick != null) Modifier.sizeIn(minHeight = 48.dp) else Modifier,
        ),
        cornerRadius = 22.dp,
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
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        minHeight = 48.dp,
    ) {
        BasicText(text = label, style = ToolBoxThemeTokens.textStyles.body)
    }
}
