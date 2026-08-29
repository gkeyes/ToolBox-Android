package io.toolbox.host.catalog

import io.toolbox.core.data.BundleLocator
import io.toolbox.core.data.CatalogEntry
import io.toolbox.core.data.CatalogInstallAttempt
import io.toolbox.core.data.CatalogLifecycleRepository
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.CoreDataRepositories
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.LaunchState
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.SecurityProfile
import io.toolbox.core.data.SignatureState
import io.toolbox.core.data.ToolMetadata
import io.toolbox.core.data.ToolVersion
import io.toolbox.core.data.ToolVersionIdentity
import io.toolbox.core.data.memory.InMemoryCoreData
import io.toolbox.tool.packagekit.lifecycle.InstallLifecycleResult
import io.toolbox.tool.packagekit.lifecycle.LifecycleFailure
import io.toolbox.tool.packagekit.lifecycle.LifecycleFailureCode
import io.toolbox.tool.packagekit.lifecycle.RecoveryLifecycleResult
import io.toolbox.tool.packagekit.lifecycle.RollbackLifecycleResult
import io.toolbox.tool.packagekit.lifecycle.ToolPackageLifecycle
import io.toolbox.tool.packagekit.lifecycle.UninstallLifecycleResult
import io.toolbox.tool.runtime.RuntimeDataCleaner
import io.toolbox.tool.runtime.RuntimeDataCleanupExecution
import io.toolbox.tool.runtime.RuntimeDataCleanupResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelTest {
    @Test
    fun catalogListUsesSingleProjectionWithoutOpeningPerToolVersionFlows() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repositories = InMemoryCoreData.create()
            val catalog = CountingCatalogRepository(repositories.catalog)
            val viewModel = CatalogViewModel(
                catalog = catalog,
                organization = repositories.organization,
                packageLifecycle = RecoveringUninstallLifecycle(repositories.lifecycle),
                runtimeDataCleaner = RecordingRuntimeDataCleaner(),
            )

            install(repositories, TOOL_A, "仓位计算", 7, "1.2.0", 4_096L)
            install(repositories, TOOL_B, "JSON 工作区", 3, "1.0.3", 2_048L)
            runCurrent()

            assertEquals(2, viewModel.state.value.tools.size)
            assertEquals(2, viewModel.state.value.visibleTools.size)
            assertEquals(1, catalog.observeCatalogProjectionCalls)
            assertEquals(0, catalog.observeVersionsCalls)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun catalogFlowDrivesRealItemsFiltersOrganizationAndRecoverableUninstall() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repositories = InMemoryCoreData.create()
            val lifecycle = RecoveringUninstallLifecycle(repositories.lifecycle)
            val runtimeDataCleaner = RecordingRuntimeDataCleaner()
            val viewModel = CatalogViewModel(
                catalog = repositories.catalog,
                organization = repositories.organization,
                packageLifecycle = lifecycle,
                runtimeDataCleaner = runtimeDataCleaner,
                now = { 1_700_000_000_000L },
            )

            advanceUntilIdle()
            assertTrue(viewModel.state.value.isLoaded)
            assertTrue(viewModel.state.value.tools.isEmpty())

            install(repositories, TOOL_A, "仓位计算", 7, "1.2.0", 4_096L)
            install(repositories, TOOL_B, "JSON 工作区", 3, "1.0.3", 2_048L)
            assertEquals(DataResult.Success(Unit), repositories.organization.setCategory(TOOL_A, "finance"))
            assertEquals(DataResult.Success(Unit), repositories.organization.setCategory(TOOL_B, "developer"))
            assertEquals(DataResult.Success(Unit), repositories.organization.recordOpened(TOOL_B, 100L))

            advanceUntilIdle()
            assertEquals(2, viewModel.state.value.tools.size)
            assertEquals("1.2.0", viewModel.state.value.tools.single { it.toolId == TOOL_A }.activeVersionName)
            assertEquals(4_096L, viewModel.state.value.tools.single { it.toolId == TOOL_A }.bundleBytes)
            assertEquals(listOf(TOOL_B), viewModel.homeState.value.recentTools.map(CatalogTool::toolId))
            assertTrue(viewModel.homeState.value.pinnedTools.isEmpty())

            viewModel.dispatch(CatalogAction.SetQuery("json"))
            runCurrent()
            assertEquals("json", viewModel.query.value)
            assertEquals("json", viewModel.state.value.query)
            assertEquals(2, viewModel.state.value.visibleTools.size)
            advanceTimeBy(119L)
            runCurrent()
            assertEquals(2, viewModel.state.value.visibleTools.size)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf(TOOL_B), viewModel.state.value.visibleTools.map(CatalogTool::toolId))
            viewModel.dispatch(CatalogAction.SetQuery(""))
            runCurrent()
            assertEquals(2, viewModel.state.value.visibleTools.size)
            viewModel.dispatch(CatalogAction.SetCategoryFilter("finance"))
            runCurrent()
            assertEquals(listOf(TOOL_A), viewModel.state.value.visibleTools.map(CatalogTool::toolId))

            viewModel.dispatch(CatalogAction.TogglePinned(TOOL_A))
            advanceUntilIdle()
            assertEquals(0, viewModel.state.value.tools.single { it.toolId == TOOL_A }.pinnedOrder)

            viewModel.dispatch(CatalogAction.RequestUninstall(TOOL_A))
            assertEquals(UninstallConfirmation(TOOL_A, "仓位计算"), viewModel.state.value.uninstallConfirmation)
            viewModel.dispatch(CatalogAction.CancelUninstall)
            assertNull(viewModel.state.value.uninstallConfirmation)
            assertTrue(viewModel.state.value.tools.any { it.toolId == TOOL_A })
            assertEquals(0, runtimeDataCleaner.clearCalls)

            viewModel.dispatch(CatalogAction.RequestUninstall(TOOL_A))
            val cleanupCompletion = runtimeDataCleaner.suspendNextClear()
            viewModel.dispatch(CatalogAction.ConfirmUninstall)
            runCurrent()
            assertEquals(1, runtimeDataCleaner.clearCalls)
            assertEquals("Package deletion must wait for the profile callback", 0, lifecycle.uninstallCalls)
            cleanupCompletion.complete(RuntimeDataCleanupResult.Cleared)
            advanceUntilIdle()
            assertEquals(1, runtimeDataCleaner.clearCalls)
            val pending = viewModel.state.value.feedback as CatalogFeedback.RecoveryPending
            assertEquals(CatalogOperation.UNINSTALL, pending.operation)
            assertEquals(LifecycleFailureCode.RECOVERY_REQUIRED, pending.code)
            assertTrue(viewModel.state.value.tools.any { it.toolId == TOOL_A })

            viewModel.dispatch(CatalogAction.RecoverPendingMutation)
            advanceUntilIdle()
            assertFalse(viewModel.state.value.tools.any { it.toolId == TOOL_A })
            assertEquals(listOf(TOOL_B), viewModel.state.value.tools.map(CatalogTool::toolId))

            install(repositories, TOOL_A, "仓位计算", 8, "1.3.0", 4_096L)
            advanceUntilIdle()
            runtimeDataCleaner.result = RuntimeDataCleanupResult.InUse
            viewModel.dispatch(CatalogAction.RequestUninstall(TOOL_A))
            viewModel.dispatch(CatalogAction.ConfirmUninstall)
            advanceUntilIdle()
            assertEquals(2, runtimeDataCleaner.clearCalls)
            assertEquals(1, lifecycle.uninstallCalls)
            assertEquals(
                CatalogFeedback.Failure(
                    operation = CatalogOperation.UNINSTALL,
                    code = "RUNTIME_IN_USE",
                    message = "工具仍在运行，关闭运行页面后再卸载。",
                ),
                viewModel.state.value.feedback,
            )
            assertTrue(viewModel.state.value.tools.any { it.toolId == TOOL_A })

            runtimeDataCleaner.result = RuntimeDataCleanupResult.Failed
            viewModel.dispatch(CatalogAction.RequestUninstall(TOOL_A))
            viewModel.dispatch(CatalogAction.ConfirmUninstall)
            advanceUntilIdle()
            assertEquals(3, runtimeDataCleaner.clearCalls)
            assertEquals(1, lifecycle.uninstallCalls)
            assertEquals("RUNTIME_DATA_CLEAR_FAILED", (viewModel.state.value.feedback as CatalogFeedback.Failure).code)
            assertTrue(viewModel.state.value.tools.any { it.toolId == TOOL_A })

            runtimeDataCleaner.result = RuntimeDataCleanupResult.ProviderUnsupported
            viewModel.dispatch(CatalogAction.RequestUninstall(TOOL_A))
            viewModel.dispatch(CatalogAction.ConfirmUninstall)
            advanceUntilIdle()
            assertEquals(4, runtimeDataCleaner.clearCalls)
            assertEquals(1, lifecycle.uninstallCalls)
            assertEquals("RUNTIME_PROVIDER_UNSUPPORTED", (viewModel.state.value.feedback as CatalogFeedback.Failure).code)
            assertTrue(viewModel.state.value.tools.any { it.toolId == TOOL_A })
        } finally {
            Dispatchers.resetMain()
        }
    }

    private suspend fun install(
        repositories: CoreDataRepositories,
        toolId: String,
        name: String,
        versionCode: Int,
        version: String,
        bundleBytes: Long,
    ) {
        val identity = ToolVersionIdentity(
            name = name,
            signatureState = SignatureState.UNSIGNED,
            publisherKeyId = null,
            securityProfile = SecurityProfile.STRICT,
        )
        val result = repositories.lifecycle.commitInstall(
            CatalogInstallAttempt(
                metadata = ToolMetadata(
                    id = toolId,
                    name = name,
                    signatureState = identity.signatureState,
                    publisherKeyId = identity.publisherKeyId,
                    securityProfile = identity.securityProfile,
                    installedAt = versionCode.toLong(),
                ),
                version = ToolVersion(
                    toolId = toolId,
                    versionCode = versionCode,
                    version = version,
                    bundleLocator = BundleLocator("miniapps/$toolId/versions/$versionCode/bundle"),
                    bundleBytes = bundleBytes,
                    integrityHash = "sha256:$toolId:$versionCode",
                    installedAt = versionCode.toLong(),
                    launchState = LaunchState.PENDING,
                    sourceSessionId = "catalog-$toolId-$versionCode",
                    identity = identity,
                ),
                initialGrants = emptyList(),
            ),
        )
        assertTrue(result is DataResult.Success)
    }

    private class RecoveringUninstallLifecycle(
        private val catalog: CatalogLifecycleRepository,
    ) : ToolPackageLifecycle {
        private var pendingToolId: String? = null
        var uninstallCalls = 0

        override suspend fun install(
            inspectionSessionId: String,
            initialGrants: List<PermissionGrant>,
        ): InstallLifecycleResult = error("Not used by catalog state")

        override suspend fun rollback(toolId: String): RollbackLifecycleResult = error("Not used by catalog state")

        override suspend fun uninstall(toolId: String): UninstallLifecycleResult {
            uninstallCalls += 1
            pendingToolId = toolId
            return UninstallLifecycleResult.CommittedRecoveryPending(
                toolId = toolId,
                reason = LifecycleFailure(
                    LifecycleFailureCode.RECOVERY_REQUIRED,
                    "fixture requires recovery",
                ),
            )
        }

        override suspend fun recover(): RecoveryLifecycleResult {
            val toolId = pendingToolId ?: return RecoveryLifecycleResult.Recovered
            assertEquals(
                DataResult.Success(io.toolbox.core.data.DeleteToolCatalogOutcome.Deleted),
                catalog.deleteToolCatalog(toolId),
            )
            pendingToolId = null
            return RecoveryLifecycleResult.Recovered
        }
    }

    private class RecordingRuntimeDataCleaner : RuntimeDataCleaner {
        var clearCalls = 0
        var result = RuntimeDataCleanupResult.Cleared
        private var nextResult: CompletableDeferred<RuntimeDataCleanupResult>? = null

        fun suspendNextClear(): CompletableDeferred<RuntimeDataCleanupResult> =
            CompletableDeferred<RuntimeDataCleanupResult>().also { nextResult = it }

        override suspend fun <T> clearThenRun(
            toolId: String,
            action: suspend () -> T,
        ): RuntimeDataCleanupExecution<T> {
            clearCalls += 1
            return when (val cleanup = nextResult?.also { nextResult = null }?.await() ?: result) {
                RuntimeDataCleanupResult.Cleared,
                RuntimeDataCleanupResult.AlreadyAbsent,
                -> RuntimeDataCleanupExecution.Completed(cleanup, action())
                RuntimeDataCleanupResult.InUse,
                RuntimeDataCleanupResult.ProviderUnsupported,
                RuntimeDataCleanupResult.RecoveryDeferred,
                RuntimeDataCleanupResult.Failed,
                -> RuntimeDataCleanupExecution.Rejected(cleanup)
            }
        }
    }

    private class CountingCatalogRepository(
        private val delegate: CatalogRepository,
    ) : CatalogRepository by delegate {
        var observeCatalogProjectionCalls = 0
            private set
        var observeVersionsCalls = 0
            private set

        override fun observeCatalogProjection(): Flow<List<CatalogEntry>> {
            observeCatalogProjectionCalls += 1
            return delegate.observeCatalogProjection()
        }

        override fun observeVersions(toolId: String): Flow<List<ToolVersion>> {
            observeVersionsCalls += 1
            return delegate.observeVersions(toolId)
        }
    }

    private companion object {
        const val TOOL_A = "io.toolbox.catalog.position"
        const val TOOL_B = "io.toolbox.catalog.json"
    }
}
