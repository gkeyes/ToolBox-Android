package io.toolbox.host.importflow

import io.toolbox.host.HostImportConfirmation
import io.toolbox.host.HostImportConfirmationKind
import io.toolbox.tool.packagekit.lifecycle.PackageImportPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageImportPickerStateTest {
    @Test
    fun idleStateCanOpenPickerDirectly() {
        assertTrue(ImportUiState().canRequestPackage(pickerBusy = false))
    }

    @Test
    fun pendingPickerOrSourceResolutionBlocksRepeatedRequests() {
        assertFalse(ImportUiState().canRequestPackage(pickerBusy = true))
        assertTrue(ImportUiState().canRequestPackage(pickerBusy = false))
    }

    @Test
    fun allActiveImportPhasesBlockAnotherPicker() {
        PackageImportPhase.entries.forEach { phase ->
            assertFalse(ImportUiState(working = true, importPhase = phase).canRequestPackage(false))
        }
    }

    @Test
    fun pendingVersionConfirmationCannotBeReplacedByAnotherSelection() {
        HostImportConfirmationKind.entries.forEach { kind ->
            val confirmation = HostImportConfirmation(
                id = "pending", toolId = "sample", toolName = "Sample",
                installedVersionName = "1.0", installedVersionCode = 1,
                incomingVersionName = "1.0", incomingVersionCode = 1,
                kind = kind,
            )
            assertFalse(ImportUiState(confirmation = confirmation).canRequestPackage(false))
        }
    }

    @Test
    fun finishedResultsAllowRetryOrAnotherImportWithoutDismissingFeedback() {
        ImportOutcome.entries.forEach { outcome ->
            val state = ImportUiState(message = "Previous result", outcome = outcome)
            assertTrue(state.canRequestPackage(pickerBusy = false))
            assertFalse(state.canRequestPackage(pickerBusy = true))
        }
    }
}
