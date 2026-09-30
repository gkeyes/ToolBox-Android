package io.toolbox.tool.runtime

import android.webkit.WebSettings
import android.webkit.WebView
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import io.toolbox.core.data.SecurityProfile
import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxCapabilityId
import io.toolbox.tool.packagekit.InstalledManifest
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real document-start bridge, WebView profile, and host lifecycle entry points. */
@RunWith(AndroidJUnit4::class)
class RuntimeBridgeLifecycleInstrumentationTest {
    @Test
    fun readyOnceEarlyEventsStateRevisionsCloseAndNewGeneration() = withFixture { fixture ->
        val first = fixture.attach()
        assertTrue(HardenedRuntimeWebView.emitEvent(first, "background.timer", timerPayload(1)))
        assertTrue(HardenedRuntimeWebView.emitEvent(first, "background.timer", timerPayload(2)))
        await { evaluate(first, "window.bridgeReady") == "true" && evaluate(first, "window.bridgeStates.length") == "1" }
        assertEquals(1, fixture.readyAdmissions.get())
        val firstGeneration = evaluate(first, "window.bridgeStates[0].generation")

        main {
            HardenedRuntimeWebView.setRuntimeForeground(first, true)
            HardenedRuntimeWebView.setRuntimeForeground(first, true)
        }
        await { evaluate(first, "window.bridgeStates.length") == "2" }
        assertEquals("true", evaluate(first, "window.bridgeStates[1].foreground"))
        assertEquals("1", evaluate(first, "window.bridgeStates[1].revision"))
        main { first.evaluateJavascript("window.installTimerListener()", null) }
        await { evaluate(first, "window.timerEvents.join(',')") == "\"1,2\"" }
        main { first.evaluateJavascript("ToolBox.runtime.getState().then(state => window.fetchedState = state)", null) }
        await { evaluate(first, "window.fetchedState && window.fetchedState.revision") == "1" }
        assertEquals(1, fixture.readyAdmissions.get())

        val firstDocument = evaluate(first, "window.documentId")
        main { first.evaluateJavascript("window.unsubscribeTimer()", null) }
        assertTrue(HardenedRuntimeWebView.emitEvent(first, "background.timer", timerPayload(99)))
        main { HardenedRuntimeWebView.loadEntry(first, fixture.runtime) }
        await {
            evaluate(first, "window.documentId") != firstDocument &&
                evaluate(first, "window.bridgeReady") == "true" &&
                evaluate(first, "window.bridgeStates.length") == "1"
        }
        assertEquals(firstGeneration, evaluate(first, "window.bridgeStates[0].generation"))
        assertEquals("1", evaluate(first, "window.bridgeStates[0].revision"))
        assertEquals("true", evaluate(first, "window.bridgeStates[0].foreground"))
        assertEquals(2, fixture.readyAdmissions.get())
        main { first.evaluateJavascript("window.installTimerListener()", null) }
        assertEquals("\"\"", evaluate(first, "window.timerEvents.join(',')"))
        assertTrue(HardenedRuntimeWebView.emitEvent(first, "background.timer", timerPayload(3)))
        await { evaluate(first, "window.timerEvents.join(',')") == "\"3\"" }

        val saved = CompletableFuture.supplyAsync {
            runBlocking { HardenedRuntimeWebView.flushBeforeClose(first, 2_000L) }
        }.get(5, TimeUnit.SECONDS)
        assertTrue("The real WebView did not acknowledge close", saved)
        assertTrue(HardenedRuntimeWebView.isClosing(first))
        await { evaluate(first, "window.bridgeStates[window.bridgeStates.length - 1].closing") == "true" }
        main { HardenedRuntimeWebView.cancelClose(first) }
        await { evaluate(first, "window.bridgeStates[window.bridgeStates.length - 1].closing") == "false" }
        assertFalse(HardenedRuntimeWebView.isClosing(first))

        fixture.dispose()
        val second = fixture.attach()
        await { evaluate(second, "window.bridgeReady") == "true" && evaluate(second, "window.bridgeStates.length") == "1" }
        assertNotEquals(firstGeneration, evaluate(second, "window.bridgeStates[0].generation"))
        assertEquals(3, fixture.readyAdmissions.get())
    }

    @Test
    fun dedicatedProfileServiceWorkerHardeningIsRestoredAfterDeleteAndRecreate() = withFixture { fixture ->
        val capabilities = runBlocking { fixture.manager.providerCapabilities() }
        assumeTrue("Provider cannot create and delete dedicated profiles: $capabilities",
            capabilities.preferredIsolationMode == RuntimeIsolationMode.DEDICATED_PROFILE &&
                capabilities.serviceWorkerBasicUsage && capabilities.serviceWorkerShouldInterceptRequest)
        fixture.attach()
        assertProfileServiceWorkersHardened(fixture.runtime.profileName)
        fixture.dispose()
        // A profile may remain loaded after WebView disposal. Force the observable
        // settings stale so this catches a missing forgetProfile on the clear path.
        main {
            val settings = requireNotNull(ProfileStore.getInstance().getProfile(fixture.runtime.profileName))
                .serviceWorkerController.serviceWorkerWebSettings
            settings.blockNetworkLoads = false
            settings.allowContentAccess = true
            settings.allowFileAccess = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
        }
        val cleanup = runBlocking { fixture.manager.clearThenRun(fixture.runtime.toolId) { Unit } }
        assertTrue("Dedicated profile deletion failed: $cleanup", cleanup is RuntimeDataCleanupExecution.Completed)
        fixture.attach()
        assertProfileServiceWorkersHardened(fixture.runtime.profileName)
    }

