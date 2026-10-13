package io.toolbox.tool.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolBoxApiV1Test {
    @Test
    fun everyCapabilityIdHasExactlyOneDescriptor() {
        val ids = ToolBoxApiV1.capabilities.map { it.id }
        assertEquals(ToolBoxCapabilityId.entries.toSet(), ids.toSet())
        assertEquals("duplicate capability descriptor", ids.size, ids.toSet().size)
        for (id in ToolBoxCapabilityId.entries) assertSame(id, ToolBoxApiV1.capability(id).id)
    }

    @Test
    fun wireNamesAreUniqueAndResolveBackToTheirDescriptor() {
        // Lookups are built with associateBy, which would silently keep only the last duplicate.
        val names = ToolBoxApiV1.capabilities.map { it.wireName }
        assertEquals("duplicate capability wire name", names.size, names.toSet().size)
        for (descriptor in ToolBoxApiV1.capabilities) {
            assertSame(descriptor, ToolBoxApiV1.capability(descriptor.wireName))
        }
        assertNull(ToolBoxApiV1.capability("Storage"))
        assertNull(ToolBoxApiV1.capability("storage "))
        assertNull(ToolBoxApiV1.capability(""))
    }

    @Test
    fun wireNamesStayStableBecauseGrantsAndManifestsStoreThem() {
        // Installed manifests and saved permission grants refer to these strings. Renaming one
        // silently revokes the grant for every installed tool, so a change needs a data migration.
        val expected = mapOf(
            ToolBoxCapabilityId.STORAGE to "storage",
            ToolBoxCapabilityId.STORAGE_SECURE to "storage.secure",
            ToolBoxCapabilityId.CLIPBOARD_WRITE to "clipboard.write",
            ToolBoxCapabilityId.CLIPBOARD_READ to "clipboard.read",
            ToolBoxCapabilityId.SHARE to "share",
            ToolBoxCapabilityId.BROWSER to "browser",
            ToolBoxCapabilityId.FILES_OPEN to "files.open",
            ToolBoxCapabilityId.FILES_SAVE to "files.save",
            ToolBoxCapabilityId.NETWORK to "network",
            ToolBoxCapabilityId.DEVICE_BASIC to "device.basic",
            ToolBoxCapabilityId.HAPTICS to "haptics",
            ToolBoxCapabilityId.NOTIFICATIONS to "notifications",
            ToolBoxCapabilityId.SHORTCUTS to "shortcuts",
            ToolBoxCapabilityId.CAMERA to "camera",
            ToolBoxCapabilityId.LOCATION to "location",
            ToolBoxCapabilityId.BACKGROUND_TASKS to "background.tasks",
            ToolBoxCapabilityId.BACKGROUND_RUNTIME to "background.runtime",
            ToolBoxCapabilityId.LOCATION_BACKGROUND to "location.background",
            ToolBoxCapabilityId.ALARMS to "alarms",
        )
        assertEquals(expected, ToolBoxApiV1.capabilities.associate { it.id to it.wireName })
    }

    @Test
    fun systemPermissionsAreFullyQualifiedAndroidPermissions() {
        for (descriptor in ToolBoxApiV1.capabilities) {
            for (permission in descriptor.systemPermissions) {
                assertTrue("${descriptor.wireName}: $permission", permission.matches(Regex("^android\\.permission\\.[A-Z_]+$")))
            }
        }
        assertEquals(
            setOf("android.permission.ACCESS_BACKGROUND_LOCATION"),
            ToolBoxApiV1.capability(ToolBoxCapabilityId.LOCATION_BACKGROUND).systemPermissions,
        )
    }

    @Test
    fun methodNamesAreUniqueAndResolve() {
        val names = ToolBoxApiV1.methods.map { it.name }
        assertEquals("duplicate method", names.size, names.toSet().size)
        for (method in ToolBoxApiV1.methods) {
            assertSame(method, ToolBoxApiV1.method(method.name))
            assertTrue(method.name, method.name.matches(Regex("^[a-z]+(\\.[a-z][A-Za-z0-9]*)*$")))
            assertTrue(method.name, method.requestType.isNotBlank() && method.resultType.isNotBlank())
        }
        assertNull(ToolBoxApiV1.method("storage.GET"))
        assertNull(ToolBoxApiV1.method("network.authorizeDomain"))
    }

    @Test
    fun onlyTheseMethodsRunWithoutACapabilityGrant() {
        // Adding a method here exposes it to every installed tool; that must be a deliberate choice.
        val ungated = ToolBoxApiV1.methods.filter { it.capability == null }.map { it.name }.toSet()
        assertEquals(
            setOf(
                "ready",
                "runtime.getState",
                "runtime.ackEvents",
                "runtime.flushComplete",
                "ui.toast",
                "crypto.sha256",
                "files.read",
            ),
            ungated,
        )
    }

    @Test
    fun everyCapabilityGatesAtLeastOneMethod() {
        val used = ToolBoxApiV1.methods.mapNotNull { it.capability }.toSet()
        // LOCATION_BACKGROUND gates no RPC of its own; manifests and the permission center use it by wire name.
        assertEquals(ToolBoxCapabilityId.entries.toSet() - ToolBoxCapabilityId.LOCATION_BACKGROUND, used)
    }

    @Test
    fun storageMethodsCannotReachSecureStorageWithThePlainGrant() {
        for (method in ToolBoxApiV1.methods.filter { it.name.startsWith("storage.secure.") }) {
            assertEquals(method.name, ToolBoxCapabilityId.STORAGE_SECURE, method.capability)
        }
        for (method in ToolBoxApiV1.methods.filter { it.name.startsWith("storage.") && !it.name.startsWith("storage.secure.") }) {
            assertEquals(method.name, ToolBoxCapabilityId.STORAGE, method.capability)
        }
    }
}
