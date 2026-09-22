package io.toolbox.host.importflow

import androidx.lifecycle.viewModelScope
import io.toolbox.host.HostDeleteResult
import io.toolbox.host.HostExampleInstallResult
import io.toolbox.host.HostImportCancellationResult
import io.toolbox.host.HostImportConfirmation
import io.toolbox.host.HostImportConfirmationKind
import io.toolbox.host.HostImportResult
import io.toolbox.host.HostInstalledManifestResult
import io.toolbox.host.HostPackageOperations
import io.toolbox.tool.packagekit.PackageInput
import io.toolbox.tool.packagekit.lifecycle.PackageImportControl
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImportLaunchIdentityTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = PackageOperations()
    private lateinit var viewModel: ImportViewModel
    private val input = object : PackageInput {
        override val displayName = "fixture.tbx"
        override fun openStream() = ByteArrayInputStream(byteArrayOf())
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = ImportViewModel(operations, dispatcher)
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun successfulImportKeepsTheActualInstalledIdentityUntilDismissed() = runTest(dispatcher) {
        viewModel.importPackage(input)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.succeeded)
        assertEquals("io.toolbox.imported", viewModel.state.value.installedToolId)
        viewModel.dismissMessage()
        assertNull(viewModel.state.value.installedToolId)
    }

    @Test
    fun pendingReplacementCannotOpenUntilApprovalSucceeds() = runTest(dispatcher) {
        operations.next = HostImportResult.ConfirmationRequired(HostImportConfirmation(
            id = "replace", toolId = "io.toolbox.imported", toolName = "导入工具",
            installedVersionName = "1", installedVersionCode = 1,
            incomingVersionName = "1", incomingVersionCode = 1,
            kind = HostImportConfirmationKind.SAME_VERSION,
        ))
        viewModel.importPackage(input)
        advanceUntilIdle()
        assertNull(viewModel.state.value.installedToolId)
        viewModel.confirmVersionReplacement()
        advanceUntilIdle()
        assertEquals("io.toolbox.imported", viewModel.state.value.installedToolId)
        assertNull(viewModel.state.value.confirmation)
    }

    @Test
    fun newImportFailureCancellationAndBatchNeverReuseAnOldOpenTarget() = runTest(dispatcher) {
        listOf(HostImportResult.Failed("IO", "读取失败"), HostImportResult.Cancelled).forEach { result ->
            operations.next = HostImportResult.Installed("io.toolbox.imported", "导入工具")
            viewModel.importPackage(input)
            advanceUntilIdle()
            assertEquals("io.toolbox.imported", viewModel.state.value.installedToolId)
            operations.next = result
            viewModel.importPackage(input)
            assertTrue(viewModel.state.value.working)
            assertNull(viewModel.state.value.installedToolId)
            advanceUntilIdle()
            assertNull(viewModel.state.value.installedToolId)
        }
        viewModel.installBundledExamples()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.succeeded)
        assertNull(viewModel.state.value.installedToolId)
    }

    private class PackageOperations : HostPackageOperations {
        var next: HostImportResult = HostImportResult.Installed("io.toolbox.imported", "导入工具")
        override suspend fun importPackage(input: PackageInput, control: PackageImportControl) = next
        override suspend fun confirmImport(confirmationId: String, control: PackageImportControl) =
            HostImportResult.Installed("io.toolbox.imported", "导入工具")
        override suspend fun cancelImport(confirmationId: String) = HostImportCancellationResult.Cancelled
        override suspend fun installedManifest(toolId: String): HostInstalledManifestResult = error("not used")
        override suspend fun deleteTool(toolId: String): HostDeleteResult = error("not used")
        override suspend fun installBundledExamples() = HostExampleInstallResult.Installed(4)
    }
}
