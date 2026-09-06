package io.toolbox.host.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.component.ToolBoxAppScaffold
import io.toolbox.core.ui.component.ToolBoxGlassState
import io.toolbox.core.ui.component.ToolBoxIcon
import io.toolbox.core.ui.component.ToolBoxIconButton
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxLargeTopBar
import io.toolbox.core.ui.component.ToolBoxNavigationBar
import io.toolbox.core.ui.component.ToolBoxNavigationItem
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.component.ToolBoxTopBar
import io.toolbox.core.ui.component.rememberToolBoxGlassState
import io.toolbox.core.ui.component.toolBoxBackdropSource
import io.toolbox.core.ui.component.toolBoxGlassEffect
import io.toolbox.core.ui.theme.ToolBoxThemeStyle
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

@Composable
internal fun PrimaryScreen(
    selected: MainDestination,
    onDestination: (MainDestination) -> Unit,
    title: String,
    subtitle: String = "",
    onImport: (() -> Unit)?,
    content: @Composable (PaddingValues, HostRouteLayout) -> Unit,
) {
    val glassState = rememberToolBoxGlassState()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(ToolBoxThemeTokens.colors.background)
            .testTag(selected.screenTestTag),
    ) {
        val layout = hostRouteLayoutFor(maxWidth)
        val isGlass = ToolBoxThemeTokens.style == ToolBoxThemeStyle.LiquidGlass
        if (layout.isCompact) {
            ToolBoxAppScaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = { TopBar(title, subtitle, onImport, glassState = glassState) },
                bottomBar = { DestinationBar(selected, onDestination, compact = true, glassState = glassState) },
            ) { scaffoldPadding ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .toolBoxBackdropSource(glassState)
                        .then(if (isGlass) Modifier else Modifier.padding(scaffoldPadding))
                        .consumeWindowInsets(scaffoldPadding),
                ) {
                    content(
                        mergePadding(
                            if (isGlass) scaffoldPadding else PaddingValues(0.dp),
                            layout.contentPadding(),
                        ),
                        layout,
                    )
                }
            }
        } else {
            Row(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                DestinationBar(selected, onDestination, compact = false, glassState = glassState)
                Column(Modifier.weight(1f)) {
                    TopBar(
                        title,
                        subtitle,
                        onImport,
                        defaultWindowInsetsPadding = false,
                        glassState = glassState,
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .toolBoxBackdropSource(glassState)
                            .widthIn(max = ToolBoxThemeTokens.sizes.contentMaxWidth)
                            .align(Alignment.CenterHorizontally),
                    ) {
                        content(layout.contentPadding(), layout)
                    }
                }
            }
        }
    }
}

private fun HostRouteLayout.contentPadding() = PaddingValues(
    start = horizontalContentPadding,
    top = verticalContentPadding,
    end = horizontalContentPadding,
    bottom = verticalContentPadding,
)

@Composable
private fun DestinationBar(
    selected: MainDestination,
    onDestination: (MainDestination) -> Unit,
    compact: Boolean,
    glassState: ToolBoxGlassState,
) {
    val modifier = if (compact) {
        Modifier
            .fillMaxWidth()
            .testTag(HostTestTags.BottomNavigationContainer)
    } else {
        Modifier
            .fillMaxHeight()
            .width(ToolBoxThemeTokens.sizes.mediumNavigationWidth)
            .padding(vertical = ToolBoxThemeTokens.spacing.two)
    }
    if (compact) {
        ToolBoxNavigationBar(
            items = mainNavigationItems,
            selectedId = selected.name,
            onItemSelected = { item -> onDestination(MainDestination.valueOf(item.id)) },
            modifier = modifier,
            glassState = glassState,
        )
    } else {
        val isGlass = ToolBoxThemeTokens.style == ToolBoxThemeStyle.LiquidGlass
        Column(
            modifier
                .toolBoxGlassEffect(
                    state = glassState,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(
                        if (isGlass) ToolBoxThemeTokens.radii.card else 0.dp,
                    ),
                )
                .background(if (isGlass) androidx.compose.ui.graphics.Color.Transparent else ToolBoxThemeTokens.colors.surface),
            verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.two),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MainDestination.entries.forEach { destination ->
                DestinationItem(destination, selected == destination, onDestination)
            }
        }
    }
}

