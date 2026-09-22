package io.toolbox.host.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupPresentationTest {
    @Test
    fun skippedConflictsAreNotCountedAsOverwrites() {
        val tools = listOf(
            RestoreToolPlan("new", "New", "2", null),
            RestoreToolPlan("update", "Update", "2", "1"),
            RestoreToolPlan("skip", "Skip", "2", "1", "不兼容"),
        )
        assertEquals(RestorePlanCounts(2, 1, 1), restorePlanCounts(tools))
    }

    @Test
    fun emptyArchiveStillHasAValidSettingsOnlySummary() {
        assertEquals(RestorePlanCounts(0, 0, 0), restorePlanCounts(emptyList()))
    }

    @Test
    fun allSkippedToolsDoNotPromiseAnyInstallOrOverwrite() {
        assertEquals(RestorePlanCounts(0, 0, 2), restorePlanCounts(listOf(
            RestoreToolPlan("a", "A", "2", "1", "跳过"),
            RestoreToolPlan("b", "B", "2", null, "跳过"),
        )))
    }
}
