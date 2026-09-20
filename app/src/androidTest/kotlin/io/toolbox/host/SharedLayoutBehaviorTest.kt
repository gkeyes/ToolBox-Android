package io.toolbox.host

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.component.ToolBoxText
import io.toolbox.core.ui.component.ToolBoxValueRow
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.core.ui.theme.ToolBoxThemeStyle
import io.toolbox.host.ui.DetailScreen
import io.toolbox.host.ui.HostBootstrapScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SharedLayoutBehaviorTest(private val style: ToolBoxThemeStyle) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val viewportWidth = mutableStateOf(960f)
    private val fontScale = mutableStateOf(1f)
    private var pixelsPerDp = 1f

    /** Change the available dp width without depending on the emulator's physical screen size. */
    private fun render(height: Dp? = null, content: @Composable () -> Unit) {
        compose.activity.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = Density(constraints.maxWidth / viewportWidth.value, fontScale.value)
                SideEffect { pixelsPerDp = density.density }
                CompositionLocalProvider(LocalDensity provides density) {
                    ToolBoxTheme(style = style, reduceTransparency = true) {
                        Box(
                            Modifier.fillMaxWidth()
                                .then(if (height == null) Modifier.fillMaxHeight() else Modifier.height(height))
                                .testTag("shared_layout_viewport"),
                        ) {
                            content()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun detailContentIsCenteredAndBoundedOnWideAndNarrowScreens() {
        render {
            DetailScreen(title = "详情", onBack = {}) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) { ToolBoxText("详情内容") }
            }
        }

        val wideViewport = bounds("shared_layout_viewport")
        val wideContent = bounds("host_detail_content")
        assertHorizontallyContained(wideContent, wideViewport)
        assertTrue("Wide detail content must leave side margins", wideContent.width < wideViewport.width)
        assertTrue("Detail content exceeded its reading width", wideContent.width <= 720f * pixelsPerDp + 1f)
        assertEquals(wideViewport.center.x, wideContent.center.x, 1.5f)

        compose.runOnIdle { viewportWidth.value = 320f }

        val narrowViewport = bounds("shared_layout_viewport")
        val narrowContent = bounds("host_detail_content")
        assertHorizontallyContained(narrowContent, narrowViewport)
        assertEquals(narrowViewport.center.x, narrowContent.center.x, 1.5f)
        compose.onNodeWithText("详情内容").assertIsDisplayed()
    }

    @Test
    fun shortBootstrapStatusWrapsItsContentWithinTheReadingWidth() {
        render { HostBootstrapScreen(loading = true, message = "正在读取外观设置。", onRetry = {}) }

        val viewport = bounds("shared_layout_viewport")
        val card = bounds("host_status_card")
        assertHorizontallyContained(card, viewport)
        assertTrue("Short status exceeded its reading width", card.width <= 480f * pixelsPerDp + 1f)
        assertEquals(viewport.center.x, card.center.x, 1.5f)
        assertTrue("Short status should not occupy the whole viewport", card.height < viewport.height / 2f)
        val scroll = compose.onNodeWithTag("host_status_scroll").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("Short status must not require scrolling", 0f, scroll.maxValue(), 0.5f)
        compose.onNodeWithText("重试").assertDoesNotExist()
    }

    @Test
    fun longErrorAtLargeFontInAShortWindowCanScrollToAndInvokeRetry() {
        viewportWidth.value = 320f
        fontScale.value = 1.8f
        var retries = 0
        val message = List(16) { "无法读取工具目录，请确认设备存储可用后重试。" }.joinToString("\n")
        render(height = 260.dp) {
            HostBootstrapScreen(loading = false, message = message, onRetry = { retries++ })
        }

        val scroll = compose.onNodeWithTag("host_status_scroll").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("The long error must be scrollable", scroll.maxValue() > 0f)
        compose.onNodeWithText("重试").assertIsNotDisplayed()
        compose.onNodeWithText("重试").performScrollTo().assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, retries) }
        val afterScroll = compose.onNodeWithTag("host_status_scroll").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("Retry must be reached by scrolling", afterScroll.value() > 0f)
    }

    @Test
    fun shortValueLabelsLeaveRoomForValuesAndAdaptToWidthAndFontSize() {
        viewportWidth.value = 420f
        val title = "目录"
        val value = "private-storage/catalog/installed-tools/a-long-readable-version-identifier"
        render { ToolBoxValueRow(title, value, Modifier.testTag("shared_value_row")) }

        val row = bounds("shared_value_row")
        val label = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val wideValue = compose.onNodeWithText(value).fetchSemanticsNode().boundsInRoot
        assertTrue("The short label should not reserve half the row", wideValue.width > row.width / 2f)
        assertTrue("The value should remain alongside its label when there is room", wideValue.left >= label.right)
        assertHorizontallyContained(wideValue, row)
        assertFullTextLaidOut(value)

        compose.runOnIdle { viewportWidth.value = 240f }
        assertStackedValueIsReadable(title, value)

        compose.runOnIdle {
            viewportWidth.value = 420f
            fontScale.value = 1.8f
        }
        assertStackedValueIsReadable(title, value)
    }

    private fun assertStackedValueIsReadable(title: String, value: String) {
        val row = bounds("shared_value_row")
        val label = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val valueBounds = compose.onNodeWithText(value).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("The value must move below its label", valueBounds.top >= label.bottom - 1f)
        assertEquals(label.left, valueBounds.left, 1.5f)
        assertHorizontallyContained(valueBounds, row)
        assertFullTextLaidOut(value)
    }

    private fun assertFullTextLaidOut(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue(it(layouts))
        }
        val layout = layouts.single()
        assertFalse("The complete value must fit without clipping", layout.hasVisualOverflow)
        assertFalse("The value must not be ellipsized", layout.isLineEllipsized(layout.lineCount - 1))
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).assertIsDisplayed()
        .fetchSemanticsNode().boundsInRoot

    private fun assertHorizontallyContained(inner: Rect, outer: Rect) {
        assertTrue("Content escaped the left edge", inner.left >= outer.left - 1f)
        assertTrue("Content escaped the right edge", inner.right <= outer.right + 1f)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = ToolBoxThemeStyle.entries.map { arrayOf<Any>(it) }
    }
}
