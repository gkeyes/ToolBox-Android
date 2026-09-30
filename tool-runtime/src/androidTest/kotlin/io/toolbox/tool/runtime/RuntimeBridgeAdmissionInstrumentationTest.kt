package io.toolbox.tool.runtime

import android.annotation.SuppressLint
import android.content.Intent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxCapabilityId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production native admission path and document-start SDK together. */
@RunWith(AndroidJUnit4::class)
class RuntimeBridgeAdmissionInstrumentationTest {
    @SuppressLint("SetJavaScriptEnabled")
    @Test
    fun busyOrdinaryAdmissionRetriesTheSameNonemptyFinalWriteWhileControlsRemainAvailable() {
        val scenario = ActivityScenario.launch<WasmRuntimeTestActivity>(
            Intent(InstrumentationRegistry.getInstrumentation().context, WasmRuntimeTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val toolId = "io.example.bridgebudget"
        val identity = RuntimeSessionIdentity(
            toolId = toolId,
            versionCode = 1,
            generation = "budget-generation",
            hostVersion = "0.8.0",
            nonce = "budget-nonce",
            exactOrigin = RuntimeIdentity.origin(toolId),
            declaredCapabilities = setOf("storage"),
        )
        val holdEntered = CompletableDeferred<Unit>()
        val releaseHold = CompletableDeferred<Unit>()
        val appliedMutation = AtomicReference<RuntimeStorageMutation?>()
        val handler = object : RuntimeBatchStorageHandler {
            override suspend fun get(key: String): RpcValue? {
                holdEntered.complete(Unit)
                releaseHold.await()
                return null
            }
            override suspend fun getMany(keys: List<String>): List<RpcValue?> = keys.map { get(it) }
            override suspend fun apply(mutation: RuntimeStorageMutation) { appliedMutation.set(mutation) }
            override suspend fun set(key: String, value: RpcValue) = Unit
            override suspend fun remove(key: String) = Unit
            override suspend fun keys(): List<String> = emptyList()
            override suspend fun clear() = Unit
        }
        val policy = object : RuntimeAuthorizationPolicy {
            override suspend fun isCurrent(identity: RuntimeSessionIdentity) = true
            override suspend fun isGranted(identity: RuntimeSessionIdentity, capability: ToolBoxCapabilityId) =
                capability == ToolBoxCapabilityId.STORAGE
            override suspend fun hasSystemPermissions(identity: RuntimeSessionIdentity, permissions: Set<String>) = true
            override suspend fun admit(identity: RuntimeSessionIdentity, method: MethodDescriptor, encodedBytes: Int) =
                RuntimePolicyDecision.Allowed
        }
        // The 800-character read fits 2 KiB alone; retaining its UTF-16 request
        // leaves less than one final storage.apply envelope until it finishes.
        val session = RuntimeBridgeSession(identity, policy, RuntimeM1Handlers(storage = handler),
            ordinaryBudget = RuntimeRequestBudget(2_048))
        var view: WebView? = null
        try {
            val created = CompletableFuture<WebView>()
            scenario.onActivity { activity ->
                runCatching {
                    WebView(activity).also { webView ->
                        webView.settings.javaScriptEnabled = true
                        activity.addContentView(webView, FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        session.attach(webView)
                        webView.loadDataWithBaseURL(identity.exactOrigin + "index.html", """
                            <html><body><script>
                              window.bridgeReady = false;
                              window.holdDone = false;
                              window.controlState = null;
                              window.closeObserved = false;
                              ToolBox.runtime.onStateChanged(state => window.closeObserved = state.closing);
                              ToolBox.runtime.registerFlushHandler(() =>
                                ToolBox.storage.apply({ set: [{ key: 'checkpoint', value: { value: 'saved' } }] }));
                              ToolBox.ready().then(() => window.bridgeReady = true);
                              window.beginHold = () => ToolBox.storage.get('h'.repeat(800))
                                .then(() => window.holdDone = true);
                            </script></body></html>
                        """.trimIndent(), "text/html", "UTF-8", null)
                    }
                }.onSuccess(created::complete).onFailure(created::completeExceptionally)
            }
            view = created.get(10, TimeUnit.SECONDS)
            val webView = requireNotNull(view)
            await { evaluate(webView, "window.bridgeReady") == "true" }
            main { webView.evaluateJavascript("window.beginHold()", null) }
            runBlocking { withTimeout(10_000) { holdEntered.await() } }

            main { webView.evaluateJavascript("ToolBox.runtime.getState().then(state => window.controlState = state)", null) }
            await { evaluate(webView, "window.controlState && window.controlState.generation") == "\"budget-generation\"" }
            val closing = CompletableFuture.supplyAsync { runBlocking { session.flushBeforeClose(webView, null) } }
            await { session.isClosing() }
            await { evaluate(webView, "window.closeObserved") == "true" }
            // Allow more than two 50 ms SDK retries while the ordinary budget is held.
            Thread.sleep(160)
            assertNull("Final write reached the handler before budget was released", appliedMutation.get())
            assertFalse(closing.isDone)

            releaseHold.complete(Unit)
            await { evaluate(webView, "window.holdDone") == "true" }
            assertTrue("Control flushComplete was blocked with ordinary work", closing.get(10, TimeUnit.SECONDS))
            assertEquals(RuntimeStorageMutation(set = listOf(RuntimeStorageSet("checkpoint",
                RpcValue.ObjectValue(mapOf("value" to RpcValue.StringValue("saved")))))), appliedMutation.get())
        } finally {
            releaseHold.complete(Unit)
            view?.let { webView -> main {
                RuntimeBridgeLifecycle.release(webView)
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            } }
            scenario.close()
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
        error("Bridge admission did not complete in the real WebView")
    }

    private fun <T> main(action: () -> T): T {
        val result = CompletableFuture<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runCatching(action).onSuccess(result::complete).onFailure(result::completeExceptionally)
        }
        return result.get(10, TimeUnit.SECONDS)
    }
}
