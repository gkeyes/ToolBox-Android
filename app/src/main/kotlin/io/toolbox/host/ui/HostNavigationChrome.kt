package io.toolbox.host.ui

import androidx.compose.foundation.layout.navigationBars
import io.toolbox.core.ui.component.ToolBoxGlassIconButton
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.component.toolBoxOpenDesignCanvas
import io.toolbox.core.ui.component.toolBoxOpenDesignSurface
import io.toolbox.core.ui.component.ToolBoxAppScaffold
import io.toolbox.core.ui.component.ToolBoxGlassState
import io.toolbox.core.ui.component.ToolBoxIcon
import io.toolbox.core.ui.component.ToolBoxIconButton
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxLargeTopBar
import io.toolbox.core.ui.component.ToolBoxMotion
import io.toolbox.core.ui.component.ToolBoxMenuAction
import io.toolbox.core.ui.component.ToolBoxOverflowMenu
import io.toolbox.core.ui.component.ToolBoxNavigationBar
import io.toolbox.core.ui.component.ToolBoxNavigationItem
import io.toolbox.core.ui.component.ToolBoxTextButton
import io.toolbox.core.ui.component.ToolBoxTopBar
import io.toolbox.core.ui.component.rememberToolBoxGlassState
import io.toolbox.core.ui.component.toolBoxBackdropSource
import io.toolbox.core.ui.component.toolBoxGlassEffect
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.CompositionLocalProvider
import io.toolbox.core.ui.component.LocalToolBoxTitleCollapse
import io.toolbox.core.ui.component.ToolBoxTitleCollapse
import io.toolbox.core.ui.component.rememberToolBoxTitleCollapse
import io.toolbox.core.ui.component.toolBoxLargeTitleHeight
import io.toolbox.core.ui.component.rememberToolBoxListScrolledPx
import androidx.compose.foundation.lazy.LazyListState
import io.toolbox.core.ui.component.ToolBoxCollapsingTopBar
import kotlin.math.absoluteValue
import kotlin.math.abs

