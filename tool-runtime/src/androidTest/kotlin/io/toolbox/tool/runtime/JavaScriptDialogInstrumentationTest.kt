package io.toolbox.tool.runtime

import android.app.AlertDialog
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.core.data.SecurityProfile
import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxCapabilityId
import io.toolbox.tool.packagekit.InstalledManifest
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JavaScriptDialogInstrumentationTest {
    @Test fun strictPageDialogsReturnStandardValuesAndEndOnNavigation() = exercise(SecurityProfile.STRICT)
    @Test fun compatPageDialogsReturnStandardValuesAndEndOnNavigation() = exercise(SecurityProfile.COMPAT)

    private fun exercise(profile: SecurityProfile) {
        val scenario = ActivityScenario.launch(WasmRuntimeTestActivity::class.java)
        val root = main { InstrumentationRegistry.getInstrumentation().targetContext.filesDir.toPath() }
        val id = "io.example.dialog.${UUID.randomUUID().toString().replace("-", "") }"
        val bundle = root.resolve(RuntimeIdentity.expectedBundleLocator(id, 1))
        Files.createDirectories(bundle)
        Files.write(bundle.resolve("index.html"), "<html><head><script src=app.js></script></head><body>Dialog test</body></html>".toByteArray())
        Files.write(bundle.resolve("app.js"), """
            window.dialogReady = true;
            window.runChain = function() {
              history.replaceState(null, '', '#section');
              alert('chain-one');
              window.chainConfirmed = confirm('chain-two');
              window.chainPrompt = prompt('chain-three', '');
              window.chainDone = true;
            };
            window.runDialog = function(kind) {
              window.dialogResult = 'pending';
              if (kind === 'alert') { alert('first\nsecond'); window.dialogResult = 'alert-ok'; }
              if (kind === 'confirm') window.dialogResult = confirm('confirm?');
              if (kind === 'prompt') window.dialogResult = prompt('prompt?', '');
            };
        """.trimIndent().toByteArray())
        val runtime = PreparedToolRuntime(id, "对话框测试", 1, root, bundle, "index.html", RuntimeIdentity.origin(id),
            RuntimeIdentity.profileName(id), profile, InstalledManifest(id, "Test", 1, "0.3.0", "index.html", profile, emptySet(), emptyList(), null))
        val manager = RuntimeProfileManager(root.toFile())
        val permit = runBlocking { (manager.acquireRuntimePermit(id, false) as RuntimeCreationPermitResult.Ready).permit }
        var view: WebView? = null
        val loadedDocuments = AtomicInteger()
        try {
            scenario.onActivity { activity -> view = activity.attach(runtime, permit,
                RuntimeWebViewCallbacks({ loadedDocuments.incrementAndGet() }, { error(it) }, { error("renderer gone") }), provider) }
            val webView = requireNotNull(view)
            await { main { webView.isShown && webView.hasWindowFocus() } && evaluate(webView, "window.dialogReady") == "true" }
            open(webView, "alert")
            main { RuntimeJavaScriptDialogs.current(webView)!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            await { evaluate(webView, "window.dialogResult") == "\"alert-ok\"" }
            open(webView, "confirm")
            main { RuntimeJavaScriptDialogs.current(webView)!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
            await { evaluate(webView, "window.dialogResult") == "false" }
            open(webView, "confirm")
            main { RuntimeJavaScriptDialogs.current(webView)!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            await { evaluate(webView, "window.dialogResult") == "true" }
            open(webView, "prompt")
            main {
                val dialog = RuntimeJavaScriptDialogs.current(webView)!!
                val input = findInput(dialog.window!!.decorView as ViewGroup)!!
                assertEquals("", input.text.toString())
                input.setText("value\nnext")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            await { evaluate(webView, "window.dialogResult") == "\"value\\nnext\"" }
            open(webView, "prompt")
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await { evaluate(webView, "window.dialogResult") == "null" }
            await { main { webView.hasWindowFocus() } }
            val prior = main { RuntimeJavaScriptDialogs.current(webView) }
            main { webView.evaluateJavascript("runChain()", null) }
            var previous = prior
            repeat(3) { index ->
                await("chain dialog ${index + 1}: native window never became interactive") {
                    main { RuntimeJavaScriptDialogs.current(webView)?.let {
                        it !== previous && it.isShowing && it.window?.decorView?.hasWindowFocus() == true
                    } == true }
                }
                previous = main { RuntimeJavaScriptDialogs.current(webView)!! }
                main { previous.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            }
            await { evaluate(webView, "window.chainDone") == "true" }
            assertEquals("true", evaluate(webView, "window.chainConfirmed"))
            assertEquals("\"\"", evaluate(webView, "window.chainPrompt"))
            open(webView, "confirm")
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            assertNull(main { RuntimeJavaScriptDialogs.current(webView) })
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            await { evaluate(webView, "window.dialogResult") == "false" }
            open(webView, "confirm")
            val beforeNavigation = loadedDocuments.get()
            main { webView.loadUrl(runtime.entryUrl) }
            await("Host navigation did not settle the pending dialog") { main { RuntimeJavaScriptDialogs.current(webView) == null } }
            await("Navigated document did not load") {
                loadedDocuments.get() > beforeNavigation && main { webView.hasWindowFocus() } && evaluate(webView, "window.dialogReady") == "true"
            }
            open(webView, "confirm")
            val beforeReload = loadedDocuments.get()
            main { webView.reload() }
            await("Host reload did not settle the pending dialog") { main { RuntimeJavaScriptDialogs.current(webView) == null } }
            await("Reloaded document did not load") {
                loadedDocuments.get() > beforeReload && main { webView.hasWindowFocus() } && evaluate(webView, "window.dialogReady") == "true"
            }
            open(webView, "alert")
            val interrupted = main { RuntimeJavaScriptDialogs.current(webView)!! }
            scenario.recreate()
            assertNull(main { RuntimeJavaScriptDialogs.current(webView) })
            assertFalse(main { interrupted.isShowing })
        } finally {
            scenario.close()
            bundle.parent.parent.parent.toFile().deleteRecursively()
        }
    }
    private fun open(view: WebView, kind: String) {
        await { main { view.hasWindowFocus() } }
        main { view.evaluateJavascript("runDialog('$kind')", null) }
        await("$kind dialog window never became interactive") {
            main { RuntimeJavaScriptDialogs.current(view)?.let {
                it.isShowing && it.window?.decorView?.hasWindowFocus() == true
            } == true }
        }
    }
    private fun evaluate(view: WebView, js: String): String {
        val result = CompletableFuture<String>()
        main { view.evaluateJavascript(js) { result.complete(it) } }
        return result.get(10, TimeUnit.SECONDS)
    }
    private fun findInput(group: ViewGroup): EditText? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is EditText) return child
            if (child is ViewGroup) findInput(child)?.let { return it }
        }
        return null
    }
    private fun await(description: String = "Dialog behavior did not complete", check: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < until) { if (check()) return; Thread.sleep(30) }
        error(description)
    }
    private fun <T> main(action: () -> T): T {
        val result = CompletableFuture<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { runCatching(action).onSuccess(result::complete).onFailure(result::completeExceptionally) }
        return result.get(10, TimeUnit.SECONDS)
    }
    private val provider = RuntimeBridgeProvider { RuntimeBridgeConfiguration(object : RuntimeAuthorizationPolicy {
        override suspend fun isCurrent(identity: RuntimeSessionIdentity) = true
        override suspend fun isGranted(identity: RuntimeSessionIdentity, capability: ToolBoxCapabilityId) = false
        override suspend fun hasSystemPermissions(identity: RuntimeSessionIdentity, permissions: Set<String>) = false
        override suspend fun admit(identity: RuntimeSessionIdentity, method: MethodDescriptor, encodedBytes: Int) = RuntimePolicyDecision.Allowed
    }, RuntimeM1Handlers(), hostVersion = "0.8.0") }
}
