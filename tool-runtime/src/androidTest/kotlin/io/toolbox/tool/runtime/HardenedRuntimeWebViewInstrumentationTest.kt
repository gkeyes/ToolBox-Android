package io.toolbox.tool.runtime

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.webkit.GeolocationPermissions
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewRenderProcess
import androidx.webkit.ProfileStore
import io.toolbox.core.data.SecurityProfile
import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxCapabilityId
import io.toolbox.tool.packagekit.InstalledManifest
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private val instrumentationBridgeProvider = RuntimeBridgeProvider {
    RuntimeBridgeConfiguration(
        authorization = object : RuntimeAuthorizationPolicy {
            override suspend fun isCurrent(identity: RuntimeSessionIdentity): Boolean = true

            override suspend fun isGranted(
                identity: RuntimeSessionIdentity,
                capability: ToolBoxCapabilityId,
            ): Boolean = true

            override suspend fun hasSystemPermissions(
                identity: RuntimeSessionIdentity,
                permissions: Set<String>,
            ): Boolean = true

            override suspend fun admit(
                identity: RuntimeSessionIdentity,
                method: MethodDescriptor,
                encodedBytes: Int,
            ): RuntimePolicyDecision = RuntimePolicyDecision.Allowed
        },
        handlers = RuntimeM1Handlers(),
        hostVersion = "0.2.0",
    )
}

@RunWith(AndroidJUnit4::class)
class HardenedRuntimeWebViewInstrumentationTest {
    private lateinit var profileManager: RuntimeProfileManager

    @Test
    fun realWebViewEnforcesOfflineBoundaryAndContainsRendererLoss() {
        val scenario = ActivityScenario.launch(RuntimeWebViewTestActivity::class.java)
        val filesRoot = withActivity(scenario) { it.filesDir.toPath().toAbsolutePath().normalize() }
        profileManager = RuntimeProfileManager(filesRoot.toFile())
        val toolRoot = filesRoot.resolve("miniapps/$TOOL_ID")
        val bundleRoot = filesRoot.resolve(RuntimeIdentity.expectedBundleLocator(TOOL_ID, VERSION_CODE))
        deleteTree(toolRoot)
        writeRuntimeBundle(bundleRoot)

        val runtime = preparedRuntime(filesRoot, bundleRoot, "index.html")
        val capabilities = runBlocking { profileManager.providerCapabilities() }
        val expectedMode = capabilities.preferredIsolationMode
        reportCapabilities(capabilities)
        var primaryFailure: Throwable? = null
        try {
            exerciseIsolationModeTransitionContracts(filesRoot)
            exerciseStatelessCapabilityRejectionContracts(filesRoot)
            exerciseLoadedRuntime(scenario, runtime, expectedMode)
            if (expectedMode == RuntimeIsolationMode.ORIGIN_ONLY_STATELESS) {
                exercisePreexistingCookieRejection(scenario, runtime)
            }
            exerciseProfileManagerFailureAndRecoveryBranches(filesRoot, expectedMode, capabilities.multiProfile)
            exerciseRendererLoss(scenario, runtime.copy(entry = "hang.html"))
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailure = runCatching {
                withActivity(scenario) { it.disposeActiveWebView() }
                scenario.close()
                val cleanupResult = runCleanup()
                assertTrue(
                    "The test runtime data must be removed after its Activity closes: $cleanupResult",
                    cleanupResult == RuntimeDataCleanupResult.Cleared ||
                        cleanupResult == RuntimeDataCleanupResult.AlreadyAbsent,
                )
                val finalPermit = acquirePermit()
                onMain { finalPermit.close() }
                deleteTree(toolRoot)
            }.exceptionOrNull()
            if (primaryFailure == null && cleanupFailure != null) throw cleanupFailure
        }
    }

