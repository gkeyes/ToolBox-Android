package io.toolbox.host

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.toolbox.core.ui.component.*
import io.toolbox.core.ui.theme.*
import io.toolbox.host.importflow.ImportOutcome
import io.toolbox.host.importflow.ImportUiState
import io.toolbox.host.ui.FeedbackSurface
import io.toolbox.host.ui.feedbackTone
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class PolishControlsBehaviorTest(
    private val style: ToolBoxThemeStyle,
    private val mode: ToolBoxThemeMode,
    private val reduceTransparency: Boolean,
) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val width = mutableFloatStateOf(360f)
    private val fontScale = mutableFloatStateOf(1f)

    private fun render(content: @Composable () -> Unit) {
        compose.activity.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalDensity provides Density(constraints.maxWidth / width.floatValue, fontScale.floatValue)) {
                    ToolBoxTheme(style = style, mode = mode, reduceTransparency = reduceTransparency) {
                        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
                    }
                }
            }
        }
    }

    @Test fun searchClearPreservesFocusAndHeightAndImeSearchRetainsResults() {
        val query = mutableStateOf("")
        render { ToolBoxSearchField(query.value, { query.value = it }, "搜索工具", Modifier.testTag("search")) }
        val field = compose.onNodeWithContentDescription("搜索工具")
        val before = compose.onNodeWithTag("search").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithContentDescription("清空搜索工具").assertDoesNotExist()
        field.performClick().performTextInput("RSS")
        compose.onNodeWithContentDescription("清空搜索工具").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        assertEquals(before, compose.onNodeWithTag("search").fetchSemanticsNode().boundsInRoot.height, 1f)
        field.performImeAction()
        compose.waitUntil(5_000) { compose.runOnIdle {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) != true
        } }
        field.assertTextEquals("RSS").assertIsFocused()
        compose.onNodeWithContentDescription("清空搜索工具").performClick()
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))).assertIsFocused()
        compose.onNodeWithContentDescription("清空搜索工具").assertDoesNotExist()
        for ((viewport, scale) in listOf(320f to 1.6f, 960f to 1f)) {
            compose.runOnIdle { width.floatValue = viewport; fontScale.floatValue = scale }
            field.performTextInput("分组")
            val bounds = compose.onNodeWithTag("search").fetchSemanticsNode().boundsInRoot
            val clear = compose.onNodeWithContentDescription("清空搜索工具").fetchSemanticsNode().boundsInRoot
            assertTrue(clear.left >= bounds.left && clear.right <= bounds.right + 1f)
            assertTrue(clear.top >= bounds.top && clear.bottom <= bounds.bottom + 1f)
            compose.onNodeWithContentDescription("清空搜索工具").performClick()
            field.assertIsFocused().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        }
    }

    @Test fun disabledControlsBlockClicksAndBecomeUsableAgain() {
        val enabled = mutableStateOf(false)
        var opens = 0
        render {
            ToolBoxSettingRow("后台任务", summary = "查看和取消任务", icon = ToolBoxIconKey.Clock,
                enabled = enabled.value, onClick = { opens++ }, modifier = Modifier.testTag("setting"))
            ToolBoxIconButton(ToolBoxIconKey.More, "更多操作", { opens++ }, enabled = enabled.value)
        }
        compose.onNodeWithTag("setting").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithContentDescription("更多操作").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, opens); enabled.value = true }
        compose.onNodeWithTag("setting").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("更多操作").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, opens) }
    }

    @Test fun terminalFeedbackAnnouncesPolitelyAndProgressDoesNotRepeatAnnouncements() {
        val state = mutableStateOf(ImportUiState(working = true))
        var dismissed = 0
        render { FeedbackSurface(state.value.message ?: state.value.progressMessage, state.value.feedbackTone,
            dismissible = !state.value.working, onDismiss = { dismissed++ }, modifier = Modifier.testTag("feedback")) }
        assertNull(compose.onNodeWithTag("feedback").fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
        for (outcome in ImportOutcome.entries) {
            compose.runOnIdle { state.value = ImportUiState(message = "操作结果", outcome = outcome) }
            compose.onNodeWithTag("feedback").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            compose.onNodeWithContentDescription("关闭提示").performClick()
        }
        compose.runOnIdle { assertEquals(3, dismissed) }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-{1}-reduce={2}")
        fun cases(): List<Array<Any>> = buildList {
            for (style in ToolBoxThemeStyle.entries) for (mode in listOf(ToolBoxThemeMode.Light, ToolBoxThemeMode.Dark)) {
                add(arrayOf(style, mode, false))
                if (style == ToolBoxThemeStyle.LiquidGlass) add(arrayOf(style, mode, true))
            }
        }
    }
}
