package io.toolbox.host

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.component.*
import io.toolbox.core.ui.theme.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class DesignPolishBehaviorTest(
    private val style: ToolBoxThemeStyle,
    private val mode: ToolBoxThemeMode,
    private val reduceTransparency: Boolean,
) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val viewportWidth = mutableFloatStateOf(360f)
    private val fontScale = mutableFloatStateOf(1f)
    private val direction = mutableStateOf(LayoutDirection.Ltr)

    private fun render(content: @Composable () -> Unit) {
        compose.activity.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                CompositionLocalProvider(
                    LocalDensity provides Density(constraints.maxWidth / viewportWidth.floatValue, fontScale.floatValue),
                    LocalLayoutDirection provides direction.value,
                ) {
                    ToolBoxTheme(style = style, mode = mode, reduceTransparency = reduceTransparency) {
                        Column(Modifier.fillMaxSize(), content = { content() })
                    }
                }
            }
        }
    }

    @Test
    fun titleAvoidsAllControlsAtNarrowWidthsAndLargeFontsInBothDirections() {
        val title = "工具详情与运行设置"
        var clicks = 0
        render {
            ToolBoxTopBar(
                title = title,
                subtitle = "已安装工具",
                modifier = Modifier.testTag("topbar"),
                navigationIcon = ToolBoxIconKey.Back,
                onNavigationClick = { clicks++ },
                actions = {
                    repeat(3) { index ->
                        ToolBoxIconButton(ToolBoxIconKey.More, "操作$index", { clicks++ })
                    }
                },
            )
        }
        val controls = listOf("返回", "操作0", "操作1", "操作2")
        for (layoutDirection in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            for ((width, scale) in listOf(320f to 1f, 320f to 1.6f, 360f to 1f, 600f to 1f)) {
                compose.runOnIdle {
                    viewportWidth.floatValue = width
                    fontScale.floatValue = scale
                    direction.value = layoutDirection
                }
                val bar = bounds("topbar")
                val titleBounds = compose.onNodeWithText(title).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("The title must retain visible space", titleBounds.width > 0f)
                assertContained(titleBounds, bar)
                val controlBounds = controls.map { description ->
                    compose.onNodeWithContentDescription(description)
                        .assertHasClickAction().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                        .fetchSemanticsNode().boundsInRoot.also { assertContained(it, bar) }
                }
                controlBounds.forEach { control ->
                    assertTrue("Title overlaps a control", titleBounds.right <= control.left + 1f || titleBounds.left >= control.right - 1f)
                }
                for (i in controlBounds.indices) for (j in i + 1 until controlBounds.size) {
                    val a = controlBounds[i]
                    val b = controlBounds[j]
                    assertTrue("Controls overlap", a.right <= b.left + 1f || b.right <= a.left + 1f)
                }
                if (width == 600f) assertEquals("Wide titles stay screen-centered", bar.center.x, titleBounds.center.x, 1.5f)
            }
        }
        controls.forEach { compose.onNodeWithContentDescription(it).performClick() }
        compose.runOnIdle { assertEquals(4, clicks) }
    }

    @Test
    fun searchTextWidthDoesNotJumpWhenTheClearControlAppears() {
        val query = mutableStateOf("")
        render { ToolBoxSearchField(query.value, { query.value = it }, "搜索工具", Modifier.testTag("search")) }
        for (layoutDirection in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            compose.runOnIdle { direction.value = layoutDirection; query.value = "" }
            val empty = compose.onNodeWithContentDescription("搜索工具").fetchSemanticsNode().boundsInRoot
            val beforeHeight = bounds("search").height
            compose.onNodeWithContentDescription("清空搜索工具").assertDoesNotExist()
            compose.runOnIdle { query.value = "RSS" }
            val filled = compose.onNodeWithContentDescription("搜索工具").fetchSemanticsNode().boundsInRoot
            assertEquals(empty.left, filled.left, 1f)
            assertEquals(empty.right, filled.right, 1f)
            assertEquals(beforeHeight, bounds("search").height, 1f)
            compose.onNodeWithContentDescription("清空搜索工具")
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
            compose.onNodeWithContentDescription("搜索工具").assertIsFocused()
            compose.onNodeWithContentDescription("清空搜索工具").assertDoesNotExist()
        }
    }

    @Test
    fun blankSummaryDoesNotCreateAnEmptyLineOrClickableInformationRow() {
        val summary = mutableStateOf<String?>(null)
        render { ToolBoxSettingRow("存储信息", summary = summary.value, modifier = Modifier.testTag("information")) }
        val before = bounds("information").height
        compose.onNodeWithTag("information").assertHasNoClickAction()
        compose.runOnIdle { summary.value = "  \n  " }
        assertEquals(before, bounds("information").height, 1f)
        compose.onNodeWithTag("information").assertHasNoClickAction()
    }

    @Test
    fun disclosureCanBeRetargetedBeforeItsAnimationFinishes() {
        val expanded = mutableStateOf(false)
        var clicks = 0
        render {
            ToolBoxDisclosureRow(
                title = "高级设置",
                expanded = expanded.value,
                onClick = {
                    clicks++
                    expanded.value = !expanded.value
                },
                modifier = Modifier.testTag("disclosure"),
            )
        }
        // activity.setContent schedules composition; settle it before freezing the clock.
        compose.waitForIdle()
        val row = compose.onNodeWithTag("disclosure")
        row.assertIsDisplayed().assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已折叠"))

        val previousAutoAdvance = compose.mainClock.autoAdvance
        compose.mainClock.autoAdvance = false
        try {
            row.performClick()
            // A paused clock also pauses recomposition, not just the arrow animation.
            compose.mainClock.advanceTimeByFrame()
            row.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
            compose.mainClock.advanceTimeBy(32)

            row.performClick()
            compose.mainClock.advanceTimeByFrame()
            row.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已折叠"))
            compose.mainClock.advanceTimeBy(32)

            row.performClick()
            compose.mainClock.advanceTimeByFrame()
            row.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
            compose.mainClock.advanceTimeBy(200)
            row.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
        } finally {
            compose.mainClock.autoAdvance = previousAutoAdvance
        }
        compose.runOnIdle {
            assertEquals(3, clicks)
            assertTrue(expanded.value)
        }
    }

    @Test
    fun navigationRemainsSelectableWhenItemsReorderAndFontSizeChanges() {
        val items = mutableStateOf(listOf(
            ToolBoxNavigationItem("tools", "工具", ToolBoxIconKey.Tools, "tab-tools"),
            ToolBoxNavigationItem("recent", "最近", ToolBoxIconKey.Clock, "tab-recent"),
            ToolBoxNavigationItem("settings", "设置", ToolBoxIconKey.Settings, "tab-settings"),
        ))
        val selectedId = mutableStateOf("tools")
        render { ToolBoxNavigationBar(items.value, selectedId.value, { selectedId.value = it.id }) }
        compose.onNodeWithTag("tab-settings").performClick().assertIsSelected()
        compose.runOnIdle {
            items.value = items.value.reversed()
            fontScale.floatValue = 1.6f
            direction.value = LayoutDirection.Rtl
        }
        compose.onNodeWithTag("tab-settings").assertIsSelected()
        compose.onNodeWithTag("tab-tools").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            .performClick().assertIsSelected()
        compose.onNodeWithTag("tab-settings").assertIsNotSelected()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot

    private fun assertContained(inner: Rect, outer: Rect) {
        assertTrue("Content escaped the left edge", inner.left >= outer.left - 1f)
        assertTrue("Content escaped the right edge", inner.right <= outer.right + 1f)
        assertTrue("Content escaped the top edge", inner.top >= outer.top - 1f)
        assertTrue("Content escaped the bottom edge", inner.bottom <= outer.bottom + 1f)
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
