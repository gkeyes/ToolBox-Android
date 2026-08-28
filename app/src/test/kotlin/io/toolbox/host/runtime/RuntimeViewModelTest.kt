package io.toolbox.host.runtime

import android.webkit.WebView
import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.BundleLocator
import io.toolbox.core.data.CatalogInstallAttempt
import io.toolbox.core.data.CoreDataRepositories
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.LaunchState
import io.toolbox.core.data.SecurityProfile
import io.toolbox.core.data.SignatureState
import io.toolbox.core.data.ToolMetadata
import io.toolbox.core.data.ToolVersion
import io.toolbox.core.data.ToolVersionIdentity
import io.toolbox.core.data.memory.InMemoryCoreData
import io.toolbox.tool.runtime.ToolRuntimePreparer
import io.toolbox.tool.runtime.RuntimeCreationPermit
import io.toolbox.tool.runtime.RuntimeCreationPermitResult
import io.toolbox.tool.runtime.RuntimePermitProvider
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeViewModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun pageLoadAndRendererLossRemainPendingUntilUserConfirmsReadyVersion() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var testedViewModel: RuntimeViewModel? = null
        try {
            val repositories = InMemoryCoreData.create()
            val filesRoot = temporaryFolder.newFolder("files")
            val awaitExistingRuntimeFlags = mutableListOf<Boolean>()
            installPendingRuntime(repositories, VERSION_CODE, "1.0.0")
            writeRuntimeBundle(filesRoot, VERSION_CODE, "1.0.0")
            val viewModel = RuntimeViewModel(
                toolId = TOOL_ID,
                catalog = repositories.catalog,
                lifecycle = repositories.lifecycle,
                preparer = ToolRuntimePreparer(filesRoot),
                runtimeProfileManager = RuntimePermitProvider { _, awaitExistingRuntimeRelease ->
                    awaitExistingRuntimeFlags += awaitExistingRuntimeRelease
                    RuntimeCreationPermitResult.Ready(NoOpRuntimeCreationPermit)
                },
            )
            testedViewModel = viewModel

            val firstReady = viewModel.state.filterIsInstance<RuntimeUiState.Ready>().first()
            assertEquals(false, awaitExistingRuntimeFlags.first())
            viewModel.mainEntryLoaded(firstReady.runtime)
            advanceUntilIdle()
            assertTrue((viewModel.state.value as RuntimeUiState.Ready).entryLoaded)
            assertEquals(LaunchState.PENDING, activeLaunchState(repositories))

            writeRuntimeBundle(filesRoot, NEXT_VERSION_CODE, "1.1.0")
            installPendingRuntime(repositories, NEXT_VERSION_CODE, "1.1.0")
            val switchedReady = viewModel.state.filterIsInstance<RuntimeUiState.Ready>()
                .first { it.runtime.versionCode == NEXT_VERSION_CODE }
            assertEquals(NEXT_VERSION_CODE, switchedReady.runtime.versionCode)
            assertTrue("Version switch must request teardown acknowledgement", awaitExistingRuntimeFlags.last())

            viewModel.rendererGone()
            advanceUntilIdle()
            assertTrue(viewModel.state.value is RuntimeUiState.Error)
            assertEquals(LaunchState.PENDING, activeLaunchState(repositories))

            viewModel.retry()
            val retriedReady = viewModel.state.filterIsInstance<RuntimeUiState.Ready>().first()
            assertTrue(awaitExistingRuntimeFlags.last())
            viewModel.mainEntryLoaded(retriedReady.runtime)
            viewModel.confirmReadyVersion()
            advanceUntilIdle()

            assertEquals(LaunchState.STABLE, activeLaunchState(repositories))
            viewModel.runtimeCreationFailed("provider setup failed")
            val creationFailure = viewModel.state.value as RuntimeUiState.Error
            assertEquals("RUNTIME_WEBVIEW_CREATION_FAILED", creationFailure.code)
            assertEquals("provider setup failed", creationFailure.message)
        } finally {
            testedViewModel?.viewModelScope?.cancel()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    private suspend fun installPendingRuntime(
        repositories: CoreDataRepositories,
        versionCode: Int,
        versionName: String,
    ) {
        val identity = ToolVersionIdentity(
            name = "Runtime fixture",
            signatureState = SignatureState.UNSIGNED,
            publisherKeyId = null,
            securityProfile = SecurityProfile.STRICT,
        )
        val result = repositories.lifecycle.commitInstall(
            CatalogInstallAttempt(
                metadata = ToolMetadata(
                    id = TOOL_ID,
                    name = identity.name,
                    signatureState = identity.signatureState,
                    publisherKeyId = identity.publisherKeyId,
                    securityProfile = identity.securityProfile,
                    installedAt = versionCode.toLong(),
                ),
                version = ToolVersion(
                    toolId = TOOL_ID,
                    versionCode = versionCode,
                    version = versionName,
                    bundleLocator = BundleLocator("miniapps/$TOOL_ID/versions/$versionCode/bundle"),
                    bundleBytes = 128L,
                    integrityHash = "0".repeat(64),
                    installedAt = versionCode.toLong(),
                    launchState = LaunchState.PENDING,
                    sourceSessionId = "runtime-fixture-$versionCode",
                    identity = identity,
                ),
                initialGrants = emptyList(),
            ),
        )
        assertTrue(result is DataResult.Success)
    }

    private fun writeRuntimeBundle(filesRoot: java.io.File, versionCode: Int, versionName: String) {
        val bundle = filesRoot.toPath().resolve("miniapps/$TOOL_ID/versions/$versionCode/bundle")
        Files.createDirectories(bundle)
        Files.writeString(
            bundle.resolve("manifest.json"),
            """
            {
              "schemaVersion": 1,
              "id": "$TOOL_ID",
              "name": "Runtime fixture",
              "version": "$versionName",
              "versionCode": $versionCode,
              "entry": "index.html",
              "apiVersion": "1.0",
              "minHostVersion": "0.1.0",
              "permissions": [],
              "securityProfile": "strict"
            }
            """.trimIndent(),
        )
        Files.writeString(bundle.resolve("index.html"), "<!doctype html><title>Runtime fixture</title>")
    }

    private suspend fun activeLaunchState(repositories: CoreDataRepositories): LaunchState {
        val activeVersionCode = requireNotNull(repositories.catalog.observeTool(TOOL_ID).first()?.activeVersionCode)
        return repositories.catalog.observeVersions(TOOL_ID).first()
            .single { it.versionCode == activeVersionCode }
            .launchState
    }

    private object NoOpRuntimeCreationPermit : RuntimeCreationPermit {
        override val isolationMode = io.toolbox.tool.runtime.RuntimeIsolationMode.ORIGIN_ONLY_STATELESS

        override fun attach(webView: WebView) = Unit
        override fun close() = Unit
    }

    private companion object {
        const val TOOL_ID = "io.toolbox.runtime.fixture"
        const val VERSION_CODE = 7
        const val NEXT_VERSION_CODE = 8
    }
}