    private fun assertProfileServiceWorkersHardened(profileName: String) = main {
        val profile = requireNotNull(ProfileStore.getInstance().getProfile(profileName))
        val settings = profile.serviceWorkerController.serviceWorkerWebSettings
        assertTrue(settings.blockNetworkLoads)
        assertFalse(settings.allowContentAccess)
        assertFalse(settings.allowFileAccess)
        assertEquals(WebSettings.LOAD_NO_CACHE, settings.cacheMode)
    }

    private fun timerPayload(sequence: Int) = RpcValue.ObjectValue(
        mapOf("sequence" to RpcValue.Number(sequence.toDouble())),
    )

    private inline fun withFixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { test(fixture) } finally { fixture.close() }
    }

    private class Fixture {
        val scenario = ActivityScenario.launch<WasmRuntimeTestActivity>(
            Intent(InstrumentationRegistry.getInstrumentation().context, WasmRuntimeTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val filesRoot: Path = RuntimeBridgeLifecycleInstrumentationTest.main {
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir.toPath()
        }
        val toolId = "com.example.lifecycle.t${UUID.randomUUID().toString().replace("-", "")}"
        val bundle = filesRoot.resolve(RuntimeIdentity.expectedBundleLocator(toolId, 1))
        val manager = RuntimeProfileManager(filesRoot.toFile())
        val readyAdmissions = AtomicInteger()
        val runtime: PreparedToolRuntime

        init {
            Files.createDirectories(bundle)
            Files.write(bundle.resolve("index.html"),
                "<html><head><script src=\"app.js\"></script></head><body>Bridge lifecycle test</body></html>".toByteArray())
            Files.write(bundle.resolve("app.js"), """
                window.bridgeStates = [];
                window.timerEvents = [];
                window.bridgeReady = false;
                window.fetchedState = null;
                window.documentId = crypto.randomUUID();
                ToolBox.runtime.onStateChanged(state => window.bridgeStates.push(state));
                window.installTimerListener = () => window.unsubscribeTimer = ToolBox.background.onTimer(event => window.timerEvents.push(event.sequence));
                ToolBox.ready().then(() => window.bridgeReady = true);
            """.trimIndent().toByteArray())
            runtime = PreparedToolRuntime(
                toolId = toolId,
                toolName = "Bridge lifecycle test",
                versionCode = 1,
                privateFilesRoot = filesRoot,
                bundleRoot = bundle,
                entry = "index.html",
                origin = RuntimeIdentity.origin(toolId),
                profileName = RuntimeIdentity.profileName(toolId),
                securityProfile = SecurityProfile.STRICT,
                installedManifest = InstalledManifest(toolId, "Bridge lifecycle test", 1, "0.3.0", "index.html",
                    SecurityProfile.STRICT, emptySet(), emptyList(), null),
            )
        }

        fun attach(): WebView {
            val permit = runBlocking {
                when (val result = manager.acquireRuntimePermit(toolId, awaitExistingRuntimeRelease = false)) {
                    is RuntimeCreationPermitResult.Ready -> result.permit
                    is RuntimeCreationPermitResult.Rejected -> error("Runtime permit rejected: ${result.reason}")
                }
            }
            val result = CompletableFuture<WebView>()
            scenario.onActivity { activity ->
                runCatching {
                    activity.attach(runtime, permit, RuntimeWebViewCallbacks({}, { error(it) }, { error("renderer gone") }),
                        RuntimeBridgeProvider {
                            RuntimeBridgeConfiguration(
                                authorization = object : RuntimeAuthorizationPolicy {
                                    override suspend fun isCurrent(identity: RuntimeSessionIdentity) = true
                                    override suspend fun isGranted(identity: RuntimeSessionIdentity, capability: ToolBoxCapabilityId) = false
                                    override suspend fun hasSystemPermissions(identity: RuntimeSessionIdentity, permissions: Set<String>) = false
                                    override suspend fun admit(identity: RuntimeSessionIdentity, method: MethodDescriptor, encodedBytes: Int): RuntimePolicyDecision {
                                        if (method.name == "ready") readyAdmissions.incrementAndGet()
                                        return RuntimePolicyDecision.Allowed
                                    }
                                },
                                handlers = RuntimeM1Handlers(),
                                hostVersion = "0.8.0",
                            )
                        })
                }.onSuccess(result::complete).onFailure(result::completeExceptionally)
            }
            return result.get(10, TimeUnit.SECONDS)
        }

        fun dispose() { scenario.onActivity { it.disposeActiveWebView() } }

        fun close() {
            dispose()
            scenario.close()
            runBlocking { manager.clearThenRun(toolId) { Unit } }
            bundle.parent.parent.parent.toFile().deleteRecursively()
        }
    }

    private fun evaluate(view: WebView, expression: String): String {
        val result = CompletableFuture<String>()
        main { view.evaluateJavascript(expression) { result.complete(it) } }
        return result.get(10, TimeUnit.SECONDS)
    }

    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (check()) return
            Thread.sleep(30)
        }
        error("Real WebView bridge behavior did not complete")
    }

    private companion object {
        fun <T> main(action: () -> T): T {
            val result = CompletableFuture<T>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                runCatching(action).onSuccess(result::complete).onFailure(result::completeExceptionally)
            }
            return result.get(10, TimeUnit.SECONDS)
        }
    }
}
