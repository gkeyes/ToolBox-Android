package io.toolbox.host

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.core.data.*
import io.toolbox.core.ui.theme.*
import io.toolbox.host.catalog.*
import io.toolbox.host.importflow.ImportUiState
import io.toolbox.host.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.ceil

@RunWith(Parameterized::class)
class CatalogHomeBehaviorTest(private val style: ToolBoxThemeStyle, private val wideLargeText: Boolean) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val fixture = CatalogBehaviorFixture()
    private lateinit var homeListState: LazyListState
    private val destination = mutableStateOf(MainDestination.Home)
    private var isEditing = false
    private var isCreatingGroup = false
    private var importRequests = 0
    private var createGroupRequests = 0

    private fun render(restoration: StateRestorationTester? = null) {
        val content: @Composable () -> Unit = {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (wideLargeText) Density(density.density * 0.5f, 1.6f) else density) {
                ToolBoxTheme(style = style, reduceTransparency = true) {
                    var editing by rememberSaveable { mutableStateOf(false) }
                    var creatingGroup by rememberSaveable { mutableStateOf(false) }
                    val homeScroll = rememberLazyListState()
                    val toolsScroll = rememberLazyListState()
                    val creatingGroupNow = creatingGroup
                    SideEffect { homeListState = homeScroll; isEditing = editing; isCreatingGroup = creatingGroupNow }
                    PrimaryScreen(destination.value, {
                        editing = false
                        creatingGroup = false
                        destination.value = it
                    }, destination.value.label, onImport = { importRequests++ }, organizeEditing = editing,
                        onOrganize = if (destination.value == MainDestination.Home) ({ editing = !editing }) else null,
                        onCreateGroup = if (destination.value == MainDestination.Home) ({ createGroupRequests++; creatingGroup = true }) else null,
                    ) { padding, _ ->
                        if (destination.value != MainDestination.Settings) ToolManagerContent(
                            state = fixture.state(), importState = ImportUiState(),
                            listState = if (destination.value == MainDestination.Home) homeScroll else toolsScroll,
                            contentPadding = padding, onAction = fixture::action,
                            onImport = { importRequests++ }, onInstallExamples = {}, onDismissImport = {}, onOpenDetails = { fixture.managed += it },
                            home = destination.value == MainDestination.Home,
                            editing = editing, onEditingChange = { editing = it },
                            creatingGroup = creatingGroup, onDismissCreateGroup = { creatingGroup = false },
                        ) else AppText("设置内容")
                    }
                }
            }
        }
        if (restoration == null) compose.activity.setContent(content = content)
        else restoration.setContent(content)
    }

    @Test fun destinationsKeepHomeSectionsSeparateAndExitOrganizing() {
        render()
        enterOrganizing()
        compose.runOnIdle { assertTrue(isEditing) }
        compose.onNodeWithTag(HostTestTags.BottomTools).performClick()
        compose.onNodeWithText("搜索工具").assertExists()
        compose.onNodeWithText("排序").assertExists()
        compose.onNodeWithText("最近使用").assertDoesNotExist()
        compose.onNodeWithTag("catalog_organize").assertDoesNotExist()
        compose.runOnIdle { assertFalse(isEditing) }
        compose.onNodeWithTag(HostTestTags.BottomHome).performClick()
        compose.onNodeWithText("最近使用").assertIsDisplayed()
        compose.onNodeWithText("搜索工具").assertDoesNotExist()
        enterOrganizing()
        compose.onNodeWithTag(HostTestTags.BottomSettings).performClick()
        compose.onNodeWithText("设置内容").assertExists()
        compose.runOnIdle { assertFalse(isEditing) }
        compose.onNodeWithTag(HostTestTags.BottomHome).performClick()
        enterOrganizing()
        compose.waitUntil(5_000) { compose.runOnIdle { isEditing } }
        compose.onNodeWithTag("catalog_organize").assertTextContains("完成")
        pressCatalogBack()
        compose.waitUntil(5_000) { compose.runOnIdle { !isEditing } }
        compose.runOnIdle { assertFalse(isEditing); assertEquals(MainDestination.Home, destination.value) }
    }

    @Test fun normalTapOpensButLongPressAndOrganizingTapOpenOptions() {
        render()
        homeTile("favorite:a").performTouchInput { click() }
        compose.waitUntil(5_000) { compose.runOnIdle { fixture.opened.size == 1 } }
        compose.runOnIdle { assertEquals(listOf("a"), fixture.opened) }
        homeTile("favorite:a").performTouchInput { longClick() }
        compose.onNodeWithTag("catalog_tool_options").assertExists()
        compose.runOnIdle { assertEquals(listOf("a"), fixture.opened) }
        pressCatalogBack()
        enterOrganizing()
        homeTile("favorite:a").performTouchInput { click() }
        compose.onNodeWithTag("catalog_tool_options").assertExists()
        compose.runOnIdle { assertEquals(listOf("a"), fixture.opened) }
        pressCatalogBack()
        compose.onNodeWithTag("catalog_organize").performClick()
        homeTile("favorite:b").performTouchInput { click() }
        compose.waitUntil(5_000) { compose.runOnIdle { fixture.opened.size == 2 } }
        compose.runOnIdle { assertEquals(listOf("a", "b"), fixture.opened) }
    }

    @Test fun organizingStateRestoresAndFinishingRestoresTheHomeMenu() {
        val restoration = StateRestorationTester(object : ComposeContentTestRule by compose {
            override fun setContent(composable: @Composable () -> Unit) { compose.activity.setContent(content = composable) }
        })
        render(restoration)
        compose.onNodeWithTag("catalog_home_overflow").assertIsDisplayed()
        enterOrganizing()
        compose.onNodeWithTag("catalog_organize").assertTextContains("完成")
        compose.onNodeWithTag(HostTestTags.ImportFab).assertDoesNotExist()

        restoration.emulateSavedInstanceStateRestore()

        compose.runOnIdle { assertTrue(isEditing) }
        compose.onNodeWithTag("catalog_organize").assertTextContains("完成")
        compose.onNodeWithTag(HostTestTags.ImportFab).assertDoesNotExist()
        compose.onNodeWithTag("catalog_organize").performClick()
        compose.runOnIdle { assertFalse(isEditing) }
        compose.onNodeWithTag("catalog_home_overflow").assertIsDisplayed()
        openHomeMenu()
        compose.onNodeWithTag("catalog_organize").assertTextContains("整理首页")
        compose.onNodeWithTag(HostTestTags.ImportFab).assertIsDisplayed()
    }

    @Test fun homeMenuFitsTheWindowAndDismissalsDoNotRunItsThreeActions() {
        render()
        val original = compose.runOnIdle { fixture.layout.value }
        compose.onNodeWithTag("catalog_home_overflow").assertIsDisplayed()
        compose.onNodeWithTag("catalog_organize").assertDoesNotExist()
        compose.onNodeWithTag(HostTestTags.ImportFab).assertDoesNotExist()
        compose.onNodeWithText("新建分组").assertDoesNotExist()
        openHomeMenu()
        val menu = compose.onNodeWithTag("catalog_home_menu")
        val viewport = menu.fetchSemanticsNode().boundsInRoot
        val items = listOf(HostTestTags.ImportFab, "catalog_organize", "catalog_home_create_group").map { tag ->
            val node = compose.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction().fetchSemanticsNode()
            val position = node.positionInRoot
            assertTrue("Menu item $tag must fit horizontally in $viewport",
                position.x >= viewport.left - 1f && position.x + node.size.width <= viewport.right + 1f)
            assertTrue("Menu item $tag must fit vertically in $viewport",
                position.y >= viewport.top - 1f && position.y + node.size.height <= viewport.bottom + 1f)
            node.boundsInRoot
        }
        assertTrue("Menu actions must remain separate touch targets", items.zipWithNext().all { (first, second) -> first.bottom <= second.top })
        compose.onNodeWithTag(HostTestTags.ImportFab).assertTextContains("导入工具")
        compose.onNodeWithTag("catalog_organize").assertTextContains("整理首页")
        compose.onNodeWithTag("catalog_home_create_group").assertTextContains("新建分组")
        pressCatalogBack()
        awaitHomeMenuClosed()
        assertNoHomeMenuAction(original)

        openHomeMenu()
        val overlay = menu.fetchSemanticsNode().boundsInRoot
        val outside = Offset(overlay.width * 0.1f, overlay.height * 0.9f)
        val outsideInRoot = outside + overlay.topLeft
        listOf(HostTestTags.ImportFab, "catalog_organize", "catalog_home_create_group").forEach { tag ->
            assertFalse("Outside dismissal must not tap menu action $tag",
                compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.contains(outsideInRoot))
        }
        menu.performTouchInput { click(outside) }
        awaitHomeMenuClosed()
        assertNoHomeMenuAction(original)

        openHomeMenu()
        compose.onNodeWithTag(HostTestTags.ImportFab).performClick()
        awaitHomeMenuClosed()
        compose.waitUntil(5_000) { compose.runOnIdle { importRequests == 1 } }
        compose.runOnIdle { assertEquals(1, importRequests); assertEquals(0, createGroupRequests) }

        enterOrganizing()
        compose.onNodeWithTag("catalog_organize").assertTextContains("完成")
        compose.onNodeWithTag("catalog_home_overflow").assertDoesNotExist()
        compose.onNodeWithTag(HostTestTags.ImportFab).assertDoesNotExist()
        compose.onNodeWithTag("catalog_organize").performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { !isEditing } }

        openHomeMenu()
        compose.onNodeWithTag("catalog_home_create_group").performClick()
        awaitHomeMenuClosed()
        compose.onNodeWithTag("catalog_group_editor").assertExists()
        compose.waitUntil(5_000) { compose.runOnIdle { isCreatingGroup } }
        compose.onNodeWithText("新建分组").assertExists()
        compose.onNodeWithTag("catalog_group_name").assert(SemanticsMatcher("new group name is empty") {
            it.config.getOrNull(SemanticsProperties.EditableText)?.text == ""
        })
        compose.runOnIdle {
            assertEquals(1, createGroupRequests)
            assertEquals(1, importRequests)
            assertEquals(original, fixture.layout.value)
        }
        compose.onNodeWithTag("catalog_group_cancel").performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { !isCreatingGroup } }
        compose.onNodeWithTag("catalog_group_editor").assertDoesNotExist()
        compose.runOnIdle { assertEquals(original, fixture.layout.value) }

        compose.onNodeWithTag(HostTestTags.BottomTools).performClick()
        compose.onNodeWithTag("catalog_home_overflow").assertDoesNotExist()
        compose.onNodeWithTag(HostTestTags.ImportFab).assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { importRequests == 2 } }
        compose.runOnIdle { assertEquals(2, importRequests); assertEquals(1, createGroupRequests) }
    }

    @Test fun allToolsMainMoreLongPressAndCancelledPressesDoNotCrossTrigger() {
        render()
        compose.onNodeWithTag(HostTestTags.BottomTools).performClick()
        val mainA = compose.onNodeWithTag("tool_main:a").performScrollTo().assertIsDisplayed()
        mainA.performTouchInput { click() }
        compose.waitUntil(5_000) { compose.runOnIdle { fixture.opened == listOf("a") } }
        compose.onNodeWithTag("catalog_tool_options").assertDoesNotExist()

        val moreB = compose.onNodeWithTag("tool_more:b").performScrollTo().assertIsDisplayed()
        moreB.performTouchInput { click() }
        compose.onNodeWithTag("catalog_tool_options").assertExists()
        compose.onNode(hasText("Beta") and hasAnyAncestor(hasTestTag("catalog_tool_options"))).assertExists()
        compose.runOnIdle { assertEquals(listOf("a"), fixture.opened) }
        compose.onNodeWithTag("catalog_panel_close").performClick()
        compose.onNodeWithTag("catalog_tool_options").assertDoesNotExist()

        mainA.performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithTag("catalog_tool_options").assertExists()
        compose.onNode(hasText("Alpha") and hasAnyAncestor(hasTestTag("catalog_tool_options"))).assertExists()
        compose.runOnIdle { assertEquals(listOf("a"), fixture.opened) }
        compose.onNodeWithTag("catalog_panel_close").performClick()
        compose.onNodeWithTag("catalog_tool_options").assertDoesNotExist()

        mainA.performScrollTo().performTouchInput { down(center); moveBy(Offset(2f, 2f)); cancel() }
        moreB.performScrollTo().performTouchInput { down(center); cancel() }
        compose.onNodeWithTag("catalog_tool_options").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("a"), fixture.opened); assertTrue(fixture.managed.isEmpty()) }
        compose.onNodeWithTag("tool_main:b").performScrollTo().performTouchInput { click() }
        compose.waitUntil(5_000) { compose.runOnIdle { fixture.opened.size == 2 } }
        compose.runOnIdle { assertEquals(listOf("a", "b"), fixture.opened); assertTrue(fixture.managed.isEmpty()) }
    }

    @Test fun multipleGroupsFavoritesAndAccessibleOrderingStayIndependent() {
        fixture.layout.value = fixture.layout.value.copy(groups = fixture.layout.value.groups.map {
            if (it.id == "g1") it.copy(members = listOf("a", "b")) else it
        })
        render()
        homeTile("favorite:a").performTouchInput { longClick() }
        compose.onNodeWithTag("catalog_tool_groups").performClick()
        compose.onNodeWithTag("membership:g2").performScrollTo().performClick()
        compose.onNodeWithTag("catalog_panel_close").performClick()
        compose.runOnIdle {
            assertTrue(fixture.layout.value.groups.all { "a" in it.members })
            assertEquals(listOf("a", "b"), fixture.layout.value.favorites)
        }
        enterOrganizing()
        moveAccessibly("favorite:a", "后移")
        compose.runOnIdle { assertEquals(listOf("b", "a"), fixture.layout.value.favorites) }
        homeTile("catalog_group:g1").performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { fixture.layout.value.groups.single { it.id == "g1" }.expanded } }
        compose.onNodeWithTag("member:g1:a").assertExists()
        moveAccessibly("member:g1:a", "后移")
        homeTile("catalog_group:g2").performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { !fixture.layout.value.groups.single { it.id == "g2" }.expanded } }
        compose.runOnIdle {
            assertTrue(fixture.layout.value.groups.single { it.id == "g1" }.expanded)
            assertFalse(fixture.layout.value.groups.single { it.id == "g2" }.expanded)
            assertEquals(listOf("b", "a"), fixture.layout.value.groups.single { it.id == "g1" }.members)
            assertEquals(listOf("a"), fixture.layout.value.groups.single { it.id == "g2" }.members)
        }
        compose.onNodeWithTag("member:g2:a").assertDoesNotExist()
        homeTile("catalog_group:g2").performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { fixture.layout.value.groups.single { it.id == "g2" }.expanded } }
        moveAccessibly("catalog_group:g1", "后移")
        compose.runOnIdle {
            assertEquals(listOf("g2", "g1"), fixture.layout.value.groups.map { it.id })
            assertTrue(fixture.layout.value.groups.all { it.expanded && "a" in it.members })
        }
        compose.onNodeWithTag("catalog_organize").performClick()
        homeTile("member:g1:a").performTouchInput { click() }
        compose.waitUntil(5_000) { compose.runOnIdle { fixture.opened.isNotEmpty() } }
        compose.runOnIdle { assertEquals(listOf("a"), fixture.opened) }
        assertLastTileClearsNavigation("member:g1:a")
    }

    @Test fun wholeTileDragUsesBothColumnsAndRows() {
        fixture.fillFavorites(18)
        render()
        enterOrganizing()
        homeTile("favorite:t0")
        val first = compose.onNodeWithTag("favorite:t0").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("favorite:t1").fetchSemanticsNode().boundsInRoot
        assertEquals("First two favorites must share a grid row", first.center.y, second.center.y, 1f)
        assertTrue(second.center.x > first.center.x)
        dragTile("favorite:t0", second.center)
        compose.runOnIdle { assertEquals(listOf("t1", "t0"), fixture.layout.value.favorites.take(2)) }

        val source = compose.onNodeWithTag("favorite:t0").fetchSemanticsNode().boundsInRoot
        val rowBelow = compose.onAllNodes(SemanticsMatcher("favorite tile") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("favorite:") == true
        }).fetchSemanticsNodes().filter { it.boundsInRoot.center.y > source.center.y + 1f }
            .minByOrNull { it.boundsInRoot.center.y } ?: error("Expected a second visible grid row")
        val targetId = rowBelow.config[SemanticsProperties.TestTag].removePrefix("favorite:")
        val before = compose.runOnIdle { fixture.layout.value.favorites }
        val destinationIndex = before.indexOf(targetId)
        dragTile("favorite:t0", rowBelow.boundsInRoot.center)
        compose.runOnIdle {
            assertEquals(before.toMutableList().apply { remove("t0"); add(destinationIndex, "t0") }, fixture.layout.value.favorites)
            assertTrue(fixture.opened.isEmpty())
        }
    }

    @Test fun draggingAtBottomEdgeScrollsPastTheInitiallyVisibleGrid() {
        fixture.fillFavorites(18)
        render()
        enterOrganizing()
        homeTile("favorite:t0")
        val list = compose.onNodeWithTag("catalog_home_list")
        val first = compose.onNodeWithTag("favorite:t0").fetchSemanticsNode().boundsInRoot
        val measuredTiles = favoriteTileNodes().filter { it.boundsInRoot.height > 0f }
        val columns = measuredTiles.count { kotlin.math.abs(it.boundsInRoot.center.y - first.center.y) < 1f }
        val nextRowCenter = measuredTiles.map { it.boundsInRoot.center.y }
            .filter { it > first.center.y + 1f }.minOrNull() ?: error("Expected two measured favorite rows")
        val rowPitch = nextRowCenter - first.center.y
        val safeHeight = compose.runOnIdle {
            val info = homeListState.layoutInfo
            info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
        }
        // The wide fixture reduces density in both dimensions. Size the collection from the
        // rendered row pitch and safe viewport, so it always extends beyond two visible screens.
        val favoriteCount = columns * (2 * ceil(safeHeight / rowPitch).toInt() + 1)
        compose.runOnIdle { fixture.fillFavorites(favoriteCount) }
        homeTile("favorite:t0")
        val sourceBounds = compose.onNodeWithTag("favorite:t0").fetchSemanticsNode().boundsInRoot
        val source = sourceBounds.center
        val viewport = list.fetchSemanticsNode().boundsInRoot
        val padding = compose.runOnIdle { homeListState.layoutInfo.beforeContentPadding to homeListState.layoutInfo.afterContentPadding }
        val lastInitiallyFullyVisible = favoriteTileNodes().filter {
            val bounds = it.boundsInRoot
            bounds.top >= viewport.top + padding.first && bounds.bottom <= viewport.bottom - padding.second &&
                bounds.height >= sourceBounds.height - 1f
        }.maxOf { it.config[SemanticsProperties.TestTag].removePrefix("favorite:t").toInt() }
        compose.runOnIdle {
            assertTrue("Measured fixture must have favorites beyond its initial viewport: count=$favoriteCount, columns=$columns, pitch=$rowPitch, safeHeight=$safeHeight, lastVisible=$lastInitiallyFullyVisible",
                lastInitiallyFullyVisible < favoriteCount - 1 && homeListState.canScrollForward)
        }
        val originalOffset = compose.runOnIdle { homeListState.firstVisibleItemIndex to homeListState.firstVisibleItemScrollOffset }
        val edge = Offset(viewport.center.x, viewport.bottom - 8f)
        compose.mainClock.autoAdvance = false
        var pointerDown = false
        try {
            list.performTouchInput { down(source - viewport.topLeft) }
            pointerDown = true
            compose.mainClock.advanceTimeBy(700)
            list.performTouchInput {
                advanceEventTime(700)
                moveTo(edge - viewport.topLeft, delayMillis = 250)
            }
            compose.mainClock.advanceTimeBy(1_200)
            val afterScroll = compose.runOnIdle { homeListState.firstVisibleItemIndex to homeListState.firstVisibleItemScrollOffset }
            assertTrue("Holding a dragged tile at the edge must scroll: $originalOffset -> $afterScroll; count=$favoriteCount, columns=$columns, pitch=$rowPitch, safeHeight=$safeHeight, viewport=$viewport",
                afterScroll != originalOffset)
        } finally {
            try { if (pointerDown) list.performTouchInput { up() } }
            finally { compose.mainClock.autoAdvance = true }
        }
        compose.runOnIdle {
            assertTrue("Drop must move past the initially visible favorites (last=$lastInitiallyFullyVisible)",
                fixture.layout.value.favorites.indexOf("t0") > lastInitiallyFullyVisible)
            assertTrue(fixture.opened.isEmpty())
        }
    }

    @Test fun groupHeaderDragTemporarilyFoldsBodiesAndOnlyMovingChangesOrder() {
        fixture.tools.value = fixture.tools.value.map { it.copy(lastOpenedAt = null) }
        fixture.layout.value = CatalogLayout(favorites = listOf("a"), groups = listOf(
            CatalogGroup("g1", "工作", listOf("a"), expanded = true),
            CatalogGroup("g2", "常用", listOf("b"), expanded = true),
        ))
        render()
        enterOrganizing()

        holdGroupHeader(moveToOtherGroup = false)
        compose.runOnIdle {
            assertEquals(listOf("g1", "g2"), fixture.layout.value.groups.map { it.id })
            assertTrue(fixture.layout.value.groups.all { it.expanded })
        }
        val list = compose.onNodeWithTag("catalog_home_list")
        list.performScrollToNode(hasTestTag("member:g1:a"))
        compose.onNodeWithTag("member:g1:a").assertIsDisplayed()

        holdGroupHeader(moveToOtherGroup = true)
        compose.runOnIdle {
            assertEquals(listOf("g2", "g1"), fixture.layout.value.groups.map { it.id })
            assertTrue(fixture.layout.value.groups.all { it.expanded })
            assertEquals(listOf("a"), fixture.layout.value.groups.single { it.id == "g1" }.members)
            assertEquals(listOf("b"), fixture.layout.value.groups.single { it.id == "g2" }.members)
            assertEquals(listOf("a"), fixture.layout.value.favorites)
            assertTrue(fixture.opened.isEmpty())
        }
        list.performScrollToNode(hasTestTag("member:g1:a"))
        compose.onNodeWithTag("member:g1:a").assertIsDisplayed()
        list.performScrollToNode(hasTestTag("member:g2:b"))
        compose.onNodeWithTag("member:g2:b").assertIsDisplayed()
    }

    @Test fun longTitlesRemainAccessibleAndLastTileClearsNavigation() {
        fixture.tools.value = listOf(CatalogTool("long", "这是一个需要跨行显示并保持完整可访问名称的非常长工具标题", 1, "1.0", 1, null))
        fixture.layout.value = CatalogLayout(favorites = listOf("long"), groups = listOf(CatalogGroup("last", "末尾分组", listOf("long"))))
        render()
        homeTile("favorite:long").assertHasClickAction()
            .assertTextContains(fixture.tools.value.single().name, substring = true)
        assertLastTileClearsNavigation("member:last:long")
    }

    @Test fun cancellingAGroupDragKeepsLayoutAndAllowsOpeningAToolAfterwards() {
        fixture.tools.value = fixture.tools.value.map { it.copy(lastOpenedAt = null) }
        val original = CatalogLayout(favorites = listOf("a"), groups = listOf(
            CatalogGroup("g1", "工作", listOf("a"), expanded = true),
            CatalogGroup("g2", "常用", listOf("b"), expanded = true),
        ))
        fixture.layout.value = original
        render()
        enterOrganizing()

        holdGroupHeader(moveToOtherGroup = true, cancelGesture = true)

        compose.runOnIdle {
            assertEquals(original, fixture.layout.value)
            assertTrue(isEditing)
            assertTrue(fixture.opened.isEmpty())
        }
        compose.onNodeWithTag("catalog_organize").assertTextContains("完成")
        val list = compose.onNodeWithTag("catalog_home_list")
        list.performScrollToNode(hasTestTag("member:g1:a"))
        compose.onNodeWithTag("member:g1:a").assertIsDisplayed()
        list.performScrollToNode(hasTestTag("member:g2:b"))
        compose.onNodeWithTag("member:g2:b").assertIsDisplayed()
        compose.onNodeWithTag("catalog_organize").performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { !isEditing } }
        homeTile("member:g1:a").performTouchInput { click() }
        compose.waitUntil(5_000) { compose.runOnIdle { fixture.opened.isNotEmpty() } }
        compose.runOnIdle { assertEquals(listOf("a"), fixture.opened) }
    }

    private fun openHomeMenu() {
        compose.onNodeWithTag("catalog_home_overflow").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("catalog_home_menu").fetchSemanticsNodes().size == 1 }
        val menuRoot = requireNotNull(compose.onNodeWithTag("catalog_home_menu").fetchSemanticsNode().root as? ViewRootForTest)
        // Native Back must target the popup window, rather than the Activity behind it.
        compose.waitUntil(5_000) { compose.runOnUiThread { menuRoot.view.hasWindowFocus() } }
    }

    private fun awaitHomeMenuClosed() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("catalog_home_menu").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("catalog_home_menu").assertDoesNotExist()
    }

    private fun enterOrganizing() {
        openHomeMenu()
        compose.onNodeWithTag("catalog_organize").assertTextContains("整理首页").performClick()
        awaitHomeMenuClosed()
        compose.waitUntil(5_000) { compose.runOnIdle { isEditing } }
        compose.onNodeWithTag("catalog_organize").assertTextContains("完成")
    }

    private fun assertNoHomeMenuAction(original: CatalogLayout) {
        compose.runOnIdle {
            assertEquals(0, importRequests)
            assertEquals(0, createGroupRequests)
            assertFalse(isEditing)
            assertFalse(isCreatingGroup)
            assertEquals(MainDestination.Home, destination.value)
            assertEquals(original, fixture.layout.value)
            assertTrue(fixture.opened.isEmpty())
            assertTrue(fixture.managed.isEmpty())
        }
        compose.onNodeWithTag("catalog_group_editor").assertDoesNotExist()
        compose.onNodeWithTag("catalog_tool_options").assertDoesNotExist()
    }

    private fun assertLastTileClearsNavigation(tag: String) {
        val lastIndex = compose.runOnIdle { homeListState.layoutInfo.totalItemsCount - 1 }
        compose.onNodeWithTag("catalog_home_list").performScrollToIndex(lastIndex)
        compose.runOnIdle { assertFalse(homeListState.canScrollForward) }
        val last = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        if (style == ToolBoxThemeStyle.LiquidGlass && !wideLargeText) {
            val navigation = compose.onNodeWithTag(HostTestTags.BottomNavigationContainer).fetchSemanticsNode().boundsInRoot
            assertTrue("Final tile must clear floating navigation: $last / $navigation", last.bottom <= navigation.top)
        }
    }

    private fun homeTile(tag: String): SemanticsNodeInteraction {
        val target = compose.onNodeWithTag(tag).performScrollTo()
        val list = compose.onNodeWithTag("catalog_home_list")
        val listBounds = list.fetchSemanticsNode().boundsInRoot
        val targetBounds = target.fetchSemanticsNode().boundsInRoot
        val rowIndex = compose.runOnIdle {
            val info = homeListState.layoutInfo
            info.visibleItemsInfo.firstOrNull { item ->
                val top = listBounds.top + item.offset - info.viewportStartOffset
                targetBounds.center.y >= top && targetBounds.center.y < top + item.size
            }?.index
        } ?: error("Tile $tag must belong to a measured lazy row")
        list.performScrollToIndex(rowIndex)
        target.assertIsDisplayed()
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val point = target.fetchSemanticsNode().boundsInRoot.center
        val padding = compose.runOnIdle { homeListState.layoutInfo.beforeContentPadding to homeListState.layoutInfo.afterContentPadding }
        assertTrue("Tile must clear top chrome: $tag $point / $bounds / $padding", point.y >= bounds.top + padding.first)
        assertTrue("Tile must clear bottom chrome: $tag $point / $bounds / $padding", point.y <= bounds.bottom - padding.second)
        return target
    }

    private fun moveAccessibly(tag: String, label: String) {
        val node = compose.onNodeWithTag(tag).performScrollTo().fetchSemanticsNode()
        compose.runOnIdle { assertTrue(node.config[SemanticsActions.CustomActions].single { it.label == label }.action()) }
    }

    private fun favoriteTileNodes() = compose.onAllNodes(SemanticsMatcher("favorite tile") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("favorite:") == true
    }).fetchSemanticsNodes()

    private fun dragTile(sourceTag: String, target: Offset) {
        val source = compose.onNodeWithTag(sourceTag).fetchSemanticsNode().boundsInRoot.center
        val list = compose.onNodeWithTag("catalog_home_list")
        val origin = list.fetchSemanticsNode().boundsInRoot.topLeft
        list.performTouchInput {
            down(source - origin)
            advanceEventTime(700)
            moveTo(target - origin, delayMillis = 250)
            up()
        }
    }

    private fun holdGroupHeader(moveToOtherGroup: Boolean, cancelGesture: Boolean = false) {
        val header = homeTile("catalog_group:g1").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("member:g1:a").assertExists()
        val list = compose.onNodeWithTag("catalog_home_list")
        val origin = list.fetchSemanticsNode().boundsInRoot.topLeft
        // Stay inside the header's main target and away from its separate edit button.
        val source = Offset(header.left + header.width / 3f, header.center.y)
        compose.mainClock.autoAdvance = false
        var pointerDown = false
        try {
            list.performTouchInput { down(source - origin) }
            pointerDown = true
            compose.mainClock.advanceTimeBy(700)
            compose.onNodeWithTag("member:g1:a").assertDoesNotExist()
            compose.onNodeWithTag("member:g2:b").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(listOf("g1", "g2"), fixture.layout.value.groups.map { it.id })
                assertTrue("Temporary drag folding must not persist collapsed groups", fixture.layout.value.groups.all { it.expanded })
            }
            if (moveToOtherGroup) {
                // Folding changes the target's location; measure it after the fold has rendered.
                val target = compose.onNodeWithTag("catalog_group:g2").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center
                val currentOrigin = list.fetchSemanticsNode().boundsInRoot.topLeft
                list.performTouchInput {
                    advanceEventTime(700)
                    moveTo(target - currentOrigin, delayMillis = 250)
                }
                compose.mainClock.advanceTimeByFrame()
            }
        } finally {
            try {
                if (pointerDown) list.performTouchInput {
                    if (!moveToOtherGroup) advanceEventTime(700)
                    if (cancelGesture) cancel() else up()
                }
            }
            finally { compose.mainClock.autoAdvance = true }
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0} wideLargeText={1}") fun variants() = listOf(
            arrayOf<Any>(ToolBoxThemeStyle.Miuix, false), arrayOf<Any>(ToolBoxThemeStyle.LiquidGlass, false),
            arrayOf<Any>(ToolBoxThemeStyle.Miuix, true), arrayOf<Any>(ToolBoxThemeStyle.LiquidGlass, true),
        )
    }
}
internal fun pressCatalogBack() = InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

