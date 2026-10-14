package io.toolbox.host.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun cancellingAnExportDoesNotTalkAboutRestore() {
        assertFalse(cancelledResult(exporting = true, running = false).message.contains("恢复"))
        assertFalse(cancelledResult(exporting = true, running = true).message.contains("恢复"))
        assertTrue(cancelledResult(exporting = false, running = false).message.contains("未执行恢复"))
        assertTrue(cancelledResult(exporting = false, running = true).message.contains("回滚"))
    }
}
