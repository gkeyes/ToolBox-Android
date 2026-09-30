package io.toolbox.host

import android.os.ParcelFileDescriptor
import android.os.Process
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.core.data.SecurityProfile
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.core.ui.theme.ToolBoxThemeMode
import io.toolbox.core.ui.theme.ToolBoxThemeStyle
import io.toolbox.host.help.DeveloperHelpPage
import io.toolbox.host.help.DeveloperHelpScreen
import io.toolbox.host.help.DeveloperHelpTestTags
import io.toolbox.host.help.HelpLoadState
import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxCapabilityId
import io.toolbox.tool.packagekit.InstalledManifest
import io.toolbox.tool.runtime.HardenedRuntimeWebView
import io.toolbox.tool.runtime.PreparedToolRuntime
import io.toolbox.tool.runtime.RuntimeAuthorizationPolicy
import io.toolbox.tool.runtime.RuntimeBridgeConfiguration
import io.toolbox.tool.runtime.RuntimeBridgeProvider
import io.toolbox.tool.runtime.RuntimeCreationPermitResult
import io.toolbox.tool.runtime.RuntimeM1Handlers
import io.toolbox.tool.runtime.RuntimePolicyDecision
import io.toolbox.tool.runtime.RuntimeProfileManager
import io.toolbox.tool.runtime.RuntimeSessionIdentity
import io.toolbox.tool.runtime.RuntimeIdentity
import io.toolbox.tool.runtime.RuntimeWebViewCallbacks
import io.toolbox.tool.runtime.RuntimeWebViewCreationResult
import io.toolbox.tool.runtime.RuntimeWebViewDebugging
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class DeveloperWebViewDebuggingBehaviorTest(
    private val style: ToolBoxThemeStyle,
    private val mode: ToolBoxThemeMode,
) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun compiledModeIsFixedForExistingAndNewRuntimeDebugging() {
        val status = RuntimeWebViewDebugging.status(compose.activity)
        assertEquals(BuildConfig.DEBUG, status.enabledByBuild)
        val root = File(compose.activity.filesDir, "debug-toggle-${UUID.randomUUID()}")
        val views = mutableListOf<WebView>()
        try {
            views += createRuntime(root, "existing")
            compose.activity.setContent {
                ToolBoxTheme(mode = mode, style = style) {
                    DeveloperHelpScreen(onBack = {}, onInstallExamples = {})
                }
            }
            val toggle = compose.onNodeWithTag(DeveloperHelpTestTags.WebViewDebugging)
            toggle.assertIsDisplayed().assertIsNotEnabled()
            if (status.enabled) toggle.assertIsOn() else toggle.assertIsOff()
            awaitDebugEndpoint(status.enabled)
            toggle.performTouchInput { click() }
            if (status.enabled) toggle.assertIsOn() else toggle.assertIsOff()
            views += createRuntime(root, "new")
            assertEquals(status, RuntimeWebViewDebugging.status(compose.activity))
            awaitDebugEndpoint(status.enabled)

            compose.onNodeWithTag(DeveloperHelpTestTags.List).performScrollToKey("intro")
            compose.onNodeWithTag(DeveloperHelpTestTags.Search).assertIsDisplayed()
            toggle.assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
            if (status.enabled) toggle.assertIsOn() else toggle.assertIsOff()
            compose.onNodeWithText(
                when {
                    status.forcedByPlatform -> "当前系统强制开启接口；应用内不可切换。"
                    status.enabledByBuild -> "调试版：编译时开启，应用内无法关闭。"
                    else -> "正式版：编译时关闭，应用内无法开启。"
                },
            ).assertIsDisplayed()
            awaitDebugEndpoint(status.enabled)
        } finally {
            compose.runOnIdle {
                views.forEach(HardenedRuntimeWebView::release)
            }
            root.deleteRecursively()
        }
    }

    @Test fun manualLoadingAndFailureKeepTheCompiledStateVisibleAndLocked() {
        val load = mutableStateOf<HelpLoadState>(HelpLoadState.Loading)
        var retries = 0
        compose.activity.setContent {
            ToolBoxTheme(mode = mode, style = style) {
                DeveloperHelpPage(
                    state = load.value, onBack = {}, onInstallExamples = {},
                    onRetry = { retries++ },
                    webViewDebuggingEnabled = BuildConfig.DEBUG,
                )
            }
        }
        val toggle = compose.onNodeWithTag(DeveloperHelpTestTags.WebViewDebugging)
        compose.onNodeWithText("正在读取离线手册…").assertIsDisplayed()
        toggle.assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
        if (BuildConfig.DEBUG) toggle.assertIsOn() else toggle.assertIsOff()
        compose.runOnIdle { load.value = HelpLoadState.Failed }
        toggle.assertIsNotEnabled().performTouchInput { click() }
        if (BuildConfig.DEBUG) toggle.assertIsOn() else toggle.assertIsOff()
        compose.onNodeWithText("重新读取").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun forcedPlatformDebuggingShowsItsActualStateAndCannotBeToggled() {
        compose.activity.setContent {
            ToolBoxTheme(mode = mode, style = style) {
                DeveloperHelpPage(
                    state = HelpLoadState.Loading, onBack = {}, onInstallExamples = {}, onRetry = {},
                    webViewDebuggingEnabled = true, webViewDebuggingForced = true,
                )
            }
        }
        compose.onNodeWithTag(DeveloperHelpTestTags.WebViewDebugging)
            .assertIsDisplayed().assertIsOn().assertIsNotEnabled().performTouchInput { click() }
            .assertIsOn()
        compose.onNodeWithText("当前系统强制开启接口；应用内不可切换。")
            .assertIsDisplayed()
    }

    private fun createRuntime(root: File, suffix: String): WebView {
        val id = "io.example.debug.${suffix}.${UUID.randomUUID().toString().replace("-", "")}"
        val bundle = root.toPath().resolve(RuntimeIdentity.expectedBundleLocator(id, 1))
        Files.createDirectories(bundle)
        Files.write(bundle.resolve("index.html"), "<html><body>Debugging test</body></html>".toByteArray())
        val runtime = PreparedToolRuntime(
            id, "调试测试", 1, root.toPath(), bundle, "index.html", RuntimeIdentity.origin(id),
            RuntimeIdentity.profileName(id), SecurityProfile.STRICT,
            InstalledManifest(id, "Test", 1, "0.3.0", "index.html", SecurityProfile.STRICT, emptySet(), emptyList(), null),
        )
        val permit = runBlocking {
            val acquired = RuntimeProfileManager(root).acquireRuntimePermit(id, false)
            assertTrue("Runtime permit unavailable: $acquired", acquired is RuntimeCreationPermitResult.Ready)
            (acquired as RuntimeCreationPermitResult.Ready).permit
        }
        var result: RuntimeWebViewCreationResult? = null
        compose.runOnIdle {
            result = HardenedRuntimeWebView.create(
                compose.activity, runtime, permit,
                RuntimeWebViewCallbacks({}, {}, {}), bridgeProvider,
                darkTheme = mode == ToolBoxThemeMode.Dark,
            )
        }
        assertTrue("Runtime creation failed: $result", result is RuntimeWebViewCreationResult.Created)
        return (result as RuntimeWebViewCreationResult.Created).webView
    }

    private fun awaitDebugEndpoint(expected: Boolean) {
        val endpoint = "@webview_devtools_remote_${Process.myPid()}"
        compose.waitUntil(timeoutMillis = 10_000) {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("cat /proc/net/unix")
            val present = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().useLines { lines ->
                lines.any { it.trim().split(Regex("\\s+")).lastOrNull() == endpoint }
            }
            present == expected
        }
    }

    private val bridgeProvider = RuntimeBridgeProvider {
        RuntimeBridgeConfiguration(
            authorization = object : RuntimeAuthorizationPolicy {
                override suspend fun isCurrent(identity: RuntimeSessionIdentity) = true
                override suspend fun isGranted(identity: RuntimeSessionIdentity, capability: ToolBoxCapabilityId) = false
                override suspend fun hasSystemPermissions(identity: RuntimeSessionIdentity, permissions: Set<String>) = false
                override suspend fun admit(identity: RuntimeSessionIdentity, method: MethodDescriptor, encodedBytes: Int) =
                    RuntimePolicyDecision.Allowed
            },
            handlers = RuntimeM1Handlers(),
            hostVersion = BuildConfig.VERSION_NAME,
        )
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}/{1}")
        fun themes() = ToolBoxThemeStyle.entries.flatMap { style ->
            listOf(ToolBoxThemeMode.Light, ToolBoxThemeMode.Dark).map { mode -> arrayOf(style, mode) }
        }
    }
}
