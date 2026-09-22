package io.toolbox.host.permissions

import io.toolbox.core.data.PermissionGrant
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolPermissionSummaryTest {
    @Test
    fun explicitRevocationOverridesTheSharedDefault() {
        assertEquals(ToolPermissionSummary.Ready(1, 2), summarizeToolPermissions("t", setOf("storage", "network"), listOf(
            PermissionGrant("t", "storage", false, 1), PermissionGrant("t", "network", true, 1),
        )))
    }

    @Test
    fun absentGrantsUseThePermissionDetailsPolicy() {
        val declared = setOf("storage", "storage.secure", "device.basic", "clipboard.write", "haptics", "network", "camera")
        assertEquals(ToolPermissionSummary.Ready(5, 7), summarizeToolPermissions("t", declared, emptyList()))
    }

    @Test
    fun undeclaredAndOtherToolGrantsNeverInflateTheCount() {
        assertEquals(ToolPermissionSummary.Ready(0, 1), summarizeToolPermissions("t", setOf("network"), listOf(
            PermissionGrant("other", "network", true, 1), PermissionGrant("t", "camera", true, 1),
        )))
    }

    @Test
    fun noDeclaredPermissionsAreNotPresentedAsFullyAuthorized() {
        assertEquals("未声明权限", summarizeToolPermissions("t", emptySet(), emptyList()).label)
    }
}
