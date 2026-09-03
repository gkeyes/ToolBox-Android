package io.toolbox.host

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.component.ToolBoxDestructiveButton
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.core.ui.theme.ToolBoxThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DestructiveButtonTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun filledActionsKeepTheirTouchTargetAndDisabledBehaviorInBothThemesAtDoubleFontScale() {
        var clicks = 0
        val enabled = mutableStateOf(true)
        composeRule.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Column {
                    ToolBoxTheme(mode = ToolBoxThemeMode.Light) {
                        ToolBoxDestructiveButton("删除工具", { clicks += 1 }, Modifier.fillMaxWidth(), enabled.value)
                    }
                    ToolBoxTheme(mode = ToolBoxThemeMode.Dark) {
                        ToolBoxDestructiveButton("停止此环境", { clicks += 1 }, Modifier.fillMaxWidth(), enabled.value)
                    }
                }
            }
        }
        listOf("删除工具", "停止此环境").forEach { label ->
            composeRule.onNodeWithText(label).assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        }
        composeRule.runOnIdle {
            assertEquals(2, clicks)
            enabled.value = false
        }
        listOf("删除工具", "停止此环境").forEach { label ->
            composeRule.onNodeWithText(label).assertIsNotEnabled()
        }
    }
}