/** Persistence acknowledgements stay explicit so panel tests can hold or fail a save. */
internal class CatalogBehaviorFixture {
    val tools = mutableStateOf(listOf(
        CatalogTool("a", "Alpha", 1, "1.0.0", 1, 2, 1), CatalogTool("b", "Beta", 1, "1.0.0", 1, 1, 2),
    ))
    val layout = mutableStateOf(CatalogLayout(favorites = listOf("a", "b"),
        groups = listOf(CatalogGroup("g1", "工作", listOf("a"), false), CatalogGroup("g2", "常用"))))
    val writes = mutableStateOf<Map<String, CatalogLayoutWriteStatus>>(emptyMap())
    val opened = mutableListOf<String>()
    val managed = mutableListOf<String>()
    val uninstallRequested = mutableListOf<String>()
    val uninstallConfirmation = mutableStateOf<UninstallConfirmation?>(null)
    val pending = linkedMapOf<String, CatalogAction>()
    var holdWrites = false

    fun state() = CatalogUiState(isLoaded = true, layout = layout.value, layoutWrites = writes.value,
        uninstallConfirmation = uninstallConfirmation.value).withCatalogTools(tools.value)

    fun fillFavorites(count: Int) {
        tools.value = (0 until count).map { CatalogTool("t$it", "工具 $it", 1, "1.0", 1, null) }
        layout.value = CatalogLayout(favorites = tools.value.map { it.toolId })
    }

