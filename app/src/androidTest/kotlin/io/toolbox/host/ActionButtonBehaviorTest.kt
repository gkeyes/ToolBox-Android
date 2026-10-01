package io.toolbox.host

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.core.ui.component.*
import io.toolbox.core.ui.theme.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(Parameterized::class)
class ActionButtonBehaviorTest(
    private val style: ToolBoxThemeStyle,
    private val mode: ToolBoxThemeMode,
) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun actionsKeepTheirOwnCallbacksAndCannotRepeatWhileDisabled() {
        val enabled = mutableStateOf(true)
        val stopping = mutableStateOf(false)
        val clicks = mutableListOf<String>()
        compose.activity.setContent {
            ToolBoxTheme(style = style, mode = mode) {
                Column(
                    Modifier.fillMaxWidth().background(ToolBoxThemeTokens.colors.background)
                        .padding(24.dp).testTag("button-preview"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ToolBoxText("ToolBox · ${style.name} · ${mode.name}")
                    ToolBoxPrimaryButton("打开工具", { clicks += "open" }, Modifier.fillMaxWidth().testTag("open"), enabled.value)
                    ToolBoxSecondaryButton("取消", { clicks += "cancel" }, Modifier.fillMaxWidth().testTag("cancel"), enabled.value)
                    ToolBoxDestructiveButton("删除工具", { clicks += "delete" }, Modifier.fillMaxWidth().testTag("delete"), enabled.value)
                    ToolBoxPrimaryButton("确认删除", { clicks += "confirm" }, Modifier.fillMaxWidth().testTag("confirm"), enabled.value, destructive = true)
                    ToolBoxTextButton("保存修改", { clicks += "save" }, Modifier.fillMaxWidth().testTag("save"), enabled.value)
                    ToolBoxRunningStatusButton(stopping.value, { clicks += "stop"; stopping.value = true }, Modifier.testTag("stop"), enabled.value && !stopping.value)
                }
            }
        }
        val tags = listOf("open", "cancel", "delete", "confirm", "save", "stop")
        tags.forEach { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(if (tag == "stop") 40.dp else 48.dp)
        }
        savePreview("enabled")
        tags.dropLast(1).forEach { compose.onNodeWithTag(it).performClick() }
        // Tap the expanded 48 dp touch target above the compact 40 dp surface.
        val stopNode = compose.onNodeWithTag("stop").assertTouchHeightIsEqualTo(48.dp).fetchSemanticsNode()
        val touchBounds = stopNode.touchBoundsInRoot
        val inset = with(stopNode.layoutInfo.density) { 1.dp.toPx() }
        compose.onRoot().performTouchInput { click(Offset(touchBounds.center.x, touchBounds.top + inset)) }
        compose.onNodeWithTag("stop").assertIsNotEnabled().assertTextEquals("停止中")
        compose.runOnIdle {
            assertEquals(tags, clicks)
            enabled.value = false
        }
        tags.forEach { tag ->
            compose.onNodeWithTag(tag).assertIsNotEnabled().performTouchInput { click() }
        }
        compose.runOnIdle { assertEquals(tags, clicks) }
        savePreview("disabled")
    }

    private fun savePreview(state: String) {
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        val directory = File(output).apply { mkdirs() }
        File(directory, "button-preview-${style.name}-${mode.name}-$state.png").outputStream().use { stream ->
            check(compose.onNodeWithTag("button-preview").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}-{1}")
        fun themes() = ToolBoxThemeStyle.entries.flatMap { style ->
            listOf(ToolBoxThemeMode.Light, ToolBoxThemeMode.Dark).map { mode -> arrayOf(style, mode) }
        }
    }
}
