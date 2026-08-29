package io.toolbox.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Scaffold

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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = toolBoxTopBarMinHeight())
            .background(ToolBoxThemeTokens.colors.background)
            .padding(horizontal = ToolBoxThemeTokens.spacing.two, vertical = ToolBoxThemeTokens.spacing.one),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navigationIcon != null && onNavigationClick != null) {
            ToolBoxIconButton(
                icon = navigationIcon,
                contentDescription = navigationContentDescription,
                onClick = onNavigationClick,
            )
            Spacer(Modifier.width(ToolBoxThemeTokens.spacing.one))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.half),
        ) {
            ToolBoxText(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = ToolBoxThemeTokens.textStyles.screenTitle.copy(
                    color = ToolBoxThemeTokens.colors.textPrimary,
                ),
            )
            if (subtitle.isNotEmpty()) {
                ToolBoxText(
                    text = subtitle,
                    style = ToolBoxThemeTokens.textStyles.metadata.copy(
                        color = ToolBoxThemeTokens.colors.textSecondary,
                    ),
                )
            }
        }
        actions()
    }
}

@Composable
fun ToolBoxNavigationBar(
    items: List<ToolBoxNavigationItem>,
    selectedId: String,
    onItemSelected: (ToolBoxNavigationItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val itemMinHeight = toolBoxNavigationItemMinHeight()
    val horizontalItems = LocalDensity.current.fontScale >= 1.5f
    Column(
        modifier = modifier
            .height(itemMinHeight)
            .background(ToolBoxThemeTokens.colors.surface),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(ToolBoxThemeTokens.sizes.divider)
                .background(ToolBoxThemeTokens.colors.divider),
        )
        Row(Modifier.fillMaxWidth().weight(1f)) {
            items.forEach { item ->
                val isSelected = item.id == selectedId
                val color = if (isSelected) {
                    ToolBoxThemeTokens.colors.primary
                } else {
                    ToolBoxThemeTokens.colors.textSecondary
                }
                val itemModifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(role = Role.Tab) { onItemSelected(item) }
                    .then(item.testTag?.let(Modifier::testTag) ?: Modifier)
                    .semantics {
                        selected = isSelected
                        contentDescription = "${item.label}标签${if (isSelected) "，已选择" else ""}"
                    }
                if (horizontalItems) {
                    Row(
                        modifier = itemModifier,
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ToolBoxIcon(item.icon, contentDescription = null, tint = color)
                        Spacer(Modifier.width(ToolBoxThemeTokens.spacing.one))
                        ToolBoxText(
                            text = item.label,
                            style = ToolBoxThemeTokens.textStyles.label.copy(color = color),
                            maxLines = 1,
                        )
                    }
                } else {
                    Column(
                        modifier = itemModifier,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        ToolBoxIcon(item.icon, contentDescription = null, tint = color)
                        Spacer(Modifier.height(ToolBoxThemeTokens.spacing.half))
                        ToolBoxText(
                            text = item.label,
                            style = ToolBoxThemeTokens.textStyles.label.copy(color = color),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

internal fun toolBoxNavigationItemMinHeight() = ToolBoxThemeTokens.sizes.compactChrome

internal fun toolBoxTopBarMinHeight() = ToolBoxThemeTokens.sizes.compactChrome

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