    @Suppress("DEPRECATION")
    private fun exerciseLoadedRuntime(
        scenario: ActivityScenario<RuntimeWebViewTestActivity>,
        runtime: PreparedToolRuntime,
        expectedMode: RuntimeIsolationMode,
    ) {
        val loaded = CountDownLatch(1)
        val loadCount = AtomicInteger(0)
        val failed = AtomicReference<String?>()
        val rendererGone = AtomicInteger(0)
        val initialPermit = acquirePermit()
        assertEquals(expectedMode, initialPermit.isolationMode)
        val webView = withActivity(scenario) { activity ->
            activity.attach(
                runtime,
                initialPermit,
                RuntimeWebViewCallbacks(
                    onMainEntryLoaded = {
                        loadCount.incrementAndGet()
                        loaded.countDown()
                    },
                    onMainEntryFailed = { failed.set(it) },
                    onRendererGone = { rendererGone.incrementAndGet() },
                ),
            )
        }

        assertTrue("The exact local HTTPS entry did not finish loading", loaded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(1, loadCount.get())
        assertNull(failed.get())
        assertEquals(0, rendererGone.get())
        assertEquals(runtime.entryUrl, onMain { webView.url })
        assertEquals(quote(runtime.origin.removeSuffix("/")), evaluateJavaScript(webView, "location.origin"))
        assertEquals(quote(runtime.entryUrl), evaluateJavaScript(webView, "location.href"))
        assertEquals("true", evaluateJavaScript(webView, "window.__pageExecuted === true"))
        assertEquals("true", evaluateJavaScript(webView, "window.__toolBoxPresent === true"))
        assertEquals(quote("object"), evaluateJavaScript(webView, "typeof window.ToolBox"))
        assertTrue(
            "The page's direct remote fetch was not rejected by the combined CSP/offline boundary",
            awaitJavaScriptValue(webView, "window.__remoteFetchState", quote("blocked")),
        )

        onMain {
            with(webView.settings) {
                assertTrue(javaScriptEnabled)
                assertEquals(expectedMode == RuntimeIsolationMode.DEDICATED_PROFILE, domStorageEnabled)
                assertEquals(expectedMode == RuntimeIsolationMode.DEDICATED_PROFILE, databaseEnabled)
                assertFalse(allowFileAccess)
                assertFalse(allowContentAccess)
                assertFalse(allowFileAccessFromFileURLs)
                assertFalse(allowUniversalAccessFromFileURLs)
                assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, mixedContentMode)
                assertFalse(supportMultipleWindows())
                assertFalse(javaScriptCanOpenWindowsAutomatically)
                assertTrue(mediaPlaybackRequiresUserGesture)
                assertFalse(saveFormData)
                assertEquals(WebSettings.LOAD_NO_CACHE, cacheMode)
            }
            val cookieManager = if (expectedMode == RuntimeIsolationMode.DEDICATED_PROFILE) {
                requireNotNull(ProfileStore.getInstance().getProfile(runtime.profileName)).cookieManager
            } else {
                CookieManager.getInstance()
            }
            assertFalse(cookieManager.acceptCookie())
            assertFalse(cookieManager.acceptThirdPartyCookies(webView))
        }

        val client = onMain { webView.webViewClient }
        val entryResponse = requireNotNull(
            client.shouldInterceptRequest(webView, TestWebResourceRequest(runtime.entryUrl)),
        )
        try {
            assertEquals(200, entryResponse.statusCode)
            assertTrue(
                entryResponse.responseHeaders.getValue("Content-Security-Policy").contains("connect-src 'none'"),
            )
        } finally {
            entryResponse.data.close()
        }
        assertFalse(onMain { client.shouldOverrideUrlLoading(webView, TestWebResourceRequest(runtime.entryUrl)) })
        listOf(
            "file:///data/local/tmp/index.html",
            "content://io.toolbox.provider/index.html",
            "intent://index.html#Intent;scheme=https;end",
            "javascript:alert(1)",
            "http://localhost/index.html",
            "https://127.0.0.1/index.html",
            "https://example.com/index.html",
        ).forEach { blockedUrl ->
            assertTrue(
                "Navigation must be rejected: $blockedUrl",
                onMain { client.shouldOverrideUrlLoading(webView, TestWebResourceRequest(blockedUrl)) },
            )
        }

        val chrome = requireNotNull(onMain { webView.webChromeClient })
        val chooserCalled = AtomicBoolean(false)
        val chooserValue = AtomicReference<Array<Uri>?>()
        assertTrue(
            onMain {
                chrome.onShowFileChooser(
                    webView,
                    ValueCallback { value ->
                        chooserValue.set(value)
                        chooserCalled.set(true)
                    },
                    TestFileChooserParams,
                )
            },
        )
        assertTrue(chooserCalled.get())
        assertNull(chooserValue.get())

        val popupMessage = Message.obtain()
        try {
            assertFalse(onMain { chrome.onCreateWindow(webView, false, true, popupMessage) })
        } finally {
            popupMessage.recycle()
        }
        val permissionRequest = RecordingPermissionRequest(Uri.parse(runtime.origin))
        onMain { chrome.onPermissionRequest(permissionRequest) }
        assertTrue(permissionRequest.denied.get())
        assertFalse(permissionRequest.granted.get())

        val geolocationCalled = AtomicBoolean(false)
        val geolocationAllowed = AtomicBoolean(true)
        val geolocationRetained = AtomicBoolean(true)
        onMain {
            chrome.onGeolocationPermissionsShowPrompt(
                runtime.origin,
                GeolocationPermissions.Callback { _, allow, retain ->
                    geolocationAllowed.set(allow)
                    geolocationRetained.set(retain)
                    geolocationCalled.set(true)
                },
            )
        }
        assertTrue(geolocationCalled.get())
        assertFalse(geolocationAllowed.get())
        assertFalse(geolocationRetained.get())

        if (expectedMode == RuntimeIsolationMode.ORIGIN_ONLY_STATELESS) {
            exerciseStatelessRuntimePersistenceBoundary(scenario, runtime, webView)
            exerciseVersionTransitionPermitHandoff(scenario)
            return
        }

        assertEquals(quote("present"), evaluateJavaScript(webView, "localStorage.setItem('cleanup_marker', 'present'); localStorage.getItem('cleanup_marker')"))
        val dedicatedCookieManager = onMain {
            requireNotNull(ProfileStore.getInstance().getProfile(runtime.profileName)).cookieManager
        }
        val cookieWritten = CompletableFuture<Boolean>()
        onMain {
            dedicatedCookieManager.setAcceptCookie(true)
            dedicatedCookieManager.setCookie(runtime.origin, "cleanup_marker=present; SameSite=Strict") {
                cookieWritten.complete(it)
            }
        }
        assertTrue(cookieWritten.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(onMain { dedicatedCookieManager.getCookie(runtime.origin).contains("cleanup_marker=present") })
        onMain { dedicatedCookieManager.setAcceptCookie(false) }
        assertEquals(RuntimeDataCleanupResult.InUse, runCleanup())
        withActivity(scenario) { it.disposeActiveWebView() }
        assertEquals(RuntimeDataCleanupResult.Cleared, runCleanup())
        assertTrue(onMain { dedicatedCookieManager.getCookie(runtime.origin).isNullOrEmpty() })

        val reloaded = CountDownLatch(1)
        val recreated = withActivity(scenario) { activity ->
            activity.attach(
                runtime,
                acquirePermit(),
                RuntimeWebViewCallbacks(
                    onMainEntryLoaded = reloaded::countDown,
                    onMainEntryFailed = { error("Recreated runtime failed: $it") },
                    onRendererGone = { error("Recreated runtime renderer exited") },
                ),
            )
        }
        assertTrue("The cleared runtime could not be recreated", reloaded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals("null", evaluateJavaScript(recreated, "localStorage.getItem('cleanup_marker')"))
        exerciseVersionTransitionPermitHandoff(scenario)
    }

    private fun exerciseRendererLoss(
        scenario: ActivityScenario<RuntimeWebViewTestActivity>,
        runtime: PreparedToolRuntime,
    ) {
        val loadedCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val rendererGone = CountDownLatch(1)
        val rendererGoneCount = AtomicInteger(0)
        val webView = withActivity(scenario) { activity ->
            activity.attach(
                runtime,
                acquirePermit(),
                RuntimeWebViewCallbacks(
                    onMainEntryLoaded = { loadedCount.incrementAndGet() },
                    onMainEntryFailed = { failedCount.incrementAndGet() },
                    onRendererGone = {
                        rendererGoneCount.incrementAndGet()
                        rendererGone.countDown()
                    },
                ),
            )
        }
        val canTerminateRenderer = onMain {
            WebViewFeature.isFeatureSupported(WebViewFeature.GET_WEB_VIEW_RENDERER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_TERMINATE)
        }

        if (canTerminateRenderer) {
            val renderProcess = awaitRenderProcess(webView)
            assertNotNull("A supported WebView must expose its live renderer process", renderProcess)
            val terminated = (0 until 50).any {
                val accepted = onMain { renderProcess!!.terminate() }
                if (!accepted) Thread.sleep(100)
                accepted
            }
            assertTrue("Renderer termination was supported but never became available", terminated)
            assertTrue(
                "onRenderProcessGone was not delivered after renderer termination",
                rendererGone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            withActivity(scenario) { it.detachDestroyedWebView() }
            assertEquals(0, withActivity(scenario) { it.contentChildCount() })
            assertEquals(1, rendererGoneCount.get())
            assertEquals("A terminated entry must not report a late success", 0, loadedCount.get())
            assertEquals(0, failedCount.get())
        } else {
            assertFalse(
                "The unsupported branch must represent the explicit renderer-termination feature boundary",
                WebViewFeature.isFeatureSupported(WebViewFeature.GET_WEB_VIEW_RENDERER) &&
                    WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_TERMINATE),
            )
            withActivity(scenario) { it.disposeActiveWebView() }
            assertFalse(rendererGone.await(250, TimeUnit.MILLISECONDS))
            assertEquals(0, rendererGoneCount.get())
            assertEquals(0, loadedCount.get())
            assertEquals(0, failedCount.get())
        }
    }

    private fun exerciseIsolationModeTransitionContracts(filesRoot: Path) {
        listOf(
            "deleted" to RuntimeProfileManager.PhysicalDeleteResult.Deleted,
            "absent" to RuntimeProfileManager.PhysicalDeleteResult.Absent,
        ).forEach { (caseName, deleteResult) ->
            val root = filesRoot.resolve("mode-transition-$caseName")
            deleteTree(root)
            seedDedicatedModeRecord(root)
            val fallback = RuntimeProfileManager(root.toFile(), fallbackCapabilities(multiProfile = true)) { deleteResult }
            val permit = acquirePermit(fallback)
            assertEquals(RuntimeIsolationMode.ORIGIN_ONLY_STATELESS, permit.isolationMode)
            onMain { permit.close() }
            assertTrue(readModeRecord(root).contains("mode=ORIGIN_ONLY_STATELESS"))
            deleteTree(root)
        }

        val loadedRoot = filesRoot.resolve("mode-transition-loaded")
        deleteTree(loadedRoot)
        seedDedicatedModeRecord(loadedRoot)
        val dedicatedRecord = readModeRecord(loadedRoot)
        val loaded = RuntimeProfileManager(loadedRoot.toFile(), fallbackCapabilities(multiProfile = true)) {
            RuntimeProfileManager.PhysicalDeleteResult.Loaded()
        }
        assertEquals(RuntimeDataCleanupResult.ProviderUnsupported, acquirePermitFailure(loaded))
        assertEquals(dedicatedRecord, readModeRecord(loadedRoot))
        deleteTree(loadedRoot)

        val unprovableRoot = filesRoot.resolve("mode-transition-unprovable")
        deleteTree(unprovableRoot)
        seedDedicatedModeRecord(unprovableRoot)
        val unprovableRecord = readModeRecord(unprovableRoot)
        val deleteCalls = AtomicInteger(0)
        val unprovable = RuntimeProfileManager(unprovableRoot.toFile(), fallbackCapabilities(multiProfile = false)) {
            deleteCalls.incrementAndGet()
            RuntimeProfileManager.PhysicalDeleteResult.Absent
        }
        assertEquals(RuntimeDataCleanupResult.ProviderUnsupported, acquirePermitFailure(unprovable))
        assertEquals(0, deleteCalls.get())
        assertEquals(unprovableRecord, readModeRecord(unprovableRoot))
        deleteTree(unprovableRoot)
    }

    private fun exerciseStatelessCapabilityRejectionContracts(filesRoot: Path) {
        val partialWorkerRoot = filesRoot.resolve("stateless-partial-worker")
        deleteTree(partialWorkerRoot)
        val partialWorker = RuntimeProfileManager(
            partialWorkerRoot.toFile(),
            fallbackCapabilities(
                multiProfile = true,
                serviceWorkerBasicUsage = true,
                serviceWorkerShouldInterceptRequest = false,
            ),
        ) { RuntimeProfileManager.PhysicalDeleteResult.Absent }
        assertEquals(RuntimeDataCleanupResult.ProviderUnsupported, acquirePermitFailure(partialWorker))
        assertFalse(Files.exists(partialWorkerRoot.resolve("runtime-isolation-mode")))
        deleteTree(partialWorkerRoot)

        val missingDocumentStartRoot = filesRoot.resolve("stateless-missing-document-start")
        deleteTree(missingDocumentStartRoot)
        val missingDocumentStart = RuntimeProfileManager(
            missingDocumentStartRoot.toFile(),
            fallbackCapabilities(multiProfile = true, documentStartScript = false),
        ) { RuntimeProfileManager.PhysicalDeleteResult.Absent }
        assertEquals(RuntimeDataCleanupResult.ProviderUnsupported, acquirePermitFailure(missingDocumentStart))
        assertFalse(Files.exists(missingDocumentStartRoot.resolve("runtime-isolation-mode")))
        deleteTree(missingDocumentStartRoot)
    }

    private fun seedDedicatedModeRecord(root: Path) {
        val dedicated = RuntimeProfileManager(root.toFile(), dedicatedCapabilities()) {
            RuntimeProfileManager.PhysicalDeleteResult.Absent
        }
        val permit = acquirePermit(dedicated)
        assertEquals(RuntimeIsolationMode.DEDICATED_PROFILE, permit.isolationMode)
        onMain { permit.close() }
        assertTrue(readModeRecord(root).contains("mode=DEDICATED_PROFILE"))
    }

    private fun dedicatedCapabilities() = RuntimeProviderCapabilities(
        multiProfile = true,
        deleteBrowsingData = true,
        documentStartScript = true,
        serviceWorkerBasicUsage = true,
        serviceWorkerShouldInterceptRequest = true,
    )

    private fun fallbackCapabilities(
        multiProfile: Boolean,
        documentStartScript: Boolean = true,
        serviceWorkerBasicUsage: Boolean = true,
        serviceWorkerShouldInterceptRequest: Boolean = true,
    ) = RuntimeProviderCapabilities(
        multiProfile = multiProfile,
        deleteBrowsingData = false,
        documentStartScript = documentStartScript,
        serviceWorkerBasicUsage = serviceWorkerBasicUsage,
        serviceWorkerShouldInterceptRequest = serviceWorkerShouldInterceptRequest,
    )

    private fun readModeRecord(root: Path): String = Files.readAllBytes(
        root.resolve("runtime-isolation-mode/${RuntimeIdentity.profileName(TOOL_ID)}.mode"),
    ).toString(Charsets.UTF_8)

    private fun exerciseProfileManagerFailureAndRecoveryBranches(
        filesRoot: Path,
        expectedMode: RuntimeIsolationMode,
        multiProfileSupported: Boolean,
    ) {
        val emptyRecoveryRoot = filesRoot.resolve("empty-runtime-recovery")
        deleteTree(emptyRecoveryRoot)
        assertEquals(
            RuntimeDataCleanupResult.AlreadyAbsent,
            runBlocking { RuntimeProfileManager(emptyRecoveryRoot.toFile()).reapMarkedOrphanProfiles(emptySet()) },
        )

        val markerRoot = filesRoot.resolve("runtime-profile-cleanup")
        Files.createDirectories(markerRoot)
        val profileName = RuntimeIdentity.profileName(TOOL_ID)
        val markerPath = markerRoot.resolve("$profileName.pending")

        writeText(markerPath, "not-a-valid-marker")
        assertEquals(RuntimeDataCleanupResult.Failed, acquirePermitFailure())

        writeText(markerPath, encodedMarker(ORPHAN_TOOL_ID))
        assertEquals(RuntimeDataCleanupResult.Failed, acquirePermitFailure())
        Files.delete(markerPath)

        val modeRoot = filesRoot.resolve("runtime-isolation-mode")
        Files.createDirectories(modeRoot)
        val modePath = modeRoot.resolve("$profileName.mode")
        writeText(modePath, "not-a-valid-mode-record")
        assertEquals(RuntimeDataCleanupResult.Failed, acquirePermitFailure())
        writeText(modePath, encodedModeRecord(ORPHAN_TOOL_ID, expectedMode))
        assertEquals(RuntimeDataCleanupResult.Failed, acquirePermitFailure())
        writeText(modePath, encodedModeRecord(TOOL_ID, expectedMode))

        val orphanProfileName = RuntimeIdentity.profileName(ORPHAN_TOOL_ID)
        val orphanMarker = markerRoot.resolve("$orphanProfileName.pending")
        writeText(orphanMarker, encodedMarker(ORPHAN_TOOL_ID))
        val orphanReap = runBlocking { profileManager.reapMarkedOrphanProfiles(setOf(TOOL_ID)) }
        if (multiProfileSupported) {
            assertEquals(RuntimeDataCleanupResult.Cleared, orphanReap)
            assertFalse(Files.exists(orphanMarker))
        } else {
            assertEquals(RuntimeDataCleanupResult.RecoveryDeferred, orphanReap)
            assertTrue(Files.exists(orphanMarker))
            Files.delete(orphanMarker)
        }

        val leasedRecoveryRoot = filesRoot.resolve("orphan-reaper-lease")
        deleteTree(leasedRecoveryRoot)
        val leasedMarkerRoot = leasedRecoveryRoot.resolve("runtime-profile-cleanup")
        val leasedModeRoot = leasedRecoveryRoot.resolve("runtime-isolation-mode")
        Files.createDirectories(leasedMarkerRoot)
        Files.createDirectories(leasedModeRoot)
        val leasedOrphanProfileName = RuntimeIdentity.profileName(ORPHAN_TOOL_ID)
        writeText(leasedMarkerRoot.resolve("$leasedOrphanProfileName.pending"), encodedMarker(ORPHAN_TOOL_ID))
        writeText(
            leasedModeRoot.resolve("$leasedOrphanProfileName.mode"),
            encodedModeRecord(ORPHAN_TOOL_ID, RuntimeIsolationMode.DEDICATED_PROFILE),
        )
        val reservationDuringPhysicalDeletion = AtomicReference<ManagedRuntimeCreationPermit?>()
        val leasedRecoveryManager = RuntimeProfileManager(leasedRecoveryRoot.toFile(), dedicatedCapabilities()) {
            reservationDuringPhysicalDeletion.set(RuntimeWebViewLifecycle.reserve(ORPHAN_TOOL_ID))
            RuntimeProfileManager.PhysicalDeleteResult.Deleted
        }
        assertEquals(
            RuntimeDataCleanupResult.Cleared,
            runBlocking { leasedRecoveryManager.reapMarkedOrphanProfiles(emptySet()) },
        )
        onMain { reservationDuringPhysicalDeletion.get()?.close() }
        assertNull(
            "An orphan's runtime reservation must be rejected while its physical profile deletion is in progress",
            reservationDuringPhysicalDeletion.get(),
        )
        assertFalse(Files.exists(leasedMarkerRoot.resolve("$leasedOrphanProfileName.pending")))
        assertFalse(Files.exists(leasedModeRoot.resolve("$leasedOrphanProfileName.mode")))
        val permitAfterOrphanCleanup = acquirePermit(leasedRecoveryManager, ORPHAN_TOOL_ID)
        onMain { permitAfterOrphanCleanup.close() }
        deleteTree(leasedRecoveryRoot)

        val actionCalled = AtomicBoolean(false)
        runBlocking {
            val cleanupProofWritten = CompletableDeferred<Unit>()
            val holdBeforeAction = CompletableDeferred<Unit>()
            val cancellationManager = RuntimeProfileManager(filesRoot.toFile()) {
                cleanupProofWritten.complete(Unit)
                holdBeforeAction.await()
            }
            val cleanupJob = launch(Dispatchers.Default) {
                cancellationManager.clearThenRun(TOOL_ID) { actionCalled.set(true) }
            }
            cleanupProofWritten.await()
            cleanupJob.cancelAndJoin()
        }
        assertFalse("Cancellation after cleanup proof must prevent package deletion", actionCalled.get())
        assertEquals(expectedMode == RuntimeIsolationMode.DEDICATED_PROFILE, Files.exists(markerPath))
        val consumedPermit = acquirePermit()
        assertEquals(expectedMode, consumedPermit.isolationMode)
        onMain { consumedPermit.close() }
        assertFalse(Files.exists(markerPath))
    }

    private fun exerciseStatelessRuntimePersistenceBoundary(
        scenario: ActivityScenario<RuntimeWebViewTestActivity>,
        runtime: PreparedToolRuntime,
        webView: WebView,
    ) {
        assertEquals(quote(""), evaluateJavaScript(webView, "document.cookie='runtime_cookie=present'; document.cookie"))
        assertStatelessPersistentApisDenied(webView)
        assertEquals(RuntimeDataCleanupResult.InUse, runCleanup())
        withActivity(scenario) { it.disposeActiveWebView() }
        assertEquals(RuntimeDataCleanupResult.AlreadyAbsent, runCleanup())

        val reloaded = CountDownLatch(1)
        val permit = acquirePermit()
        assertEquals(RuntimeIsolationMode.ORIGIN_ONLY_STATELESS, permit.isolationMode)
        val recreated = withActivity(scenario) { activity ->
            activity.attach(
                runtime,
                permit,
                RuntimeWebViewCallbacks(
                    onMainEntryLoaded = reloaded::countDown,
                    onMainEntryFailed = { error("Recreated stateless runtime failed: $it") },
                    onRendererGone = { error("Recreated stateless runtime renderer exited") },
                ),
            )
        }
        assertTrue("The stateless runtime could not be recreated", reloaded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(quote(""), evaluateJavaScript(recreated, "document.cookie"))
        assertStatelessPersistentApisDenied(recreated)
    }

    private fun assertStatelessPersistentApisDenied(webView: WebView) {
        assertEquals("true", evaluateJavaScript(webView, "globalThis.__toolboxStatelessHardened === true"))
        listOf("indexedDB", "localStorage", "sessionStorage").forEach { name ->
            assertEquals(quote("undefined"), evaluateJavaScript(webView, "typeof globalThis['$name']"))
        }
        listOf(
            "__cacheState",
            "__opfsState",
            "__storageBucketsState",
            "__webSqlState",
            "__requestFileSystemState",
            "__resolveFileSystemState",
            "__serviceWorkerState",
        ).forEach { state ->
            assertTrue("Persistent API was not denied: $state", awaitJavaScriptValue(webView, "window.$state", quote("blocked")))
        }
        listOf(
            "caches",
            "indexedDB",
            "localStorage",
            "sessionStorage",
            "storageBuckets",
            "openDatabase",
            "requestFileSystem",
            "webkitRequestFileSystem",
            "resolveLocalFileSystemURL",
            "webkitResolveLocalFileSystemURL",
        ).forEach { name -> assertPrototypeDescriptorsHardened(webView, "globalThis", name) }
        listOf("serviceWorker", "storage", "storageBuckets").forEach { name ->
            assertPrototypeDescriptorsHardened(webView, "navigator", name)
        }
    }

    private fun assertPrototypeDescriptorsHardened(webView: WebView, target: String, name: String) {
        val script = """
            (() => {
              const target = $target;
              const direct = target['$name'];
              let cursor = target;
              while (cursor && cursor !== Object.prototype) {
                const descriptor = Object.getOwnPropertyDescriptor(cursor, '$name');
                if (descriptor && (descriptor.get !== undefined || descriptor.set !== undefined || descriptor.value !== direct)) {
                  return false;
                }
                cursor = Object.getPrototypeOf(cursor);
              }
              return true;
            })()
        """.trimIndent()
        assertEquals("Prototype descriptor bypass remained for $target.$name", "true", evaluateJavaScript(webView, script))
    }

    private fun exercisePreexistingCookieRejection(
        scenario: ActivityScenario<RuntimeWebViewTestActivity>,
        runtime: PreparedToolRuntime,
    ) {
        val cookieManager = onMain { CookieManager.getInstance() }
        setExactOriginCookie(cookieManager, runtime.origin, "preexisting_runtime_cookie=present; Path=/; Secure; SameSite=Strict")
        assertTrue(onMain { cookieManager.getCookie(runtime.origin).orEmpty().contains("preexisting_runtime_cookie=present") })
        val permit = acquirePermit()
        assertEquals(RuntimeIsolationMode.ORIGIN_ONLY_STATELESS, permit.isolationMode)
        var primaryFailure: Throwable? = null
        try {
            val creation = withActivity(scenario) { activity ->
                HardenedRuntimeWebView.create(
                    context = activity,
                    runtime = runtime,
                    creationPermit = permit,
                    callbacks = RuntimeWebViewCallbacks(
                        onMainEntryLoaded = { error("Cookie-contaminated origin unexpectedly loaded") },
                        onMainEntryFailed = { error("Cookie-contaminated origin reached page loading") },
                        onRendererGone = { error("Cookie-contaminated origin created a renderer") },
                    ),
                    bridgeProvider = instrumentationBridgeProvider,
                )
            }
            assertTrue("A default-profile cookie must fail typed WebView creation", creation is RuntimeWebViewCreationResult.Failed)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailure = runCatching {
                setExactOriginCookie(
                    cookieManager,
                    runtime.origin,
                    "preexisting_runtime_cookie=; Max-Age=0; Path=/; Secure; SameSite=Strict",
                )
            }.exceptionOrNull()
            if (primaryFailure == null && cleanupFailure != null) throw cleanupFailure
        }
        assertFalse(onMain { cookieManager.getCookie(runtime.origin).orEmpty().contains("preexisting_runtime_cookie=") })
        onMain { cookieManager.setAcceptCookie(false) }
    }

    private fun setExactOriginCookie(cookieManager: CookieManager, origin: String, cookie: String) {
        val completion = CompletableFuture<Boolean>()
        onMain {
            cookieManager.setAcceptCookie(true)
            cookieManager.setCookie(origin, cookie) { accepted -> completion.complete(accepted) }
        }
        assertTrue("The exact-origin test cookie operation was rejected", completion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        onMain { cookieManager.flush() }
    }

    private fun exerciseVersionTransitionPermitHandoff(
        scenario: ActivityScenario<RuntimeWebViewTestActivity>,
    ) {
        val waitingBoundaryReached = CompletableDeferred<Unit>()
        val handoffManager = RuntimeProfileManager(
            privateFilesDirectory = withActivity(scenario) { it.filesDir },
            afterCleanupProofWritten = {},
            onRuntimeReleaseWait = { waitingBoundaryReached.complete(Unit) },
        )
        val waitingResult = CompletableFuture<RuntimeCreationPermitResult>()
        val waitingJob = CoroutineScope(Dispatchers.Default).launch {
            waitingResult.complete(
                handoffManager.acquireRuntimePermit(
                    toolId = TOOL_ID,
                    awaitExistingRuntimeRelease = true,
                ),
            )
        }
        runBlocking {
            withTimeout(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS)) { waitingBoundaryReached.await() }
        }
        assertFalse("Next-version permit must wait for old WebView teardown", waitingResult.isDone)
        withActivity(scenario) { it.disposeActiveWebView() }
        val permitResult = waitingResult.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val nextPermit = (permitResult as RuntimeCreationPermitResult.Ready).permit
        onMain { nextPermit.close() }
        runBlocking { waitingJob.join() }
    }

    private fun writeRuntimeBundle(bundleRoot: Path) {
        Files.createDirectories(bundleRoot)
        writeText(
            bundleRoot.resolve("manifest.json"),
            """
                {
                  "schemaVersion": 1,
                  "id": "$TOOL_ID",
                  "name": "Runtime Instrumentation",
                  "version": "1.0.0",
                  "versionCode": $VERSION_CODE,
                  "entry": "index.html",
                  "apiVersion": "1.0",
                  "minHostVersion": "0.1.0",
                  "permissions": [],
                  "securityProfile": "strict"
                }
            """.trimIndent(),
        )
        writeText(
            bundleRoot.resolve("index.html"),
            "<!doctype html><html><head><meta charset=\"utf-8\"><script src=\"app.js\"></script></head><body>runtime</body></html>",
        )
        writeText(
            bundleRoot.resolve("app.js"),
            """
                window.__pageExecuted = true;
                window.__toolBoxPresent = typeof window.ToolBox === "object";
                window.__remoteFetchState = "pending";
                window.__cacheState = "pending";
                window.__opfsState = "pending";
                window.__storageBucketsState = "pending";
                window.__webSqlState = "pending";
                window.__requestFileSystemState = "pending";
                window.__resolveFileSystemState = "pending";
                window.__serviceWorkerState = "pending";
                fetch("https://example.com/toolbox-runtime-must-not-fetch")
                  .then(() => { window.__remoteFetchState = "unexpected-success"; })
                  .catch(() => { window.__remoteFetchState = "blocked"; });
                Promise.resolve()
                  .then(() => caches.open("toolbox-runtime-must-not-persist"))
                  .then(() => { window.__cacheState = "unexpected-success"; })
                  .catch(() => { window.__cacheState = "blocked"; });
                Promise.resolve()
                  .then(() => navigator.storage.getDirectory())
                  .then(() => { window.__opfsState = "unexpected-success"; })
                  .catch(() => { window.__opfsState = "blocked"; });
                Promise.resolve()
                  .then(() => navigator.storageBuckets.open("toolbox-runtime-must-not-persist"))
                  .then(() => { window.__storageBucketsState = "unexpected-success"; })
                  .catch(() => { window.__storageBucketsState = "blocked"; });
                try {
                  openDatabase("toolbox-runtime-must-not-persist", "1", "blocked", 1024);
                  window.__webSqlState = "unexpected-success";
                } catch (_) {
                  window.__webSqlState = "blocked";
                }
                try {
                  requestFileSystem(0, 1024, () => { window.__requestFileSystemState = "unexpected-success"; });
                } catch (_) {
                  window.__requestFileSystemState = "blocked";
                }
                try {
                  resolveLocalFileSystemURL("filesystem:blocked", () => { window.__resolveFileSystemState = "unexpected-success"; });
                } catch (_) {
                  window.__resolveFileSystemState = "blocked";
                }
                if (!navigator.serviceWorker) {
                  window.__serviceWorkerState = "blocked";
                } else {
                  navigator.serviceWorker.register("sw.js")
                    .then(() => { window.__serviceWorkerState = "unexpected-success"; })
                    .catch(() => { window.__serviceWorkerState = "blocked"; });
                }
            """.trimIndent(),
        )
        writeText(bundleRoot.resolve("sw.js"), "self.addEventListener('fetch', () => {});")
        writeText(
            bundleRoot.resolve("hang.html"),
            "<!doctype html><html><head><meta charset=\"utf-8\"><script src=\"hang.js\"></script></head><body>hang</body></html>",
        )
        writeText(bundleRoot.resolve("hang.js"), "while (true) {}")
    }

    private fun writeText(path: Path, value: String) {
        Files.write(path, value.toByteArray(Charsets.UTF_8))
    }

    private fun preparedRuntime(filesRoot: Path, bundleRoot: Path, entry: String) = PreparedToolRuntime(
        toolId = TOOL_ID,
        toolName = "Runtime Instrumentation",
        versionCode = VERSION_CODE,
        privateFilesRoot = filesRoot,
        bundleRoot = bundleRoot,
        entry = entry,
        origin = RuntimeIdentity.origin(TOOL_ID),
        profileName = RuntimeIdentity.profileName(TOOL_ID),
        securityProfile = SecurityProfile.STRICT,
        installedManifest = InstalledManifest(
            id = TOOL_ID,
            name = "Runtime Instrumentation",
            versionCode = VERSION_CODE,
            entry = entry,
            securityProfile = SecurityProfile.STRICT,
            permissions = emptySet(),
            permissionDeclarations = emptyList(),
            network = null,
            maxBridgePayloadBytes = RuntimeBridgeConfiguration.DEFAULT_MAX_BRIDGE_PAYLOAD_BYTES,
        ),
    )

    private fun evaluateJavaScript(webView: WebView, script: String): String {
        val result = CompletableFuture<String>()
        onMain { webView.evaluateJavascript(script) { result.complete(it) } }
        return result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun awaitJavaScriptValue(webView: WebView, script: String, expected: String): Boolean {
        repeat(50) {
            if (evaluateJavaScript(webView, script) == expected) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun awaitRenderProcess(webView: WebView): WebViewRenderProcess? {
        repeat(50) {
            val process = onMain { WebViewCompat.getWebViewRenderProcess(webView) }
            if (process != null) return process
            Thread.sleep(100)
        }
        return null
    }

    private fun <T> withActivity(
        scenario: ActivityScenario<RuntimeWebViewTestActivity>,
        action: (RuntimeWebViewTestActivity) -> T,
    ): T {
        val result = CompletableFuture<T>()
        scenario.onActivity { activity ->
            runCatching { action(activity) }
                .onSuccess(result::complete)
                .onFailure(result::completeExceptionally)
        }
        return result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun <T> onMain(action: () -> T): T {
        val result = CompletableFuture<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runCatching(action)
                .onSuccess(result::complete)
                .onFailure(result::completeExceptionally)
        }
        return result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun runCleanup(): RuntimeDataCleanupResult = runBlocking {
        when (val execution = profileManager.clearThenRun(TOOL_ID) { Unit }) {
            is RuntimeDataCleanupExecution.Completed -> execution.cleanupResult
            is RuntimeDataCleanupExecution.Rejected -> execution.reason
        }
    }

    private fun acquirePermit(): RuntimeCreationPermit = runBlocking {
        when (val result = profileManager.acquireRuntimePermit(TOOL_ID, awaitExistingRuntimeRelease = false)) {
            is RuntimeCreationPermitResult.Ready -> result.permit
            is RuntimeCreationPermitResult.Rejected -> error("Runtime permit rejected: ${result.reason}")
        }
    }

    private fun acquirePermit(
        manager: RuntimeProfileManager,
        toolId: String = TOOL_ID,
    ): RuntimeCreationPermit = runBlocking {
        when (val result = manager.acquireRuntimePermit(toolId, awaitExistingRuntimeRelease = false)) {
            is RuntimeCreationPermitResult.Ready -> result.permit
            is RuntimeCreationPermitResult.Rejected -> error("Runtime permit rejected: ${result.reason}")
        }
    }

    private fun acquirePermitFailure(): RuntimeDataCleanupResult = runBlocking {
        when (val result = profileManager.acquireRuntimePermit(TOOL_ID, awaitExistingRuntimeRelease = false)) {
            is RuntimeCreationPermitResult.Ready -> {
                onMain { result.permit.close() }
                error("Malformed marker unexpectedly produced a runtime permit")
            }
            is RuntimeCreationPermitResult.Rejected -> result.reason
        }
    }

    private fun acquirePermitFailure(manager: RuntimeProfileManager): RuntimeDataCleanupResult = runBlocking {
        when (val result = manager.acquireRuntimePermit(TOOL_ID, awaitExistingRuntimeRelease = false)) {
            is RuntimeCreationPermitResult.Ready -> {
                onMain { result.permit.close() }
                error("Unsafe capability transition unexpectedly produced a runtime permit")
            }
            is RuntimeCreationPermitResult.Rejected -> result.reason
        }
    }

    private fun encodedMarker(toolId: String): String = buildString {
        appendLine("version=1")
        appendLine("toolId=$toolId")
        appendLine("profile=${RuntimeIdentity.profileName(toolId)}")
        appendLine("status=CONTENT_CLEARED_PENDING_PROFILE_DELETE")
    }

    private fun encodedModeRecord(toolId: String, mode: RuntimeIsolationMode): String = buildString {
        appendLine("version=1")
        appendLine("toolId=$toolId")
        appendLine("profile=${RuntimeIdentity.profileName(toolId)}")
        appendLine("mode=${mode.name}")
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun quote(value: String) = "\"$value\""

    private fun reportCapabilities(capabilities: RuntimeProviderCapabilities) {
        val stream = buildString {
            append("ToolBox runtime WebView capabilities: ")
            append("MULTI_PROFILE=${capabilities.multiProfile}, ")
            append("DELETE_BROWSING_DATA=${capabilities.deleteBrowsingData}, ")
            append("DOCUMENT_START_SCRIPT=${capabilities.documentStartScript}, ")
            append("SERVICE_WORKER_BASIC_USAGE=${capabilities.serviceWorkerBasicUsage}, ")
            append("SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST=")
            append(capabilities.serviceWorkerShouldInterceptRequest)
            append(", MODE=${capabilities.preferredIsolationMode}\n")
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", stream) })
    }

    private class TestWebResourceRequest(url: String) : WebResourceRequest {
        private val requestUrl = Uri.parse(url)
        override fun getUrl(): Uri = requestUrl
        override fun isForMainFrame(): Boolean = true
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = true
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private class RecordingPermissionRequest(private val requestOrigin: Uri) : PermissionRequest() {
        val denied = AtomicBoolean(false)
        val granted = AtomicBoolean(false)
        override fun getOrigin(): Uri = requestOrigin
        override fun getResources(): Array<String> = arrayOf(RESOURCE_VIDEO_CAPTURE)
        override fun grant(resources: Array<out String>) {
            granted.set(true)
        }
        override fun deny() {
            denied.set(true)
        }
    }

    private object TestFileChooserParams : WebChromeClient.FileChooserParams() {
        override fun getMode(): Int = MODE_OPEN
        override fun getAcceptTypes(): Array<String> = arrayOf("text/plain")
        override fun isCaptureEnabled(): Boolean = false
        override fun getTitle(): CharSequence = "Denied by runtime"
        override fun getFilenameHint(): String? = null
        override fun createIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
    }

    private companion object {
        const val TOOL_ID = "io.toolbox.runtime.instrumentation"
        const val ORPHAN_TOOL_ID = "io.toolbox.runtime.orphan"
        const val VERSION_CODE = 7
        const val TIMEOUT_SECONDS = 10L
    }
}

class RuntimeWebViewTestActivity : Activity() {
    private lateinit var content: FrameLayout
    private var activeWebView: WebView? = null
    private var activeDestroyedByClient = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = FrameLayout(this)
        setContentView(content)
    }

    fun attach(
        runtime: PreparedToolRuntime,
        creationPermit: RuntimeCreationPermit,
        callbacks: RuntimeWebViewCallbacks,
        bridgeProvider: RuntimeBridgeProvider = instrumentationBridgeProvider,
    ): WebView {
        disposeActiveWebView()
        activeDestroyedByClient = false
        val guardedCallbacks = callbacks.copy(
            onRendererGone = {
                activeDestroyedByClient = true
                callbacks.onRendererGone()
            },
        )
        val creation = HardenedRuntimeWebView.create(
            this,
            runtime,
            creationPermit,
            guardedCallbacks,
            bridgeProvider,
        )
        val webView = when (creation) {
            is RuntimeWebViewCreationResult.Created -> creation.webView
            is RuntimeWebViewCreationResult.Failed -> error(creation.message)
        }
        return webView.also {
            activeWebView = webView
            content.addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    fun detachDestroyedWebView() {
        activeWebView?.let(content::removeView)
        activeWebView = null
        activeDestroyedByClient = false
    }

    fun disposeActiveWebView() {
        val webView = activeWebView ?: return
        content.removeView(webView)
        if (!activeDestroyedByClient) {
            RuntimeWebViewLifecycle.destroyAndUnregister(webView)
        }
        activeWebView = null
        activeDestroyedByClient = false
    }

    fun contentChildCount(): Int = content.childCount

    override fun onDestroy() {
        disposeActiveWebView()
        super.onDestroy()
    }
}
