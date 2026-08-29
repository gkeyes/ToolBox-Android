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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import io.toolbox.core.ui.component.ToolBoxAppScaffold
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxIconButton
import io.toolbox.core.ui.component.ToolBoxNavigationBar
import io.toolbox.core.ui.component.ToolBoxNavigationItem
import io.toolbox.core.ui.component.ToolBoxTopBar
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

@Composable
internal fun PrimaryScreen(
    selected: MainDestination,
    onDestination: (MainDestination) -> Unit,
    title: String,
    onImport: (() -> Unit)?,
    content: @Composable (PaddingValues) -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(ToolBoxThemeTokens.colors.background)
            .testTag(selected.screenTestTag),
    ) {
        val layout = hostRouteLayoutFor(maxWidth)
        ToolBoxAppScaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = if (layout.isCompact) ({ TopBar(selected, title, onImport) }) else null,
            bottomBar = if (layout.isCompact) ({ DestinationBar(selected, onDestination, compact = true) }) else null,
        ) { scaffoldPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(scaffoldPadding)
                    .consumeWindowInsets(scaffoldPadding),
            ) {
                if (layout.isCompact) {
                    content(layout.contentPadding())
                } else {
                    Row(Modifier.fillMaxSize()) {
                        DestinationBar(selected, onDestination, compact = false)
                        Column(Modifier.weight(1f)) {
                            TopBar(selected, title, onImport)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .widthIn(max = ToolBoxThemeTokens.sizes.contentMaxWidth)
                                    .align(Alignment.CenterHorizontally),
                            ) {
                                content(layout.contentPadding())
                            }
                        }
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
        )
    } else {
        Column(
            modifier.background(ToolBoxThemeTokens.colors.surface),
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
        AppText(
            destination.symbol,
            textStyle = ToolBoxThemeTokens.textStyles.sectionTitle,
            color = color,
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
    content: @Composable BoxScope.() -> Unit,
) {
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
                )
            },
        ) { scaffoldPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(scaffoldPadding)
                    .consumeWindowInsets(scaffoldPadding),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun TopBar(selected: MainDestination, title: String, onImport: (() -> Unit)?) {
    ToolBoxTopBar(
        title = title,
        subtitle = if (selected == MainDestination.Home) "本地运行的小工具" else "",
        actions = {
            if (onImport != null) {
                if (selected == MainDestination.Tools) {
                    Box(
                        modifier = Modifier
                            .heightIn(min = ToolBoxThemeTokens.sizes.touchTarget)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(ToolBoxThemeTokens.radii.full))
                            .background(ToolBoxThemeTokens.colors.softPrimary)
                            .clickable(role = Role.Button, onClick = onImport)
                            .padding(horizontal = ToolBoxThemeTokens.spacing.oneHalf)
                            .testTag(HostTestTags.ImportFab)
                            .semantics { contentDescription = "导入 .tbx 工具包" },
                        contentAlignment = Alignment.Center,
                    ) {
                        AppText(
                            "导入",
                            textStyle = ToolBoxThemeTokens.textStyles.label,
                            color = ToolBoxThemeTokens.colors.primary,
                            weight = FontWeight.SemiBold,
                        )
                    }
                } else {
                    ToolBoxIconButton(
                        icon = ToolBoxIconKey.Add,
                        contentDescription = "导入 .tbx 工具包",
                        onClick = onImport,
                        modifier = Modifier.testTag(HostTestTags.ImportFab),
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
        icon = when (destination) {
            MainDestination.Home -> ToolBoxIconKey.Home
            MainDestination.Tools -> ToolBoxIconKey.Tools
            MainDestination.Settings -> ToolBoxIconKey.Settings
        },
        testTag = destination.testTag,
    )
}

private val MainDestination.testTag: String
    get() = when (this) {
        MainDestination.Home -> HostTestTags.BottomHome
        MainDestination.Tools -> HostTestTags.BottomTools
        MainDestination.Settings -> HostTestTags.BottomSettings
    }

private val MainDestination.screenTestTag: String
    get() = when (this) {
        MainDestination.Home -> HostTestTags.PrimaryHome
        MainDestination.Tools -> HostTestTags.PrimaryTools
        MainDestination.Settings -> HostTestTags.PrimarySettings
    }