    fun action(action: CatalogAction) {
        if (action is CatalogAction.ForgetLayoutWrite) {
            writes.value -= action.operationId
            return
        }
        val id = action.operationId()
        if (id != null) {
            if (writes.value[id] == CatalogLayoutWriteStatus.Writing) return
            pending[id] = action
            writes.value += id to CatalogLayoutWriteStatus.Writing
            if (!holdWrites) complete(id)
        } else apply(action)
    }

    fun complete(id: String, failure: String? = null) {
        val action = requireNotNull(pending.remove(id))
        if (failure == null) apply(action)
        if (id in writes.value) writes.value += id to if (failure == null) CatalogLayoutWriteStatus.Succeeded
            else CatalogLayoutWriteStatus.Failed("CATALOG_LAYOUT_WRITE", failure)
    }

    private fun apply(action: CatalogAction) {
        val current = layout.value
        layout.value = when (action) {
            is CatalogAction.SetFavorite -> current.favorite(action.toolId, action.selected)
            is CatalogAction.SetGroupMembership -> current.groupMembership(action.groupId, action.toolId, action.selected)
            is CatalogAction.MoveFavorite -> current.moveFavorite(action.toolId, action.offset)
            is CatalogAction.MoveGroup -> current.moveGroup(action.groupId, action.offset)
            is CatalogAction.MoveMember -> current.moveMember(action.groupId, action.toolId, action.offset)
            is CatalogAction.SetGroupExpanded -> current.copy(groups = current.groups.map { if (it.id == action.groupId) it.copy(expanded = action.expanded) else it })
            is CatalogAction.SetSort -> current.copy(sort = action.sort)
            is CatalogAction.SaveGroup -> {
                val value = CatalogGroup(action.groupId ?: "new", action.name, action.members)
                if (action.groupId == null) current.copy(groups = current.groups + value)
                else current.copy(groups = current.groups.map { if (it.id == action.groupId) value.copy(expanded = it.expanded) else it })
            }
            is CatalogAction.CreateGroup -> current.copy(groups = current.groups + CatalogGroup("new", action.name))
            is CatalogAction.RenameGroup -> current.copy(groups = current.groups.map { if (it.id == action.groupId) it.copy(name = action.name) else it })
            is CatalogAction.DeleteGroup -> current.copy(groups = current.groups.filterNot { it.id == action.groupId })
            is CatalogAction.RequestRuntimeLaunch -> { opened += action.toolId; current }
            is CatalogAction.RequestUninstall -> {
                uninstallRequested += action.toolId
                uninstallConfirmation.value = UninstallConfirmation(action.toolId, tools.value.single { it.toolId == action.toolId }.name)
                current
            }
            CatalogAction.CancelUninstall -> { uninstallConfirmation.value = null; current }
            else -> current
        }
    }
}
private fun CatalogAction.operationId(): String? = when (this) {
    is CatalogAction.SetSort -> operationId
    is CatalogAction.SetFavorite -> operationId
    is CatalogAction.CreateGroup -> operationId
    is CatalogAction.SaveGroup -> operationId
    is CatalogAction.RenameGroup -> operationId
    is CatalogAction.DeleteGroup -> operationId
    is CatalogAction.SetGroupMembership -> operationId
    is CatalogAction.SetGroupExpanded -> operationId
    is CatalogAction.MoveFavorite -> operationId
    is CatalogAction.MoveGroup -> operationId
    is CatalogAction.MoveMember -> operationId
    else -> null
}
