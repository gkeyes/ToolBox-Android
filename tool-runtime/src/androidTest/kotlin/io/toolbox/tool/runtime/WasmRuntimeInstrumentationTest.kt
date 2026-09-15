package io.toolbox.tool.runtime

import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import io.toolbox.core.data.SecurityProfile
import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxCapabilityId
import io.toolbox.tool.packagekit.InstalledManifest
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises production AssetLoader, CSP and WebView; all tested JS executes from bundle files. */
@RunWith(AndroidJUnit4::class)
class WasmRuntimeInstrumentationTest {
    @Test
    fun strictWasmAndWorkersExecuteWithNoNetworkGrant() = exerciseProfile(SecurityProfile.STRICT)

    @Test
    fun compatWasmAndWorkersExecuteWithNoNetworkGrant() = exerciseProfile(SecurityProfile.COMPAT)

    private fun exerciseProfile(profile: SecurityProfile) {
        val scenario = ActivityScenario.launch(RuntimeWebViewTestActivity::class.java)
        val filesRoot = withActivity(scenario) { it.filesDir.toPath().toAbsolutePath().normalize() }
        val toolId = "com.example.wasm.${profile.name.lowercase()}"
        val toolRoot = filesRoot.resolve("miniapps/$toolId")
        val bundleRoot = filesRoot.resolve(RuntimeIdentity.expectedBundleLocator(toolId, 1))
        val manager = RuntimeProfileManager(filesRoot.toFile())
        deleteTree(toolRoot)
        val runtime = prepareBundle(filesRoot, bundleRoot, toolId, profile)
        var primaryFailure: Throwable? = null
        try {
            verifyResourceResponses(runtime)
            val permit = runBlocking {
                when (val result = manager.acquireRuntimePermit(toolId, awaitExistingRuntimeRelease = false)) {
                    is RuntimeCreationPermitResult.Ready -> result.permit
                    is RuntimeCreationPermitResult.Rejected -> error("Wasm runtime permit rejected: ${result.reason}")
                }
            }
            val loadFailure = AtomicReference<String?>()
            val webView = withActivity(scenario) { activity ->
                activity.attach(
                    runtime,
                    permit,
                    RuntimeWebViewCallbacks(
                        onMainEntryLoaded = {},
                        onMainEntryFailed = { loadFailure.set(it) },
                        onRendererGone = { loadFailure.set("Wasm renderer exited") },
                    ),
                    noGrantsBridgeProvider,
                )
            }
            val provider = onMain { WebViewCompat.getCurrentWebViewPackage(webView.context) }
            report("WASM_ENV android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT} " +
                "webview=${provider?.packageName} version=${provider?.versionName} profile=$profile")
            val result = awaitReport(webView, loadFailure)
            assertFalse("Wasm suite did not finish: $result", result.has("fatal"))
            listOf("page", "classic", "module").forEach { context ->
                val checks = result.getJSONObject(context).getJSONArray("results")
                assertEquals("Missing $context assertions", if (context == "page") 23 else 21, checks.length())
                for (index in 0 until checks.length()) {
                    val check = checks.getJSONObject(index)
                    report("WASM_CHECK profile=$profile context=$context $check")
                    assertTrue("$profile $context: $check", check.getBoolean("pass"))
                }
                // Fetch failures alone are not isolation evidence: each context must have
                // actual CSP reports for both foreign origins and the forbidden Blob Worker.
                assertTrue(result.getJSONObject(context).getJSONArray("violations").length() >= 3)
            }
            assertNull(loadFailure.get())
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailure = runCatching {
                withActivity(scenario) { it.disposeActiveWebView() }
                scenario.close()
                val cleanup = runBlocking { manager.clearThenRun(toolId) { Unit } }
                assertTrue("Wasm runtime cleanup failed: $cleanup", cleanup is RuntimeDataCleanupExecution.Completed)
                deleteTree(toolRoot)
            }.exceptionOrNull()
            if (primaryFailure == null && cleanupFailure != null) throw cleanupFailure
        }
    }

