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
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<ImportViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun sameVersionWaitsForConfirmationAndInstallsOnlyAfterApproval() = runTest(dispatcher) {
        val confirmation = confirmation(HostImportConfirmationKind.SAME_VERSION, 2, 2)
        val operations = RecordingPackageOperations(
            initialResult = HostImportResult.ConfirmationRequired(confirmation),
            confirmedResult = HostImportResult.Installed(confirmation.toolId, confirmation.toolName),
        )
        val viewModel = createViewModel(operations)

        viewModel.importPackage(ByteInput())
        advanceUntilIdle()

        assertEquals(confirmation, viewModel.state.value.confirmation)
        assertNull(viewModel.state.value.message)
        assertTrue(operations.confirmations.isEmpty())

        viewModel.confirmVersionReplacement()
        advanceUntilIdle()

        assertEquals(listOf(confirmation.id), operations.confirmations)
        assertNull(viewModel.state.value.confirmation)
        assertEquals("示例工具 已安装", viewModel.state.value.message)
        assertTrue(viewModel.state.value.succeeded)
    }

    @Test
    fun cancellingDowngradeDiscardsPendingInstallWithoutReportingSuccess() = runTest(dispatcher) {
        val confirmation = confirmation(HostImportConfirmationKind.DOWNGRADE, 3, 1)
        val operations = RecordingPackageOperations(
            initialResult = HostImportResult.ConfirmationRequired(confirmation),
            confirmedResult = HostImportResult.Failed("UNEXPECTED", "不应执行确认"),
        )
        val viewModel = createViewModel(operations)

        viewModel.importPackage(ByteInput())
        advanceUntilIdle()
        viewModel.cancelVersionReplacement()
        advanceUntilIdle()

        assertEquals(listOf(confirmation.id), operations.cancellations)
        assertTrue(operations.confirmations.isEmpty())
        assertNull(viewModel.state.value.confirmation)
        assertNull(viewModel.state.value.message)
        assertFalse(viewModel.state.value.succeeded)
    }

    private fun createViewModel(operations: HostPackageOperations) =
        ImportViewModel(operations, dispatcher).also(viewModels::add)

    private fun confirmation(
        kind: HostImportConfirmationKind,
        installedVersionCode: Int,
        incomingVersionCode: Int,
    ) = HostImportConfirmation(
        id = "confirmation-1",
        toolId = "io.toolbox.fixture",
        toolName = "示例工具",
        installedVersionName = "1.0.$installedVersionCode",
        installedVersionCode = installedVersionCode,
        incomingVersionName = "1.0.$incomingVersionCode",
        incomingVersionCode = incomingVersionCode,
        kind = kind,
    )
}

private class RecordingPackageOperations(
    private val initialResult: HostImportResult,
    private val confirmedResult: HostImportResult,
) : HostPackageOperations {
    val confirmations = mutableListOf<String>()
    val cancellations = mutableListOf<String>()

    override suspend fun importPackage(input: PackageInput): HostImportResult = initialResult

    override suspend fun confirmImport(confirmationId: String): HostImportResult {
        confirmations += confirmationId
        return confirmedResult
    }

    override suspend fun cancelImport(confirmationId: String): HostImportCancellationResult {
        cancellations += confirmationId
        return HostImportCancellationResult.Cancelled
    }

    override suspend fun installedManifest(toolId: String): HostInstalledManifestResult = error("not used")

    override suspend fun deleteTool(toolId: String): HostDeleteResult = error("not used")

    override suspend fun installBundledExamples(): HostExampleInstallResult = error("not used")
}

private class ByteInput : PackageInput {
    override val displayName = "fixture.tbx"
    override fun openStream() = ByteArrayInputStream(byteArrayOf())
}
