package io.toolbox.host

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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

@RunWith(Parameterized::class)
class CatalogPanelBehaviorTest(private val style: ToolBoxThemeStyle) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val fixture = CatalogBehaviorFixture()
    private val groupId = mutableStateOf<String?>(null)
    private val selectedToolId = mutableStateOf<String?>(null)
    private val managedToolId = mutableStateOf<String?>(null)
    private val pickingFavorites = mutableStateOf(false)

    private fun render(restoration: StateRestorationTester? = null) {
        val content: @Composable () -> Unit = {
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
        if (restoration == null) compose.activity.setContent(content = content)
        else restoration.setContent(content)
    }

    @Test fun saveWaitsForItsOwnResultAndFailedDraftSurvivesStateRestoration() {
        fixture.holdWrites = true
        groupId.value = "g1"
        // MainActivity already owns content; keep the production Activity while delegating only
        // installation of this test composition to the normal Activity.setContent entry point.
        val restoration = StateRestorationTester(object : ComposeContentTestRule by compose {
            override fun setContent(content: @Composable () -> Unit) { compose.activity.setContent(content = content) }
        })
        render(restoration)
        compose.onNodeWithTag("catalog_group_name").performTextReplacement("保留的草稿")
        compose.onNodeWithTag("group-tool:b").performScrollTo().performClick()
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
        compose.onNodeWithTag("catalog_group_name").performScrollTo().assertTextEquals("保留的草稿")
        compose.onNodeWithTag("group-tool:b").performScrollTo().assertIsOn()

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("catalog_group_name").performScrollTo().assertTextEquals("保留的草稿")
        compose.onNodeWithTag("group-tool:b").performScrollTo().assertIsOn()
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
        compose.onNodeWithTag("group-tool:b").performScrollTo().performClick()
        compose.onNodeWithTag("catalog_group_save").performClick()
        compose.onNodeWithTag("catalog_group_editor").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("b"), fixture.layout.value.groups.single { it.id == "new" }.members)
            groupId.value = "g2"
        }
        compose.onNodeWithTag("group-tool:b").performScrollTo().performClick()
        compose.onNodeWithTag("catalog_group_cancel").performClick()
        compose.runOnIdle {
            assertTrue(fixture.layout.value.groups.single { it.id == "g2" }.members.isEmpty())
            groupId.value = "g2"
        }
        compose.onNodeWithTag("catalog_group_delete").performScrollTo().performClick()
        compose.onNodeWithTag("catalog_group_delete_confirmation").assertExists()
        // Back leaves the confirmation page and keeps the editor, rather than stacking a dialog.
        pressCatalogBack()
        compose.onNodeWithTag("catalog_group_editor").assertExists()
        compose.onNodeWithTag("catalog_group_delete_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("catalog_group_delete").performScrollTo().performClick()
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

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun variants() =
            listOf(arrayOf(ToolBoxThemeStyle.Miuix), arrayOf(ToolBoxThemeStyle.LiquidGlass))
    }
}