    private fun prepareBundle(filesRoot: Path, bundleRoot: Path, toolId: String, profile: SecurityProfile): PreparedToolRuntime {
        Files.createDirectories(bundleRoot)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        listOf("common.js", "page.js", "classic-worker.js", "module-worker.mjs", "add.wasm", "imports.wasm").forEach { name ->
            assets.open("wasm/$name").use { input -> Files.newOutputStream(bundleRoot.resolve(name)).use { output -> input.copyTo(output) } }
        }
        val wasm = Files.readAllBytes(bundleRoot.resolve("add.wasm"))
        listOf("ADD.WASM", "module.so", "module-bytes").forEach { Files.write(bundleRoot.resolve(it), wasm) }
        listOf("companion.data", "companion.bin", "companion").forEach {
            Files.write(bundleRoot.resolve(it), byteArrayOf(0, -1, 1, -2, 2, -3))
        }
        Files.write(bundleRoot.resolve("empty.data"), byteArrayOf())
        Files.write(bundleRoot.resolve("short.data"), byteArrayOf(0, -1))
        Files.write(bundleRoot.resolve("malformed.wasm"), byteArrayOf(0, 0x61, 0x73, 0x6d))
        RandomAccessFile(bundleRoot.resolve("large.data").toFile(), "rw").use {
            it.setLength(20L * 1024 * 1024 + 1)
            it.write(17)
            it.seek(it.length() - 1)
            it.write(29)
        }
        val otherOrigin = RuntimeIdentity.origin("com.example.wasm.other").removeSuffix("/")
        Files.write(
            bundleRoot.resolve("index.html"),
            """
                <!doctype html>
                <html data-profile="${profile.name.lowercase()}" data-other-origin="$otherOrigin">
                <head><meta charset="utf-8"><script>globalThis.inlineRan = true;</script>
                <script src="common.js"></script><script defer src="page.js"></script></head>
                <body>Wasm runtime behavior test</body></html>
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
        return PreparedToolRuntime(
            toolId = toolId,
            toolName = "Wasm runtime test",
            versionCode = 1,
            privateFilesRoot = filesRoot,
            bundleRoot = bundleRoot,
            entry = "index.html",
            origin = RuntimeIdentity.origin(toolId),
            profileName = RuntimeIdentity.profileName(toolId),
            securityProfile = profile,
            installedManifest = InstalledManifest(
                id = toolId,
                name = "Wasm runtime test",
                versionCode = 1,
                minHostVersion = "0.3.0",
                entry = "index.html",
                securityProfile = profile,
                permissions = emptySet(),
                permissionDeclarations = emptyList(),
                network = null,
                maxBridgePayloadBytes = RuntimeBridgeConfiguration.DEFAULT_MAX_BRIDGE_PAYLOAD_BYTES,
            ),
        )
    }

    private fun verifyResourceResponses(runtime: PreparedToolRuntime) {
        val handler = BundlePathHandler(runtime.privateFilesRoot, runtime.bundleRoot, runtime.securityProfile)
        listOf("add.wasm", "ADD.WASM", "module.so", "module-bytes").forEach { name ->
            val response = handler.handle(name)
            response.data.use { input ->
                assertEquals(200, response.statusCode)
                assertEquals("application/wasm", response.mimeType)
                assertNull(response.encoding)
                assertTrue(Files.readAllBytes(runtime.bundleRoot.resolve("add.wasm")).contentEquals(input.readBytes()))
            }
        }
        listOf("companion.data", "empty.data", "short.data", "large.data").forEach { name ->
            val response = handler.handle(name)
            response.data.use {
                assertEquals(200, response.statusCode)
                assertEquals("application/octet-stream", response.mimeType)
                assertNull(response.encoding)
                assertEquals("nosniff", response.responseHeaders["X-Content-Type-Options"])
            }
        }
        listOf("classic-worker.js", "module-worker.mjs").forEach { name ->
            val response = handler.handle(name)
            response.data.use {
                assertEquals("text/javascript", response.mimeType)
                assertEquals(RuntimePolicy.contentSecurityPolicy(runtime.securityProfile), response.responseHeaders["Content-Security-Policy"])
            }
        }
        listOf("../add.wasm", "%2e%2e/add.wasm", "/add.wasm", "absent.wasm").forEach { path ->
            val response = handler.handle(path)
            response.data.use { assertEquals(404, response.statusCode) }
        }
        val link = runtime.bundleRoot.resolve("linked.wasm")
        Files.createSymbolicLink(link, runtime.bundleRoot.resolve("add.wasm"))
        val response = handler.handle("linked.wasm")
        response.data.use { assertEquals(404, response.statusCode) }
        Files.delete(link)
    }

    private fun awaitReport(webView: WebView, failure: AtomicReference<String?>): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (System.nanoTime() < deadline) {
            failure.get()?.let { error(it) }
            val value = CompletableFuture<String>()
            // Read only. Running eval/Function/Wasm here would bypass the page CSP being tested.
            onMain { webView.evaluateJavascript("JSON.stringify(globalThis.wasmReport || null)") { value.complete(it) } }
            val serialized = JSONTokener(value.get(5, TimeUnit.SECONDS)).nextValue() as String
            if (serialized != "null") return JSONObject(serialized)
            Thread.sleep(100)
        }
        error("Timed out waiting for Wasm fixture report")
    }

    private fun <T> withActivity(scenario: ActivityScenario<RuntimeWebViewTestActivity>, action: (RuntimeWebViewTestActivity) -> T): T {
        val result = CompletableFuture<T>()
        scenario.onActivity { activity -> runCatching { action(activity) }.onSuccess(result::complete).onFailure(result::completeExceptionally) }
        return result.get(10, TimeUnit.SECONDS)
    }

    private fun <T> onMain(action: () -> T): T {
        val result = CompletableFuture<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runCatching(action).onSuccess(result::complete).onFailure(result::completeExceptionally)
        }
        return result.get(10, TimeUnit.SECONDS)
    }

    private fun report(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "$message\n") })
    }

    private fun deleteTree(root: Path) {
        if (Files.exists(root)) Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private val noGrantsBridgeProvider = RuntimeBridgeProvider {
        RuntimeBridgeConfiguration(
            authorization = object : RuntimeAuthorizationPolicy {
                override suspend fun isCurrent(identity: RuntimeSessionIdentity): Boolean = true
                override suspend fun isGranted(identity: RuntimeSessionIdentity, capability: ToolBoxCapabilityId): Boolean = false
                override suspend fun hasSystemPermissions(identity: RuntimeSessionIdentity, permissions: Set<String>): Boolean = false
                override suspend fun admit(identity: RuntimeSessionIdentity, method: MethodDescriptor, encodedBytes: Int): RuntimePolicyDecision =
                    RuntimePolicyDecision.Allowed
            },
            handlers = RuntimeM1Handlers(),
            hostVersion = "0.6.7",
        )
    }
}
