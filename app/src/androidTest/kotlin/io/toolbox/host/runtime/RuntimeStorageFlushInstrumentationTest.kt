package io.toolbox.host.runtime

import android.content.Intent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.core.data.CoreDataFactory
import io.toolbox.core.data.BundleLocator
import io.toolbox.core.data.CatalogInstallAttempt
import io.toolbox.core.data.CoreDataStores
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.InstallTransaction
import io.toolbox.core.data.InstallTransactionState
import io.toolbox.core.data.SecurityProfile
import io.toolbox.core.data.ToolMetadata
import io.toolbox.core.data.ToolVersion
import io.toolbox.host.MainActivity
import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxCapabilityId
import io.toolbox.tool.packagekit.InstalledManifest
import io.toolbox.tool.runtime.HardenedRuntimeWebView
import io.toolbox.tool.runtime.PreparedToolRuntime
import io.toolbox.tool.runtime.RpcValue
import io.toolbox.tool.runtime.RuntimeAuthorizationPolicy
import io.toolbox.tool.runtime.RuntimeBatchStorageHandler
import io.toolbox.tool.runtime.RuntimeBridgeConfiguration
import io.toolbox.tool.runtime.RuntimeBridgeProvider
import io.toolbox.tool.runtime.RuntimeCreationPermit
import io.toolbox.tool.runtime.RuntimeCreationPermitResult
import io.toolbox.tool.runtime.RuntimeDataCleanupExecution
import io.toolbox.tool.runtime.RuntimeM1Handlers
import io.toolbox.tool.runtime.RuntimePolicyDecision
import io.toolbox.tool.runtime.RuntimeProfileManager
import io.toolbox.tool.runtime.RuntimeSessionIdentity
import io.toolbox.tool.runtime.RuntimeStorageMutation
import io.toolbox.tool.runtime.RuntimeWebViewCallbacks
import io.toolbox.tool.runtime.RuntimeWebViewCreationResult
import io.toolbox.tool.runtime.RuntimeIdentity
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeStorageFlushInstrumentationTest {
    @Test
    fun nonemptyFinalWriteWaitsForRoomThenSealRejectsAndCancelAllowsNewWrite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val toolId = "com.example.flush.$suffix"
        val databaseName = "runtime-flush-$suffix.db"
        val stores = CoreDataFactory.create(context, databaseName, "runtime-flush-settings")
        val root = context.filesDir.toPath()
        val bundle = root.resolve(RuntimeIdentity.expectedBundleLocator(toolId, 1))
        val manager = RuntimeProfileManager(context.filesDir)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        var attachedView: WebView? = null
        try {
            Files.createDirectories(bundle)
            Files.write(bundle.resolve("index.html"),
                "<html><head><script src=\"app.js\"></script></head><body>Storage flush test</body></html>".toByteArray())
            Files.write(bundle.resolve("app.js"), """
                window.bridgeReady = false;
                window.writeResult = null;
                ToolBox.runtime.registerFlushHandler(() =>
                  ToolBox.storage.apply({ set: [{ key: 'checkpoint', value: { value: 'saved' } }] }));
                ToolBox.ready().then(() => window.bridgeReady = true);
                window.tryWrite = (key, value) => ToolBox.storage.set(key, value)
                  .then(() => window.writeResult = 'ok', error => window.writeResult = error.code);
            """.trimIndent().toByteArray())
            runBlocking { installCatalog(stores, toolId) }
            val runtime = PreparedToolRuntime(
                toolId = toolId,
                toolName = "Storage flush test",
                versionCode = 1,
                privateFilesRoot = root,
                bundleRoot = bundle,
                entry = "index.html",
                origin = RuntimeIdentity.origin(toolId),
                profileName = RuntimeIdentity.profileName(toolId),
                securityProfile = SecurityProfile.STRICT,
                installedManifest = InstalledManifest(toolId, "Storage flush test", 1, "0.3.0", "index.html",
                    SecurityProfile.STRICT, setOf("storage"), emptyList(), null),
                declaredCapabilities = setOf("storage"),
            )
            val actualStorage = StandardToolKvStorageHandler(toolId, stores.repositories.keyValues, System::currentTimeMillis)
            val controlledStorage = DelayedStorage(actualStorage)
            val permit = runBlocking {
                when (val result = manager.acquireRuntimePermit(toolId, awaitExistingRuntimeRelease = false)) {
                    is RuntimeCreationPermitResult.Ready -> result.permit
                    is RuntimeCreationPermitResult.Rejected -> error("Runtime permit rejected: ${result.reason}")
                }
            }
            val opened = CompletableFuture<WebView>()
            scenario.onActivity { activity ->
                runCatching {
                    attach(activity, runtime, permit, RuntimeBridgeProvider {
                        RuntimeBridgeConfiguration(
                            authorization = storageAuthorization,
                            handlers = RuntimeM1Handlers(storage = controlledStorage),
                            hostVersion = "0.8.0",
                        )
                    })
                }.onSuccess { view ->
                    attachedView = view
                    opened.complete(view)
                }.onFailure(opened::completeExceptionally)
            }
            val view = opened.get(10, TimeUnit.SECONDS)
            await { evaluate(view, "window.bridgeReady") == "true" }

            val closing = CompletableFuture.supplyAsync {
                runBlocking { HardenedRuntimeWebView.flushBeforeClose(view, timeoutMillis = null) }
            }
            runBlocking { withTimeout(10_000) { controlledStorage.entered.await() } }
            assertFalse("Close must wait for the admitted Room write", closing.isDone)
            assertNull(runBlocking { actualStorage.get("checkpoint") })
            // A later external stop shares the soft close ticket, but its own
            // two-second call must still return without cancelling the UI wait.
            val externalStop = CompletableFuture.supplyAsync {
                runBlocking { HardenedRuntimeWebView.flushBeforeClose(view, timeoutMillis = 2_000L) }
            }
            assertFalse(externalStop.get(5, TimeUnit.SECONDS))
            assertFalse(closing.isDone)
            controlledStorage.release.complete(Unit)
            assertTrue("The final save was not acknowledged", closing.get(10, TimeUnit.SECONDS))
            assertEquals(RpcValue.ObjectValue(mapOf("value" to RpcValue.StringValue("saved"))),
                runBlocking { actualStorage.get("checkpoint") })

            main { view.evaluateJavascript("window.tryWrite('late', 'before-cancel')", null) }
            await { evaluate(view, "window.writeResult") == "\"SESSION_ENDED\"" }
            assertNull(runBlocking { actualStorage.get("late") })

            main { HardenedRuntimeWebView.cancelClose(view) }
            main { view.evaluateJavascript("window.writeResult = null; window.tryWrite('late', 'after-cancel')", null) }
            await { evaluate(view, "window.writeResult") == "\"ok\"" }
            assertEquals(RpcValue.StringValue("after-cancel"), runBlocking { actualStorage.get("late") })
        } finally {
            scenario.onActivity {
                attachedView?.let { view ->
                    (view.parent as? ViewGroup)?.removeView(view)
                    HardenedRuntimeWebView.release(view)
                }
            }
            scenario.close()
            runBlocking { manager.clearThenRun(toolId) { Unit } }.let {
                assertTrue("Runtime profile cleanup failed: $it", it is RuntimeDataCleanupExecution.Completed)
            }
            stores.close()
            context.deleteDatabase(databaseName)
            bundle.parent.parent.parent.toFile().deleteRecursively()
        }
    }

    private suspend fun installCatalog(stores: CoreDataStores, toolId: String) {
        val transactionId = UUID.randomUUID().toString()
        val installs = stores.repositories.installs
        assertTrue(installs.begin(InstallTransaction(transactionId, toolId, 1,
            InstallTransactionState.PREPARING, 1, 1)) is DataResult.Success)
        assertTrue(installs.markCommitting(transactionId, 1) is DataResult.Success)
        val committed = stores.repositories.lifecycle.commitInstall(CatalogInstallAttempt(
            transactionId,
            ToolMetadata(toolId, "Storage flush test", SecurityProfile.STRICT, 1),
            ToolVersion(toolId, 1, "1.0.0", BundleLocator(RuntimeIdentity.expectedBundleLocator(toolId, 1)),
                0, "a".repeat(64), 1),
            emptyList(),
        ))
        assertTrue("Catalog install failed: $committed", committed is DataResult.Success)
    }

    private class DelayedStorage(private val delegate: RuntimeBatchStorageHandler) : RuntimeBatchStorageHandler by delegate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun apply(mutation: RuntimeStorageMutation) {
            entered.complete(Unit)
            release.await()
            delegate.apply(mutation)
        }
    }

    private val storageAuthorization = object : RuntimeAuthorizationPolicy {
        override suspend fun isCurrent(identity: RuntimeSessionIdentity) = true
        override suspend fun isGranted(identity: RuntimeSessionIdentity, capability: ToolBoxCapabilityId) =
            capability == ToolBoxCapabilityId.STORAGE
        override suspend fun hasSystemPermissions(identity: RuntimeSessionIdentity, permissions: Set<String>) = true
        override suspend fun admit(identity: RuntimeSessionIdentity, method: MethodDescriptor, encodedBytes: Int) =
            RuntimePolicyDecision.Allowed
    }

    private fun attach(
        activity: MainActivity,
        runtime: PreparedToolRuntime,
        permit: RuntimeCreationPermit,
        provider: RuntimeBridgeProvider,
    ): WebView {
        val content = FrameLayout(activity)
        activity.setContentView(content)
        val result = HardenedRuntimeWebView.create(activity, runtime, permit,
            RuntimeWebViewCallbacks({}, { error(it) }, { error("renderer gone") }), provider)
        val view = when (result) {
            is RuntimeWebViewCreationResult.Created -> result.webView
            is RuntimeWebViewCreationResult.Failed -> error(result.message)
        }
        content.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return view
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
        error("Storage flush in the real WebView did not complete")
    }

    private fun <T> main(action: () -> T): T {
        val result = CompletableFuture<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runCatching(action).onSuccess(result::complete).onFailure(result::completeExceptionally)
        }
        return result.get(10, TimeUnit.SECONDS)
    }
}
