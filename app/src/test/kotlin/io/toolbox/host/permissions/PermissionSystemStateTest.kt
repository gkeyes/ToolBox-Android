package io.toolbox.host.permissions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionSystemStateTest {
    private val coarse = "android.permission.ACCESS_COARSE_LOCATION"
    private val fine = "android.permission.ACCESS_FINE_LOCATION"
    private fun item(capability: String, vararg permissions: String) =
        PermissionItem(capability, capability, "", enabled = true, androidPermissions = permissions.toList())

    @Test fun locationNeedsEitherCoarseOrFine() {
        val location = item("location", coarse, fine)
        assertTrue(location.systemAllowed { it == coarse })
        assertFalse(location.systemAllowed { false })
    }

    @Test fun revokedNotificationPermissionIsReportedOff() {
        val notifications = item("notifications", "android.permission.POST_NOTIFICATIONS")
        assertFalse(notifications.systemAllowed { false })
        assertTrue(notifications.systemAllowed { true })
        assertTrue(item("storage").systemAllowed { false })
    }
}
