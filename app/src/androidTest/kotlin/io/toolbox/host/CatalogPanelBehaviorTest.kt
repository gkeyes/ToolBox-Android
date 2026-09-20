package io.toolbox.host

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.core.ui.theme.ToolBoxThemeStyle
import io.toolbox.host.catalog.CatalogAction
import io.toolbox.host.catalog.CatalogLayoutWriteStatus
import io.toolbox.host.ui.AppText
import io.toolbox.host.ui.CatalogFavoritesPicker
import io.toolbox.host.ui.CatalogGroupEditor
import io.toolbox.host.ui.CatalogToolOptions
import io.toolbox.host.ui.ToolDetailScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.ceil

@RunWith(Parameterized::class)
class CatalogPanelBehaviorTest(private val style: ToolBoxThemeStyle, private val wideLargeText: Boolean) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val fixture = CatalogBehaviorFixture()
    private val groupId = mutableStateOf<String?>(null)
    private val selectedToolId = mutableStateOf<String?>(null)
    private val managedToolId = mutableStateOf<String?>(null)
    private val pickingFavorites = mutableStateOf(false)
    private var targetDensity = 1f

    private fun render(restoration: StateRestorationTester? = null) {
        val content: @Composable () -> Unit = {
            val density = LocalDensity.current
            val panelDensity = if (wideLargeText) Density(density.density * 0.5f, 1.6f) else density
            SideEffect { targetDensity = panelDensity.density }
            CompositionLocalProvider(LocalDensity provides panelDensity) {
                ToolBoxTheme(style = style, reduceTransparency = true) {
                    val state = fixture.state()
                    val detail = managedToolId.value
                    if (detail == null) AppText("工具列表")
                    else ToolDetailScreen(detail, state, fixture::action, { managedToolId.value = null }, {}, {})
                    groupId.value?.let { id ->
                        CatalogGroupEditor(state.layout.groups.firstOrNull { it.id == id }, state, creating = id.isEmpty(),
                            onAction = fixture::action, onDismiss = { groupId.value = null })
                    }
                    selectedToolId.value?.let { id ->
                        state.tools.firstOrNull { it.toolId == id }?.let { tool ->
                            CatalogToolOptions(tool, state, fixture::action, onDismiss = { selectedToolId.value = null },
                                onManage = { fixture.managed += id; managedToolId.value = id })
                        }
                    }
                    if (pickingFavorites.value) CatalogFavoritesPicker(state, fixture::action, { pickingFavorites.value = false })
                }
            }
        }
        if (restoration == null) compose.activity.setContent(content = content)
        else restoration.setContent(content)
    }

    @Test fun saveWaitsForItsOwnResultAndFailedDraftSurvivesStateRestoration() {
        fixture.holdWrites = true
        groupId.value = "g1"
        // MainActivity already owns content; keep the production Activity while delegating only
        // installation of this test composition to the normal Activity.setContent entry point.
        val restoration = StateRestorationTester(object : ComposeContentTestRule by compose {
            override fun setContent(composable: @Composable () -> Unit) { compose.activity.setContent(content = composable) }
        })
        render(restoration)
        compose.onNodeWithTag("catalog_group_name").performTextReplacement("保留的草稿")
        finishGroupNameInput()
        groupMember("b").performClick()
        compose.onNodeWithTag("catalog_group_save").performClick()
        val operation = compose.runOnIdle {
            assertEquals(1, fixture.pending.size)
            val entry = fixture.pending.entries.single()
            assertTrue(entry.value is CatalogAction.SaveGroup)
            assertEquals("g1", (entry.value as CatalogAction.SaveGroup).groupId)
            assertEquals(listOf("a", "b"), (entry.value as CatalogAction.SaveGroup).members)
            assertEquals("工作", fixture.layout.value.groups.first().name)
            fixture.writes.value += "another-editor" to CatalogLayoutWriteStatus.Succeeded
            entry.key
        }
        compose.onNodeWithTag("catalog_group_editor").assertExists()
        compose.onNodeWithTag("catalog_group_save").assertIsNotEnabled()
        compose.runOnIdle { fixture.complete(operation, "存储失败，请重试。") }
        compose.onNodeWithTag("catalog_group_error").assertTextContains("存储失败，请重试。")
        compose.onNodeWithTag("catalog_group_save").assertIsEnabled()
        groupEditorRow("name", "catalog_group_name").assertTextEquals("保留的草稿")
        groupMember("b").assertIsOn()

        restoration.emulateSavedInstanceStateRestore()
        groupEditorRow("name", "catalog_group_name").assertTextEquals("保留的草稿")
        groupMember("b").assertIsOn()
        compose.onNodeWithTag("catalog_group_save").performClick()
        compose.runOnIdle {
            assertEquals(1, fixture.pending.size)
            fixture.complete(fixture.pending.keys.single())
        }
        compose.onNodeWithTag("catalog_group_editor").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("保留的草稿", fixture.layout.value.groups.first().name)
            assertEquals(listOf("a", "b"), fixture.layout.value.groups.first().members)
            assertFalse(fixture.layout.value.groups.first().expanded)
        }
    }

    @Test fun createCancelAndDeleteKeepOtherGroupsToolsAndFavorites() {
        groupId.value = ""
        render()
        compose.onNodeWithTag("catalog_group_name").performTextInput("工作")
        finishGroupNameInput()
        groupMember("b").performClick()
        compose.onNodeWithTag("catalog_group_save").performClick()
        compose.onNodeWithTag("catalog_group_editor").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("b"), fixture.layout.value.groups.single { it.id == "new" }.members)
            groupId.value = "g2"
        }
        groupMember("b").performClick()
        compose.onNodeWithTag("catalog_group_cancel").performClick()
        compose.runOnIdle {
            assertTrue(fixture.layout.value.groups.single { it.id == "g2" }.members.isEmpty())
            groupId.value = "g2"
        }
        groupEditorRow("delete", "catalog_group_delete").performClick()
        compose.onNodeWithTag("catalog_group_delete_confirmation").assertExists()
        // Back leaves the confirmation page and keeps the editor, rather than stacking a dialog.
        pressCatalogBack()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("catalog_group_editor").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("catalog_group_editor").assertExists()
        compose.onNodeWithTag("catalog_group_delete_confirmation").assertDoesNotExist()
        groupEditorRow("delete", "catalog_group_delete").performClick()
        compose.onNodeWithTag("catalog_group_delete_confirm").performClick()
        compose.onNodeWithTag("catalog_group_editor").assertDoesNotExist()
        compose.onNodeWithTag("catalog_group_delete_confirmation").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(fixture.layout.value.groups.any { it.id == "g2" })
            assertEquals(listOf("a"), fixture.layout.value.groups.single { it.id == "g1" }.members)
            assertEquals(listOf("a", "b"), fixture.layout.value.favorites)
            assertEquals(listOf("a", "b"), fixture.tools.value.map { it.toolId })
        }
    }

    @Test fun groupPickerBackAndManagementPreserveTheSelectedUninstallTarget() {
        selectedToolId.value = "b"
        render()
        compose.onNodeWithTag("catalog_tool_groups").performClick()
        compose.onNodeWithTag("catalog_group_picker").assertExists()
        pressCatalogBack()
        compose.onNodeWithTag("catalog_tool_options").assertExists()
        compose.onNodeWithTag("catalog_group_picker").assertDoesNotExist()
        compose.onNodeWithTag("catalog_tool_manage").performScrollTo().performClick()
        compose.onNodeWithTag("catalog_tool_options").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("b"), fixture.managed) }
        compose.onNodeWithText("删除工具").performScrollTo().performClick()
        compose.onNodeWithText("删除 Beta？").assertExists()
        compose.runOnIdle {
            assertEquals(listOf("b"), fixture.uninstallRequested)
            assertEquals("b", fixture.uninstallConfirmation.value?.toolId)
        }
        compose.onNodeWithText("取消", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertNull(fixture.uninstallConfirmation.value)
            assertEquals(listOf("a", "b"), fixture.tools.value.map { it.toolId })
        }
    }

    @Test fun favoriteAndMembershipFailuresRemainLocalAndRetryWithoutClosingThePanel() {
        fixture.holdWrites = true
        selectedToolId.value = "a"
        render()
        compose.onNodeWithTag("catalog_tool_favorite").performClick()
        compose.onNodeWithTag("catalog_tool_favorite").assertIsNotEnabled()
        val favoriteWrite = compose.runOnIdle { fixture.pending.keys.single() }
        // An unrelated group selection remains usable while the favorite write is pending.
        compose.onNodeWithTag("catalog_tool_groups").performClick()
        compose.onNodeWithTag("membership:g2").performScrollTo().performClick()
        val membershipWrite = compose.runOnIdle {
            assertEquals(2, fixture.pending.size)
            fixture.pending.keys.single { it != favoriteWrite }
        }
        compose.runOnIdle { fixture.complete(membershipWrite) }
        compose.onNodeWithTag("membership:g2").assertIsOn()
        compose.onNodeWithTag("catalog_panel_back").performClick()
        compose.onNodeWithTag("catalog_tool_favorite").assertIsNotEnabled()
        compose.runOnIdle {
            assertTrue("a" in fixture.layout.value.favorites)
            fixture.complete(favoriteWrite, "收藏未保存，请重试。")
        }
        compose.onNodeWithText("收藏未保存，请重试。").assertExists()
        compose.onNodeWithTag("catalog_tool_favorite").assertIsEnabled().performClick()
        compose.runOnIdle { fixture.complete(fixture.pending.keys.single()) }
        compose.onNodeWithTag("catalog_tool_options").assertExists()
        compose.runOnIdle {
            assertEquals(listOf("b"), fixture.layout.value.favorites)
            assertTrue(fixture.layout.value.groups.all { "a" in it.members })
        }
    }

    @Test fun favoritesPickerSearchRetainsSelectionsAndKeepsThePanelOpen() {
        fixture.layout.value = fixture.layout.value.copy(favorites = emptyList())
        pickingFavorites.value = true
        render()
        compose.onNodeWithTag("favorite-tool:a").performScrollTo().performClick()
        compose.onNodeWithTag("favorite-tool:b").performScrollTo().performClick()
        compose.onNodeWithTag("catalog_favorites_picker").assertExists()
        compose.runOnIdle { assertEquals(listOf("a", "b"), fixture.layout.value.favorites) }
        compose.onNodeWithTag("catalog_favorites_search").performScrollTo()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("catalog_favorites_search")), useUnmergedTree = true)
            .performTextInput("Beta")
        compose.onNodeWithTag("favorite-tool:a").assertDoesNotExist()
        compose.onNodeWithTag("favorite-tool:b").assertIsOn()
        compose.onNodeWithTag("catalog_panel_close").performClick()
        compose.onNodeWithTag("catalog_favorites_picker").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("a", "b"), fixture.layout.value.favorites) }
    }

    @Test fun compactShortcutsKeepSeparateTouchTargetsAndEveryActionWorks() {
        selectedToolId.value = "a"
        render()
        val favorite = compose.onNodeWithTag("catalog_tool_favorite").assertIsDisplayed().assertHasClickAction()
        val groups = compose.onNodeWithTag("catalog_tool_groups").assertIsDisplayed().assertHasClickAction()
        val first = favorite.fetchSemanticsNode()
        val second = groups.fetchSemanticsNode()
        val minimumTarget = compose.runOnIdle { 48f * targetDensity }
        listOf(first, second).forEach { node ->
            assertTrue("Shortcut must retain a 48 dp width", node.size.width + 1f >= minimumTarget)
            assertTrue("Shortcut must retain a 48 dp height", node.size.height + 1f >= minimumTarget)
        }
        assertTrue("Shortcut touch targets must not overlap", first.boundsInRoot.right <= second.boundsInRoot.left)
        assertEquals("Shortcut actions must share a horizontal row", first.boundsInRoot.center.y, second.boundsInRoot.center.y, 1f)
        listOf("catalog_tool_favorite", "catalog_tool_groups").forEach(::assertPanelLabelFits)
        favorite.performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("b"), fixture.layout.value.favorites) }
        groups.performTouchInput { click() }
        compose.onNodeWithTag("catalog_group_picker").assertIsDisplayed()
        compose.onNodeWithTag("membership:g2").performScrollTo().performTouchInput { click() }
        compose.runOnIdle { assertTrue("a" in fixture.layout.value.groups.single { it.id == "g2" }.members) }
        compose.onNodeWithTag("catalog_panel_back").performTouchInput { click() }
        compose.onNodeWithTag("catalog_tool_manage").performScrollTo().assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("a"), fixture.managed) }
    }

    @Test fun editorActionsStayAboveTheKeyboardAndDraftSurvivesFinishingInput() {
        groupId.value = "g1"
        render()
        val name = compose.onNodeWithTag("catalog_group_name")
        name.performTextReplacement("键盘中的草稿")
        val sheetRoot = awaitGroupKeyboard()
        val usableBottom = compose.runOnUiThread {
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(sheetRoot.view))
            sheetRoot.view.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        }
        listOf("catalog_group_cancel", "catalog_group_save").forEach { tag ->
            val action = compose.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction().fetchSemanticsNode()
            assertTrue("$tag must fit above the visible keyboard: ${action.boundsInRoot} / $usableBottom",
                action.positionInRoot.y + action.size.height <= usableBottom + 1f)
            assertTrue("$tag must retain its full touch height", action.size.height + 1f >= 48f * targetDensity)
            assertEquals("$tag must remain fully visible", action.size.height.toFloat(), action.boundsInRoot.height, 1f)
        }
        finishGroupNameInput()
        groupEditorRow("name", "catalog_group_name").assertTextEquals("键盘中的草稿").assertIsNotFocused()
        groupMember("b").performTouchInput { click() }
        compose.onNodeWithTag("catalog_group_save").assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithTag("catalog_group_editor").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("键盘中的草稿", fixture.layout.value.groups.first().name)
            assertEquals(listOf("a", "b"), fixture.layout.value.groups.first().members)
        }
    }

    @Test fun draggingTheCompactTitleDismissesWithoutRunningAToolAction() {
        selectedToolId.value = "a"
        val original = fixture.layout.value
        render()
        val header = compose.onNodeWithTag("catalog_panel_header").assertIsDisplayed()
        val bounds = header.fetchSemanticsNode().boundsInRoot
        val title = compose.onNode(hasText("Alpha") and hasAnyAncestor(hasTestTag("catalog_panel_header")),
            useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val close = compose.onNodeWithTag("catalog_panel_close").fetchSemanticsNode().boundsInRoot
        assertFalse("The drag must start on the title, outside the close button", close.contains(title.center))
        val start = title.center - bounds.topLeft
        header.performTouchInput {
            swipe(start, start + Offset(0f, bounds.height * 2f), durationMillis = 500)
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("catalog_tool_options").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle {
            assertNull(selectedToolId.value)
            assertEquals(original, fixture.layout.value)
            assertTrue(fixture.pending.isEmpty())
            assertTrue(fixture.opened.isEmpty())
            assertTrue(fixture.managed.isEmpty())
            assertTrue(fixture.uninstallRequested.isEmpty())
        }
    }

    @Test fun swipingUpOnAnOversizedTitleScrollsToTheShortcutActions() {
        fixture.tools.value = fixture.tools.value.map { if (it.toolId == "a") it.copy(name = "测") else it }
        selectedToolId.value = "a"
        render()
        val title = panelTitle()
        val shortTitle = title.fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(layouts)) }
        val layout = layouts.single()
        val glyphWidth = layout.getLineRight(0) - layout.getLineLeft(0)
        val lineHeight = layout.getLineBottom(0) - layout.getLineTop(0)
        val sheetRoot = requireNotNull(shortTitle.root as? ViewRootForTest)
        val windowHeight = compose.runOnUiThread { sheetRoot.view.height }
        assertTrue(glyphWidth > 0f && lineHeight > 0f)
        val charactersPerLine = (shortTitle.size.width / glyphWidth).toInt().coerceAtLeast(1)
        val lineCount = ceil(windowHeight / lineHeight).toInt() + 3
        val oversizedName = "测".repeat(charactersPerLine * lineCount)
        compose.runOnIdle {
            fixture.tools.value = fixture.tools.value.map { if (it.toolId == "a") it.copy(name = oversizedName) else it }
        }

        val list = compose.onNodeWithTag("catalog_tool_options").assertIsDisplayed()
        val viewport = list.fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("catalog_panel_header").fetchSemanticsNode()
        val originalHeaderTop = header.positionInRoot.y
        assertTrue("The fixture title must exceed the actual sheet viewport", header.size.height > viewport.height)
        compose.onNodeWithTag("catalog_tool_favorite").assertIsNotDisplayed()
        compose.onNodeWithTag("catalog_tool_groups").assertIsNotDisplayed()
        val titleArea = panelTitle().fetchSemanticsNode().boundsInRoot.intersect(viewport)
        assertTrue("A visible title area must accept the real swipe", titleArea.height > 0f && titleArea.width > 0f)
        val start = Offset(titleArea.center.x, titleArea.top + titleArea.height * 0.8f)
        val end = Offset(titleArea.center.x, titleArea.top + titleArea.height * 0.2f)
        val close = compose.onNodeWithTag("catalog_panel_close").fetchSemanticsNode().boundsInRoot
        assertFalse("The swipe must start on title text, not its close control", close.contains(start))
        list.performTouchInput { swipe(start - viewport.topLeft, end - viewport.topLeft, durationMillis = 800) }

        val movedHeader = compose.onNodeWithTag("catalog_panel_header").fetchSemanticsNode()
        assertTrue("A real upward title swipe must move the list instead of being swallowed by sheet drag",
            movedHeader.positionInRoot.y < originalHeaderTop - 1f)
        compose.runOnIdle { assertEquals("a", selectedToolId.value) }
        // Only after proving physical scrolling, finish locating the action semantically.
        list.performScrollToKey("shortcuts")
        compose.onNodeWithTag("catalog_tool_favorite").assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("b"), fixture.layout.value.favorites); assertTrue(fixture.opened.isEmpty()) }
    }

    @Test fun cancellingATitleDragRestoresTheSheetWithoutRunningAnAction() {
        selectedToolId.value = "a"
        val original = fixture.layout.value
        render()
        val header = compose.onNodeWithTag("catalog_panel_header").assertIsDisplayed()
        val bounds = header.fetchSemanticsNode().boundsInRoot
        val start = panelTitle().fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        var pointerDown = false
        try {
            header.performTouchInput {
                down(start)
                pointerDown = true
                moveTo(start + Offset(0f, bounds.height * 2f), delayMillis = 250)
            }
            assertTrue("The cancellation fixture must first move the sheet",
                header.fetchSemanticsNode().positionInRoot.y > bounds.top + 1f)
        } finally {
            if (pointerDown) header.performTouchInput { cancel() }
        }
        compose.waitForIdle()
        val restored = header.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("Cancellation must settle back to the original sheet position", bounds.top, restored.top, 1f)
        compose.runOnIdle {
            assertEquals("a", selectedToolId.value)
            assertEquals(original, fixture.layout.value)
            assertTrue(fixture.pending.isEmpty())
            assertTrue(fixture.opened.isEmpty())
            assertTrue(fixture.managed.isEmpty())
            assertTrue(fixture.uninstallRequested.isEmpty())
        }
        compose.onNodeWithTag("catalog_tool_favorite").performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("b"), fixture.layout.value.favorites) }
    }

    private fun panelTitle(): SemanticsNodeInteraction = compose.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and hasAnyAncestor(hasTestTag("catalog_panel_header")),
        useUnmergedTree = true,
    )

    private fun assertPanelLabelFits(tag: String) {
        val results = mutableListOf<TextLayoutResult>()
        val label = compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and hasAnyAncestor(hasTestTag(tag)),
            useUnmergedTree = true,
        )
        label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue(action(results))
        }
        assertFalse("$tag text must fit at the current font scale", results.single().hasVisualOverflow)
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertEquals("$tag label must share the compact horizontal action's center line", bounds.center.y,
            label.fetchSemanticsNode().boundsInRoot.center.y, 1f)
    }

    private fun awaitGroupKeyboard(): ViewRootForTest {
        val name = compose.onNodeWithTag("catalog_group_name")
        val sheetRoot = requireNotNull(name.fetchSemanticsNode().root as? ViewRootForTest)
        // Android delivers IME insets after Compose is idle. Finish real input in the sheet's
        // window before scrolling, so the focused field cannot bring itself back into view.
        compose.waitUntil(5_000) {
            compose.runOnUiThread {
                ViewCompat.getRootWindowInsets(sheetRoot.view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
        }
        compose.waitUntil(5_000) { compose.runOnUiThread { !sheetRoot.hasPendingMeasureOrLayout } }
        return sheetRoot
    }

    private fun finishGroupNameInput() {
        val name = compose.onNodeWithTag("catalog_group_name")
        val sheetRoot = awaitGroupKeyboard()
        name.performImeAction()
        compose.waitUntil(5_000) {
            compose.runOnUiThread {
                val insets = ViewCompat.getRootWindowInsets(sheetRoot.view)
                insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime()) &&
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0 && !sheetRoot.hasPendingMeasureOrLayout
            }
        }
        name.assertIsNotFocused()
    }

    private fun groupMember(toolId: String): SemanticsNodeInteraction =
        groupEditorRow("tool:$toolId", "group-tool:$toolId")

    private fun groupEditorRow(key: String, tag: String): SemanticsNodeInteraction {
        // The IME can reduce the sheet viewport until a member row is not composed yet.
        compose.onNodeWithTag("catalog_group_members").performScrollToKey(key)
        return compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0} wideLargeText={1}") fun variants() = listOf(
            arrayOf<Any>(ToolBoxThemeStyle.Miuix, false), arrayOf<Any>(ToolBoxThemeStyle.LiquidGlass, false),
            arrayOf<Any>(ToolBoxThemeStyle.Miuix, true), arrayOf<Any>(ToolBoxThemeStyle.LiquidGlass, true),
        )
    }
}
