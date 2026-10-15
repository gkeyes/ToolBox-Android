package io.toolbox.host.permissions

import io.toolbox.core.data.PermissionGrant
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolPermissionSummaryTest {
    @Test
    fun explicitRevocationOverridesTheSharedDefault() {
        assertEquals(ToolPermissionSummary.Ready(1, 2, setOf("network")), summarizeToolPermissions("t", setOf("storage", "network"), listOf(
            PermissionGrant("t", "storage", false, 1), PermissionGrant("t", "network", true, 1),
        )))
    }

    @Test
    fun absentGrantsUseThePermissionDetailsPolicy() {
        val declared = setOf("storage", "storage.secure", "device.basic", "clipboard.write", "haptics", "network", "camera")
        assertEquals(5 to 7, summarizeToolPermissions("t", declared, emptyList()).let { it.enabled to it.total })
    }

    @Test
    fun undeclaredAndOtherToolGrantsNeverInflateTheCount() {
        assertEquals(ToolPermissionSummary.Ready(0, 1, emptySet()), summarizeToolPermissions("t", setOf("network"), listOf(
            PermissionGrant("other", "network", true, 1), PermissionGrant("t", "camera", true, 1),
        )))
    }

    @Test
    fun noDeclaredPermissionsAreNotPresentedAsFullyAuthorized() {
        assertEquals("未声明权限", summarizeToolPermissions("t", emptySet(), emptyList()).label)
    }

    @Test
    fun capabilitiesRevokedInSystemSettingsCountAsOff() {
        val stored = summarizeToolPermissions("t", setOf("network", "notifications", "location"), listOf(
            PermissionGrant("t", "network", true, 1), PermissionGrant("t", "notifications", true, 1), PermissionGrant("t", "location", true, 1),
        ))
        val revoked = stored.withSystemState({ false })
        assertEquals(1, (revoked as ToolPermissionSummary.Ready).enabled)
        assertEquals("已允许 1 / 3 项 · 2 项关闭", revoked.label)
        assertEquals(3, (stored.withSystemState({ true }) as ToolPermissionSummary.Ready).enabled)
    }

    @Test
    fun revokedExactAlarmAccessCountsAlarmsAsOff() {
        val stored = summarizeToolPermissions("t", setOf("alarms"), listOf(PermissionGrant("t", "alarms", true, 1)))
        assertEquals(0, (stored.withSystemState({ true }, exactAlarms = false) as ToolPermissionSummary.Ready).enabled)
        assertEquals(1, (stored.withSystemState({ true }, exactAlarms = true) as ToolPermissionSummary.Ready).enabled)
    }
}
