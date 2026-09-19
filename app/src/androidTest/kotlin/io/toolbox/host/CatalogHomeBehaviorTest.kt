package io.toolbox.host

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
    private val alpha = CatalogTool("a", "Alpha", 1, "1.0.0", 1, 2, 1)
    private val beta = CatalogTool("b", "Beta", 1, "1.0.0", 1, 1, 2)
    private val layout = mutableStateOf(CatalogLayout(favorites = listOf("a", "b"),
        groups = listOf(CatalogGroup("g1", "工作", listOf("a"), false), CatalogGroup("g2", "常用"))))
    private val opened = mutableListOf<String>()
    private val destination = mutableStateOf(MainDestination.Home)

    private fun render() {
        compose.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (wideLargeText) Density(density.density * 0.5f, 1.6f) else density) {
                ToolBoxTheme(style = style, reduceTransparency = true) {
                    val homeScroll = rememberLazyListState()
                    val toolsScroll = rememberLazyListState()
                    PrimaryScreen(destination.value, { destination.value = it }, destination.value.label, onImport = {}) { padding, _ ->
                        if (destination.value != MainDestination.Settings) ToolManagerContent(
                            state = CatalogUiState(isLoaded = true, layout = layout.value).withCatalogTools(listOf(alpha, beta)),
                            importState = ImportUiState(), listState = if (destination.value == MainDestination.Home) homeScroll else toolsScroll,
                            contentPadding = padding, onAction = ::action,
                            onImport = {}, onInstallExamples = {}, onDismissImport = {}, onOpenDetails = {},
                            home = destination.value == MainDestination.Home,
                        ) else AppText("设置内容")
                    }
                }
            }
        }
    }

    @Test fun threeDestinationsKeepQuickSectionsOnHomeAndSortOnAllTools() {
        render()
        compose.onNodeWithTag(HostTestTags.BottomHome).assertExists()
        compose.onNodeWithTag(HostTestTags.BottomTools).performClick()
        compose.onNodeWithText("搜索工具").assertExists()
        compose.onNodeWithText("排序").assertExists()
        compose.onNodeWithText("最近使用").assertDoesNotExist()
        compose.onNodeWithText("收藏").assertDoesNotExist()
        compose.onNodeWithTag(HostTestTags.BottomSettings).performClick()
        compose.onNodeWithText("设置内容").assertExists()
        compose.onNodeWithTag(HostTestTags.BottomHome).performClick()
        compose.onNodeWithText("最近使用").assertIsDisplayed()
        compose.onNodeWithText("搜索工具").assertDoesNotExist()
        compose.onNodeWithTag("favorite:a").assertExists()
    }

    @Test fun multipleGroupsFavoritesAndAccessibleOrderingStayIndependent() {
        render()
        compose.onNodeWithTag(HostTestTags.BottomTools).performClick()
        compose.onNodeWithContentDescription("Alpha的收藏、分组与管理").performClick()
        compose.onNodeWithTag("membership:g2").performClick()
        compose.onNodeWithText("完成", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertTrue(layout.value.groups.all { "a" in it.members })
            assertEquals(listOf("a", "b"), layout.value.favorites)
        }
        compose.onNodeWithTag(HostTestTags.BottomHome).performClick()
        compose.onNodeWithText("编辑首页").performClick()
        compose.onNodeWithContentDescription("后移Alpha").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("b", "a"), layout.value.favorites) }
        compose.onNodeWithTag("catalog_group:g1").performScrollTo().performClick()
        compose.onNodeWithTag("member:g1:a").assertExists()
        compose.onNode(hasAnyAncestor(hasTestTag("member:g1:a")) and hasClickAction() and hasText("Alpha"))
            .performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("a"), opened) }
    }

    @Test fun longPressDragReordersMeasuredFavoriteRows() {
        render()
        compose.onNodeWithText("编辑首页").performClick()
        compose.onNodeWithTag("drag:favorite:a").performScrollTo()
        val first = compose.onNodeWithTag("favorite:a").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("favorite:b").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("drag:favorite:a").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, second.center.y - first.center.y), delayMillis = 250)
            up()
        }
        compose.runOnIdle { assertEquals(listOf("b", "a"), layout.value.favorites) }
    }

    @Test fun createRenameDeleteGroupKeepsToolsAndOtherMemberships() {
        render()
        compose.onNodeWithText("新建分组").performClick()
        compose.onNodeWithTag("catalog_group_name").performTextInput("工作")
        compose.onNodeWithTag("group-tool:b").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals(3, layout.value.groups.size)
            assertEquals(listOf("b"), layout.value.groups.single { it.id == "new" }.members)
        }
        compose.onNodeWithContentDescription("编辑分组常用").performScrollTo().performClick()
        compose.onNodeWithTag("group-tool:b").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(layout.value.groups.single { it.id == "g2" }.members.isEmpty()) }
        compose.onNodeWithContentDescription("编辑分组常用").performScrollTo().performClick()
        compose.onNodeWithTag("catalog_group_name").performTextReplacement("新的名称")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals("新的名称", layout.value.groups.single { it.id == "g2" }.name) }
        compose.onNodeWithContentDescription("编辑分组新的名称").performScrollTo().performClick()
        compose.onNodeWithText("删除分组").performScrollTo().performClick()
        compose.onNodeWithText("确认删除分组").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("a", "b"), layout.value.favorites)
            assertEquals(listOf("a"), layout.value.groups.single { it.id == "g1" }.members)
            assertFalse(layout.value.groups.any { it.id == "g2" })
        }
    }

    private fun action(action: CatalogAction) {
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
            else -> current
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0} wideLargeText={1}") fun variants() = listOf(
            arrayOf<Any>(ToolBoxThemeStyle.Miuix, false), arrayOf<Any>(ToolBoxThemeStyle.LiquidGlass, false),
            arrayOf<Any>(ToolBoxThemeStyle.Miuix, true), arrayOf<Any>(ToolBoxThemeStyle.LiquidGlass, true),
        )
    }
}
