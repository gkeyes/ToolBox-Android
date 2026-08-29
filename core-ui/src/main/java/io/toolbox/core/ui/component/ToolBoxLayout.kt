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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
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
    val bottomBarInsets = WindowInsets.navigationBars
    val contentWindowInsets = when {
        topBar == null && bottomBar == null -> topBarInsets
            .union(WindowInsets.navigationBars)
            .union(WindowInsets.ime)
        topBar == null -> topBarInsets.union(WindowInsets.ime)
        bottomBar == null -> WindowInsets.navigationBars.union(WindowInsets.ime)
        else -> WindowInsets.ime
    }

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
                val floatingActionButtonModifier = if (
                    ownership.floatingActionButton == ToolBoxInsetOwner.FloatingActionButton
                ) {
                    Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                } else {
                    Modifier
                }
                Box(floatingActionButtonModifier) {
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
        modifier = modifier.height(itemMinHeight),
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

internal fun toolBoxNavigationItemMinHeight(fontScale: Float) = toolBoxChromeMinHeight(fontScale)

internal fun toolBoxTopBarMinHeight(fontScale: Float) = toolBoxChromeMinHeight(fontScale)

private fun toolBoxChromeMinHeight(fontScale: Float) =
    ToolBoxThemeTokens.sizes.compactChrome +
        (ToolBoxThemeTokens.sizes.largeTextChrome - ToolBoxThemeTokens.sizes.compactChrome) *
        (fontScale - 1f).coerceIn(0f, 1f)

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
        BasicText(text = label, style = ToolBoxThemeTokens.textStyles.body)
    }
}
