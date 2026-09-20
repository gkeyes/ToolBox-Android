package io.toolbox.host

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
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

@RunWith(Parameterized::class)
class CatalogHomeBehaviorTest(private val style: ToolBoxThemeStyle, private val wideLargeText: Boolean) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val fixture = CatalogBehaviorFixture()
    private lateinit var homeListState: LazyListState
    private val destination = mutableStateOf(MainDestination.Home)
    private var isEditing = false

    private fun render(restoration: StateRestorationTester? = null) {
        val content: @Composable () -> Unit = {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (wideLargeText) Density(density.density * 0.5f, 1.6f) else density) {
                ToolBoxTheme(style = style, reduceTransparency = true) {
                    var editing by rememberSaveable { mutableStateOf(false) }
                    val homeScroll = rememberLazyListState()
                    val toolsScroll = rememberLazyListState()
                    SideEffect { homeListState = homeScroll; isEditing = editing }
                    PrimaryScreen(destination.value, {
                        editing = false
                        destination.value = it
                    }, destination.value.label, onImport = {}, organizeEditing = editing,
                        onOrganize = if (destination.value == MainDestination.Home) ({ editing = !editing }) else null,
                    ) { padding, _ ->
                        if (destination.value != MainDestination.Settings) ToolManagerContent(
                            state = fixture.state(), importState = ImportUiState(),
                            listState = if (destination.value == MainDestination.Home) homeScroll else toolsScroll,
                            contentPadding = padding, onAction = fixture::action,
                            onImport = {}, onInstallExamples = {}, onDismissImport = {}, onOpenDetails = { fixture.managed += it },
                            home = destination.value == MainDestination.Home,
                            editing = editing, onEditingChange = { editing = it },
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
        compose.onNodeWithTag("catalog_organize").performClick()
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
        compose.onNodeWithTag("catalog_organize").performClick()
        compose.onNodeWithTag(HostTestTags.BottomSettings).performClick()
        compose.onNodeWithText("设置内容").assertExists()
        compose.runOnIdle { assertFalse(isEditing) }
        compose.onNodeWithTag(HostTestTags.BottomHome).performClick()
        compose.onNodeWithTag("catalog_organize").performClick()
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
        compose.onNodeWithTag("catalog_organize").performClick()
        homeTile("favorite:a").performTouchInput { click() }
        compose.onNodeWithTag("catalog_tool_options").assertExists()
        compose.runOnIdle { assertEquals(listOf("a"), fixture.opened) }
        pressCatalogBack()
        compose.onNodeWithTag("catalog_organize").performClick()
        homeTile("favorite:b").performTouchInput { click() }
        compose.waitUntil(5_000) { compose.runOnIdle { fixture.opened.size == 2 } }
        compose.runOnIdle { assertEquals(listOf("a", "b"), fixture.opened) }
    }

    @Test fun organizingStateRestoresAndFinishingRestoresImport() {
        val restoration = StateRestorationTester(object : ComposeContentTestRule by compose {
            override fun setContent(composable: @Composable () -> Unit) { compose.activity.setContent(content = composable) }
        })
        render(restoration)
        compose.onNodeWithTag(HostTestTags.ImportFab).assertIsDisplayed()
        compose.onNodeWithTag("catalog_organize").performClick()
        compose.onNodeWithTag("catalog_organize").assertTextContains("完成")
        compose.onNodeWithTag(HostTestTags.ImportFab).assertDoesNotExist()

        restoration.emulateSavedInstanceStateRestore()

        compose.runOnIdle { assertTrue(isEditing) }
        compose.onNodeWithTag("catalog_organize").assertTextContains("完成")
        compose.onNodeWithTag(HostTestTags.ImportFab).assertDoesNotExist()
        compose.onNodeWithTag("catalog_organize").performClick()
        compose.runOnIdle { assertFalse(isEditing) }
        compose.onNodeWithTag("catalog_organize").assertTextContains("整理")
        compose.onNodeWithTag(HostTestTags.ImportFab).assertIsDisplayed()
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
        compose.onNodeWithTag("catalog_organize").performClick()
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
        compose.onNodeWithTag("catalog_organize").performClick()
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
        fixture.fillFavorites(48)
        render()
        compose.onNodeWithTag("catalog_organize").performClick()
        homeTile("favorite:t0")
        val source = compose.onNodeWithTag("favorite:t0").fetchSemanticsNode().boundsInRoot.center
        val list = compose.onNodeWithTag("catalog_home_list")
        val viewport = list.fetchSemanticsNode().boundsInRoot
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
            assertTrue("Holding a dragged tile at the edge must scroll: $originalOffset -> $afterScroll", afterScroll != originalOffset)
        } finally {
            try { if (pointerDown) list.performTouchInput { up() } }
            finally { compose.mainClock.autoAdvance = true }
        }
        compose.runOnIdle {
            assertTrue(fixture.layout.value.favorites.indexOf("t0") > 1)
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
        compose.onNodeWithTag("catalog_organize").performClick()

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

    private fun holdGroupHeader(moveToOtherGroup: Boolean) {
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
                    up()
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
