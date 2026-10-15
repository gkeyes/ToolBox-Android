package io.toolbox.host.runtime

import android.content.Intent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.core.data.SecurityProfile
import io.toolbox.host.MainActivity
import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxCapabilityId
import io.toolbox.tool.packagekit.InstalledManifest
import io.toolbox.tool.runtime.HardenedRuntimeWebView
import io.toolbox.tool.runtime.PreparedToolRuntime
import io.toolbox.tool.runtime.RuntimeAuthorizationPolicy
import io.toolbox.tool.runtime.RuntimeBridgeConfiguration
import io.toolbox.tool.runtime.RuntimeBridgeProvider
import io.toolbox.tool.runtime.RuntimeCoreHandlers
import io.toolbox.tool.runtime.RuntimeCreationPermitResult
import io.toolbox.tool.runtime.RuntimeDataCleanupExecution
import io.toolbox.tool.runtime.RuntimeIdentity
import io.toolbox.tool.runtime.RuntimePolicyDecision
import io.toolbox.tool.runtime.RuntimeProfileManager
import io.toolbox.tool.runtime.RuntimeSessionIdentity
import io.toolbox.tool.runtime.RuntimeWebViewCallbacks
import io.toolbox.tool.runtime.RuntimeWebViewCreationResult
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SEC-X1: WebRTC must be gone in every frame of a real tool WebView, including a sandboxed
 * srcdoc iframe whose origin is opaque, a same-origin about:blank iframe, and an iframe reached in
 * the same task as its insertion through contentWindow/contentDocument, window[i], frames[i] or its name.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeWebRtcFrameInstrumentationTest {
    @Test
    fun webRtcIsBlockedInMainFrameSandboxedSrcdocAndBlankIframes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val toolId = "com.example.webrtc.t$suffix"
        // Not under filesDir/miniapps: MainActivity's startup maintenance deletes bundles of tools the
        // catalog does not know, which raced this test's bundle and served the entry as a 404.
        val root = context.filesDir.toPath().resolve("webrtc-frame-test-$suffix")
        val bundle = root.resolve(RuntimeIdentity.expectedBundleLocator(toolId, 1))
        val manager = RuntimeProfileManager(context.filesDir)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        var attachedView: WebView? = null
        try {
            Files.createDirectories(bundle)
            Files.write(bundle.resolve("index.html"),
                "<html><body>WebRTC frame test<script src=\"app.js\"></script></body></html>".toByteArray())
            // COMPAT allows inline scripts, and a srcdoc frame inherits the parent CSP, so its
            // inline probe runs with an opaque origin and reports back through postMessage.
            Files.write(bundle.resolve("app.js"), """
                const probe = scope => typeof scope.RTCPeerConnection + '|' + (scope.__toolboxWebRtcBlocked === true);
                window.mainProbe = probe(window);
                // Same task as appendChild: the child's initial about:blank may not have run the
                // document-start script yet, so the parent's frame accessors must lock it first.
                const childProbe = (insert, read) => {
                  const frame = insert();
                  let type;
                  let created;
                  let marked;
                  try { const child = read(frame); type = typeof child.RTCPeerConnection; marked = child.__toolboxWebRtcBlocked === true; }
                  catch (error) { type = 'threw:' + error; }
                  try { new (read(frame).RTCPeerConnection)({ iceServers: [] }); created = 'constructed'; }
                  catch (_) { created = 'blocked'; }
                  return type + '|' + created + '|' + marked;
                };
                const appended = () => document.body.appendChild(document.createElement('iframe'));
                window.sameTaskWindowProbe = childProbe(appended, frame => frame.contentWindow);
                window.sameTaskDocumentProbe = childProbe(appended, frame => frame.contentDocument.defaultView);
                // Indexed and named access reach the child without any frame-element accessor.
                window.indexedProbe = childProbe(appended, () => window[window.length - 1]);
                window.framesProbe = childProbe(appended, () => frames[frames.length - 1]);
                window.namedHtmlProbe = childProbe(() => {
                  document.body.insertAdjacentHTML('beforeend', '<iframe name="rtcNamed"></iframe>');
                }, () => window.rtcNamed);
                // Known residual (SEC-X1, docs/runtime.md): a custom-element reaction runs inside the
                // insertion, before its lock. Pinned so a WebView that closes it shows up here.
                window.reactionProbe = null;
                customElements.define('rtc-reaction-probe', class extends HTMLElement {
                  connectedCallback() {
                    if (window.reactionProbe !== null) return;
                    try { window.reactionProbe = typeof window[window.length - 1].RTCPeerConnection; }
                    catch (error) { window.reactionProbe = 'threw:' + error; }
                  }
                });
                const reactionHost = document.createElement('div');
                reactionHost.append(document.createElement('iframe'), document.createElement('rtc-reaction-probe'));
                document.body.appendChild(reactionHost);
                window.afterReactionProbe = typeof window[window.length - 1].RTCPeerConnection;
                window.srcdocProbe = null;
                window.addEventListener('message', event => {
                  if (event.data && typeof event.data.rtcProbe === 'string') window.srcdocProbe = event.data.rtcProbe;
                });
                const sandboxed = document.createElement('iframe');
                sandboxed.setAttribute('sandbox', 'allow-scripts');
                sandboxed.srcdoc = '<script>parent.postMessage({ rtcProbe: typeof RTCPeerConnection + "|" + ' +
                  '(globalThis.__toolboxWebRtcBlocked === true) + "|" + String(origin) }, "*");<\/script>';
                document.body.appendChild(sandboxed);
                const blank = document.createElement('iframe');
                document.body.appendChild(blank);
                window.blankProbe = () => probe(blank.contentWindow);
            """.trimIndent().toByteArray())
            val runtime = PreparedToolRuntime(
                toolId = toolId,
                toolName = "WebRTC frame test",
                versionCode = 1,
                privateFilesRoot = root,
                bundleRoot = bundle,
                entry = "index.html",
                origin = RuntimeIdentity.origin(toolId),
                profileName = RuntimeIdentity.profileName(toolId),
                securityProfile = SecurityProfile.COMPAT,
                installedManifest = InstalledManifest(toolId, "WebRTC frame test", 1, "0.3.0", "index.html",
                    SecurityProfile.COMPAT, emptySet(), emptyList(), null),
                declaredCapabilities = emptySet(),
            )
            val permit = runBlocking {
                when (val result = manager.acquireRuntimePermit(toolId, awaitExistingRuntimeRelease = false)) {
                    is RuntimeCreationPermitResult.Ready -> result.permit
                    is RuntimeCreationPermitResult.Rejected -> error("Runtime permit rejected: ${result.reason}")
                }
            }
            val opened = CompletableFuture<WebView>()
            scenario.onActivity { activity ->
                runCatching {
                    val content = FrameLayout(activity)
                    activity.setContentView(content)
                    val result = HardenedRuntimeWebView.create(activity, runtime, permit,
                        RuntimeWebViewCallbacks({}, { error(it) }, { error("renderer gone") }),
                        RuntimeBridgeProvider {
                            RuntimeBridgeConfiguration(
                                authorization = denyAll,
                                handlers = RuntimeCoreHandlers(),
                                hostVersion = "0.8.0",
                            )
                        })
                    val view = when (result) {
                        is RuntimeWebViewCreationResult.Created -> result.webView
                        is RuntimeWebViewCreationResult.Failed -> error(result.message)
                    }
                    content.addView(view, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    view
                }.onSuccess { view ->
                    attachedView = view
                    opened.complete(view)
                }.onFailure(opened::completeExceptionally)
            }
            val view = opened.get(10, TimeUnit.SECONDS)

            await { evaluate(view, "typeof window.mainProbe") == "\"string\"" }
            assertEquals("\"undefined|true\"", evaluate(view, "window.mainProbe"))
            assertEquals("\"undefined|blocked|true\"", evaluate(view, "window.sameTaskWindowProbe"))
            assertEquals("\"undefined|blocked|true\"", evaluate(view, "window.sameTaskDocumentProbe"))
            assertEquals("\"undefined|blocked|true\"", evaluate(view, "window.indexedProbe"))
            assertEquals("\"undefined|blocked|true\"", evaluate(view, "window.framesProbe"))
            assertEquals("\"undefined|blocked|true\"", evaluate(view, "window.namedHtmlProbe"))
            assertEquals("A custom-element reaction inside the insertion still sees the unlocked frame (known residual)",
                "\"function\"", evaluate(view, "window.reactionProbe"))
            assertEquals("\"undefined\"", evaluate(view, "window.afterReactionProbe"))
            await { evaluate(view, "window.srcdocProbe !== null") == "true" }
            val srcdoc = evaluate(view, "window.srcdocProbe")
            assertTrue("The sandboxed srcdoc frame must be opaque: $srcdoc", srcdoc.endsWith("|null\""))
            assertTrue("WebRTC must be blocked in an opaque-origin iframe: $srcdoc",
                srcdoc.startsWith("\"undefined|true|"))
            assertEquals("\"undefined|true\"", evaluate(view, "window.blankProbe()"))
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
            root.toFile().deleteRecursively()
        }
    }

    private val denyAll = object : RuntimeAuthorizationPolicy {
        override suspend fun isCurrent(identity: RuntimeSessionIdentity) = true
        override suspend fun isGranted(identity: RuntimeSessionIdentity, capability: ToolBoxCapabilityId) = false
        override suspend fun hasSystemPermissions(identity: RuntimeSessionIdentity, permissions: Set<String>) = true
        override suspend fun admit(identity: RuntimeSessionIdentity, method: MethodDescriptor, encodedBytes: Int) =
            RuntimePolicyDecision.Allowed
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
        error("The WebRTC frame probe did not report")
    }

    private fun <T> main(action: () -> T): T {
        val result = CompletableFuture<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runCatching(action).onSuccess(result::complete).onFailure(result::completeExceptionally)
        }
        return result.get(10, TimeUnit.SECONDS)
    }
}
