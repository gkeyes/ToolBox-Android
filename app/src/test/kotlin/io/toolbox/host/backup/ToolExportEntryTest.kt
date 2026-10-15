package io.toolbox.host.backup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.host.catalog.CatalogAction
import io.toolbox.host.catalog.CatalogTool
import io.toolbox.host.catalog.CatalogUiState
import io.toolbox.host.ui.ToolDetailScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The tool page's 导出工具 row opens a .tbx save dialog and exports to the chosen document. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ToolExportEntryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun exportRowSavesTheToolAsTbx() {
        val actions = mutableListOf<CatalogAction>()
        val state = CatalogUiState(isLoaded = true, tools = listOf(CatalogTool(ID, "有余", 4, "1.2.0", 1_000, null)))
        compose.setContent {
            ToolBoxTheme(reduceTransparency = true) { ToolDetailScreen(ID, state, { actions += it }, {}, {}, {}) }
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("tool_detail_export"))
        compose.onNodeWithTag("tool_detail_export").performClick()
        compose.waitForIdle()

        val request = checkNotNull(shadowOf(compose.activity).nextStartedActivityForResult)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
        assertEquals("有余-1.2.0.tbx", request.intent.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue(actions.isEmpty())

        val saved = Uri.parse("content://documents/document/42")
        shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(saved))
        compose.waitForIdle()
        assertEquals(listOf<CatalogAction>(CatalogAction.ExportTool(ID, saved.toString())), actions)
    }

    @Test
    fun fileNameDropsPathCharacters() {
        assertEquals("a_b_c-1.0.tbx", exportFileName("a/b:c", "1.0"))
    }

    private companion object {
        const val ID = "io.toolbox.youyu"
    }
}