@Composable
internal fun PrimaryScreen(
    selected: MainDestination,
    onDestination: (MainDestination) -> Unit,
    title: String,
    subtitle: String = "",
    onImport: (() -> Unit)?,
    organizeEditing: Boolean = false,
    onOrganize: (() -> Unit)? = null,
    onCreateGroup: (() -> Unit)? = null,
    listStates: Map<MainDestination, LazyListState> = emptyMap(),
    content: @Composable (MainDestination, PaddingValues, HostRouteLayout) -> Unit,
) {
    val glassState = rememberToolBoxGlassState()
    val titleCollapse = rememberToolBoxTitleCollapse()
    val pagerState = rememberPagerState(
        initialPage = selected.ordinal,
        pageCount = { MainDestination.entries.size },
    )
    // One top bar for all tabs. Its collapse is read synchronously from the tabs' own list
    // states and blended by the pager position, so a tab switch never starts from a stale or
    // late-bound value: the first frame of every page position is already correct.
    val tabScroll = MainDestination.entries.map { destination ->
        listStates[destination]?.let { rememberToolBoxListScrolledPx(it) }
    }
    val tabScrolledPx: () -> Float = {
        val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
            .coerceIn(0f, (tabScroll.size - 1).toFloat())
        val lower = position.toInt()
        val upper = (lower + 1).coerceAtMost(tabScroll.lastIndex)
        val fraction = position - lower
        fun px(index: Int) = (tabScroll[index]?.invoke() ?: 0f).coerceIn(0f, TAB_SCROLL_CAP_PX)
        px(lower) + (px(upper) - px(lower)) * fraction
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .toolBoxOpenDesignCanvas()
            .testTag(selected.screenTestTag),
    ) {
        val layout = hostRouteLayoutFor(maxWidth)
        // Tabs drive the bar through tabScrolledPx; no per-page binding that could lag a frame.
        CompositionLocalProvider(LocalToolBoxTitleCollapse provides null) {
            if (layout.isCompact) {
                ToolBoxAppScaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopBar(title, subtitle, onImport, glassState = glassState, scrolledPx = tabScrolledPx,
                            organizeEditing = organizeEditing, onOrganize = onOrganize, onCreateGroup = onCreateGroup)
                    },
                    bottomBar = { DestinationBar(selected, onDestination, compact = true, glassState = glassState) },
                ) { scaffoldPadding ->
                    // Content always scrolls under the bars; the scaffold padding becomes list padding.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .nestedScroll(titleCollapse.nestedScrollConnection)
                            .toolBoxBackdropSource(glassState)
                            .consumeWindowInsets(scaffoldPadding),
                    ) {
                        PrimaryPager(
                            selected = selected,
                            onDestination = onDestination,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = mergePadding(scaffoldPadding, layout.contentPadding()),
                            layout = layout,
                            userScrollEnabled = !organizeEditing,
                            pagerState = pagerState,
                            content = content,
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
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .nestedScroll(titleCollapse.nestedScrollConnection)
                                .toolBoxBackdropSource(glassState)
                                .widthIn(max = ToolBoxThemeTokens.sizes.contentMaxWidth)
                                .align(Alignment.TopCenter),
                        ) {
                            PrimaryPager(
                                selected = selected,
                                onDestination = onDestination,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = mergePadding(
                                    PaddingValues(top = ToolBoxThemeTokens.sizes.topBar + toolBoxLargeTitleHeight(subtitle)),
                                    layout.contentPadding(),
                                ),
                                layout = layout,
                                userScrollEnabled = false,
                                pagerState = pagerState,
                                content = content,
                            )
                        }
                        TopBar(
                            title,
                            subtitle,
                            onImport,
                            defaultWindowInsetsPadding = false,
                            glassState = glassState,
                            scrolledPx = tabScrolledPx,
                            organizeEditing = organizeEditing,
                            onOrganize = onOrganize,
                            onCreateGroup = onCreateGroup,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimaryPager(
    selected: MainDestination,
    onDestination: (MainDestination) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    layout: HostRouteLayout,
    userScrollEnabled: Boolean,
    pagerState: androidx.compose.foundation.pager.PagerState,
    content: @Composable (MainDestination, PaddingValues, HostRouteLayout) -> Unit,
) {
    val destinations = MainDestination.entries
    val latestSelected = rememberUpdatedState(selected)
    val latestOnDestination = rememberUpdatedState(onDestination)

    LaunchedEffect(selected) {
        val targetPage = selected.ordinal
        if (pagerState.currentPage != targetPage || pagerState.currentPageOffsetFraction != 0f) {
            pagerState.animateScrollToPage(
                page = targetPage,
                animationSpec = ToolBoxMotion.pageSpec(
                    distance = abs(targetPage - pagerState.currentPage),
                ),
            )
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress to pagerState.currentPage }
            .distinctUntilChanged()
            .collect { (scrolling, page) ->
                if (!scrolling) {
                    val destination = destinations[page]
                    if (destination != latestSelected.value) {
                        latestOnDestination.value(destination)
                    }
                }
            }
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        beyondViewportPageCount = 1,
        userScrollEnabled = userScrollEnabled && layout.isCompact,
    ) { page ->
        val pageOffset = (
            (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
        ).absoluteValue.coerceIn(0f, 1f)
        val pageScale = 1f - pageOffset * 0.012f
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = 1f - pageOffset * 0.08f
                    scaleX = pageScale
                    scaleY = pageScale
                }
                .then(
                    if (page == pagerState.currentPage) Modifier
                    else Modifier.clearAndSetSemantics { },
                ),
        ) {
            content(destinations[page], contentPadding, layout)
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
        // iOS 27: the tab capsule and a separate search circle float over the content.
        Row(modifier, verticalAlignment = Alignment.Bottom) {
            ToolBoxNavigationBar(
                items = mainNavigationItems,
                selectedId = selected.name,
                onItemSelected = { item -> onDestination(MainDestination.valueOf(item.id)) },
                modifier = Modifier.weight(1f),
                glassState = glassState,
            )
            ToolBoxGlassIconButton(
                icon = ToolBoxIconKey.Search,
                contentDescription = "搜索工具",
                onClick = { onDestination(MainDestination.Tools) },
                glassState = glassState,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(end = ToolBoxThemeTokens.spacing.oneHalf, bottom = ToolBoxThemeTokens.spacing.compact)
                    .testTag(HostTestTags.BottomSearch),
                size = 64.dp,
                iconSize = 24.dp,
            )
        }
    } else {
        Column(
            modifier
                .toolBoxGlassEffect(
                    state = glassState,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(ToolBoxThemeTokens.radii.card),
                )
                .background(androidx.compose.ui.graphics.Color.Transparent),
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
    val titleCollapse = rememberToolBoxTitleCollapse()
    Box(
        modifier = modifier.fillMaxSize().toolBoxOpenDesignCanvas(),
        contentAlignment = Alignment.TopCenter,
    ) {
        CompositionLocalProvider(LocalToolBoxTitleCollapse provides titleCollapse) {
            ToolBoxAppScaffold(
                modifier = Modifier.widthIn(max = ToolBoxThemeTokens.sizes.contentMaxWidth).fillMaxSize(),
                topBar = {
                    ToolBoxCollapsingTopBar(
                        title = title,
                        subtitle = subtitle,
                        navigationIcon = ToolBoxIconKey.Back,
                        onNavigationClick = onBack,
                        glassState = glassState,
                        actions = actions,
                    )
                },
            ) { scaffoldPadding ->
                // Both looks scroll content under the bar; pages apply this as list content padding.
                Box(
                    Modifier
                        .fillMaxSize()
                        .nestedScroll(titleCollapse.nestedScrollConnection)
                        .toolBoxBackdropSource(glassState)
                        .consumeWindowInsets(scaffoldPadding),
                ) {
                    Box(
                        Modifier.widthIn(max = ToolBoxThemeTokens.sizes.detailContentMaxWidth)
                            .fillMaxSize().align(Alignment.TopCenter).testTag("host_detail_content"),
                    ) {
                        content(scaffoldPadding)
                    }
                }
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
    scrolledPx: (() -> Float)? = null,
    organizeEditing: Boolean = false,
    onOrganize: (() -> Unit)? = null,
    onCreateGroup: (() -> Unit)? = null,
) {
    ToolBoxLargeTopBar(
        title = title,
        subtitle = subtitle,
        defaultWindowInsetsPadding = defaultWindowInsetsPadding,
        glassState = glassState,
        scrolledPx = scrolledPx,
        actions = {
            if (onOrganize != null) {
                if (organizeEditing) {
                    ToolBoxTextButton(
                        label = "完成",
                        onClick = onOrganize,
                        outlined = false,
                        modifier = Modifier.testTag("catalog_organize"),
                    )
                } else {
                    ToolBoxOverflowMenu(
                        contentDescription = "首页菜单",
                        actions = buildList {
                            onImport?.let { add(ToolBoxMenuAction("导入工具", ToolBoxIconKey.Add, HostTestTags.ImportFab, it)) }
                            add(ToolBoxMenuAction("整理首页", ToolBoxIconKey.Drag, "catalog_organize", onOrganize))
                            onCreateGroup?.let { add(ToolBoxMenuAction("新建分组", ToolBoxIconKey.Folder, "catalog_home_create_group", it)) }
                        },
                        menuTestTag = "catalog_home_menu",
                        modifier = Modifier.testTag("catalog_home_overflow"),
                        glassState = glassState,
                    )
                }
            } else if (onImport != null) {
                ToolBoxGlassIconButton(
                    icon = ToolBoxIconKey.Add,
                    contentDescription = "导入工具（支持 .tbx / .zip）",
                    onClick = onImport,
                    glassState = glassState,
                    modifier = Modifier.testTag(HostTestTags.ImportFab),
                )
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
        MainDestination.Home -> ToolBoxIconKey.Folder
        MainDestination.Tools -> ToolBoxIconKey.Tools
        MainDestination.Settings -> ToolBoxIconKey.Settings
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

/** Beyond this scroll distance every tab reads as fully collapsed; keeps blending finite. */
private const val TAB_SCROLL_CAP_PX = 4096f