@Composable
private fun DestinationItem(
    destination: MainDestination,
    selected: Boolean,
    onDestination: (MainDestination) -> Unit,
) {
    val color = if (selected) ToolBoxThemeTokens.colors.primary else ToolBoxThemeTokens.colors.textSecondary
    Column(
        modifier = Modifier
            .width(ToolBoxThemeTokens.sizes.mediumNavigationItemWidth)
            .heightIn(min = ToolBoxThemeTokens.sizes.compactChrome)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(ToolBoxThemeTokens.radii.denseSurface))
            .clickable(role = Role.Tab) { onDestination(destination) }
            .testTag(destination.testTag)
            .semantics { contentDescription = "${destination.label}标签${if (selected) "，已选择" else ""}" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ToolBoxIcon(
            icon = destination.icon,
            contentDescription = null,
            tint = color,
        )
        AppText(
            destination.label,
            textStyle = ToolBoxThemeTokens.textStyles.label,
            color = color,
            weight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
internal fun DetailScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable BoxScope.(PaddingValues) -> Unit,
) {
    val glassState = rememberToolBoxGlassState()
    val isGlass = ToolBoxThemeTokens.style == ToolBoxThemeStyle.LiquidGlass
    Box(
        modifier = modifier.fillMaxSize().background(ToolBoxThemeTokens.colors.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        ToolBoxAppScaffold(
            modifier = Modifier.fillMaxSize().widthIn(max = ToolBoxThemeTokens.sizes.contentMaxWidth),
            topBar = {
                ToolBoxTopBar(
                    title = title,
                    subtitle = subtitle,
                    navigationIcon = ToolBoxIconKey.Back,
                    onNavigationClick = onBack,
                    glassState = glassState,
                    actions = actions,
                )
            },
        ) { scaffoldPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .toolBoxBackdropSource(glassState)
                    .then(if (isGlass) Modifier else Modifier.padding(scaffoldPadding))
                    .consumeWindowInsets(scaffoldPadding),
            ) {
                content(if (isGlass) scaffoldPadding else PaddingValues(0.dp))
            }
        }
    }
}

@Composable
internal fun mergePadding(first: PaddingValues, second: PaddingValues): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    return PaddingValues(
        start = first.calculateStartPadding(layoutDirection) + second.calculateStartPadding(layoutDirection),
        top = first.calculateTopPadding() + second.calculateTopPadding(),
        end = first.calculateEndPadding(layoutDirection) + second.calculateEndPadding(layoutDirection),
        bottom = first.calculateBottomPadding() + second.calculateBottomPadding(),
    )
}

@Composable
private fun TopBar(
    title: String,
    subtitle: String,
    onImport: (() -> Unit)?,
    defaultWindowInsetsPadding: Boolean = true,
    glassState: ToolBoxGlassState,
) {
    ToolBoxLargeTopBar(
        title = title,
        subtitle = subtitle,
        defaultWindowInsetsPadding = defaultWindowInsetsPadding,
        glassState = glassState,
        actions = {
            if (onImport != null) {
                if (ToolBoxThemeTokens.style == ToolBoxThemeStyle.LiquidGlass) {
                    ToolBoxIconButton(
                        icon = ToolBoxIconKey.Add,
                        contentDescription = "导入 .tbx 工具包",
                        onClick = onImport,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(ToolBoxThemeTokens.colors.surface)
                            .testTag(HostTestTags.ImportFab),
                        tint = ToolBoxThemeTokens.colors.primary,
                    )
                } else {
                    ToolBoxTextButton(
                        label = "＋ 导入",
                        onClick = onImport,
                        modifier = Modifier
                            .testTag(HostTestTags.ImportFab)
                            .semantics { contentDescription = "导入 .tbx 工具包" },
                        contentColor = ToolBoxThemeTokens.colors.primary,
                    )
                }
            }
        },
    )
}

private val mainNavigationItems = MainDestination.entries.map { destination ->
    ToolBoxNavigationItem(
        id = destination.name,
        label = destination.label,
        icon = destination.icon,
        testTag = destination.testTag,
    )
}

private val MainDestination.icon: ToolBoxIconKey
    get() = when (this) {
        MainDestination.Tools -> ToolBoxIconKey.Tools
        MainDestination.Settings -> ToolBoxIconKey.Settings
    }

private val MainDestination.testTag: String
    get() = when (this) {
        MainDestination.Tools -> HostTestTags.BottomTools
        MainDestination.Settings -> HostTestTags.BottomSettings
    }

private val MainDestination.screenTestTag: String
    get() = when (this) {
        MainDestination.Tools -> HostTestTags.PrimaryTools
        MainDestination.Settings -> HostTestTags.PrimarySettings
    }
