package io.toolbox.host.runtime

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.toolbox.core.data.CoreDataFactory
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.PermissionGrant
import io.toolbox.host.HostDeleteResult
import io.toolbox.host.HostImportResult
import io.toolbox.host.ProductionHostDependenciesFactory
import io.toolbox.tool.packagekit.PackageInput
import io.toolbox.tool.runtime.RpcValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the actual host installer, cleanup, Room and AndroidKeyStore, not a harmless cleanup fake. */
@RunWith(AndroidJUnit4::class)
class TbxUpgradePersistenceTest {
    @Test
    fun hostUpdateRetainsOriginalCiphertextAndKey() = runBlocking(Dispatchers.IO) {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val toolId = "io.toolbox.upgrade.t$suffix"
        val databaseName = "upgrade-$suffix.db"
        val stores = CoreDataFactory.create(application, databaseName, "upgrade-$suffix")
        val dependencies = ProductionHostDependenciesFactory.create(application, stores)
        val packages = dependencies.packageOperations
        try {
            assertTrue(packages.importPackage(fixture(toolId, 1)) is HostImportResult.Installed)
            assertEquals(DataResult.Success(Unit), stores.repositories.grants.put(PermissionGrant(toolId, "network", false, 10)))
            val originalGrants = stores.repositories.grants.observeGrants(toolId).first()
            val ordinary = StandardToolKvStorageHandler(toolId, stores.repositories.keyValues, { 11 })
            val secure = createRuntimeSecureStorageHandler(toolId, stores.repositories.keyValues, { 11 }, { true })
            val auth = RpcValue.ObjectValue(mapOf(
                "serverUrl" to RpcValue.StringValue("https://fixture.invalid"),
                "userId" to RpcValue.Number(7.0),
                "token" to RpcValue.StringValue("synthetic-upgrade-token-not-a-real-credential"),
            ))
            ordinary.set("fixture.settings", RpcValue.StringValue("keep-settings"))
            secure.set("fixture.auth", auth)
            val envelope = RuntimeSecureEnvelopeStorage(toolId, stores.repositories.keyValues)
            val originalCiphertext = envelope.read()
            assertNotNull(originalCiphertext)
            assertTrue(hasKey(toolId))

            val candidate = packages.importPackage(fixture(toolId, 2))
            val installed = if (candidate is HostImportResult.ConfirmationRequired) {
                // Confirmation must not publish candidate code or clear/re-authorize old storage.
                assertEquals(1, stores.repositories.catalog.observeTool(toolId).first()!!.currentVersion.versionCode)
                assertEquals(originalCiphertext, envelope.read())
                assertEquals(originalGrants, stores.repositories.grants.observeGrants(toolId).first())
                packages.confirmImport(candidate.confirmation.id)
            } else candidate
            assertTrue(installed.toString(), installed is HostImportResult.Installed)
            assertEquals("Secure ciphertext must survive host TBX replacement", originalCiphertext, envelope.read())
            assertTrue("The original tool key must remain present", hasKey(toolId))
            assertEquals(auth, createRuntimeSecureStorageHandler(toolId, stores.repositories.keyValues, { 12 }, { true }).get("fixture.auth"))
            assertEquals(RpcValue.StringValue("keep-settings"), ordinary.get("fixture.settings"))
            assertEquals(originalGrants, stores.repositories.grants.observeGrants(toolId).first())

            // Maintenance must not replay a destructive upgrade cleanup later.
            repeat(2) { dependencies.recoverPendingPackageMutations() }
            assertEquals(originalCiphertext, envelope.read())
            assertEquals(auth, secure.get("fixture.auth"))

            assertEquals(HostDeleteResult.Deleted, packages.deleteTool(toolId))
            assertFalse(hasKey(toolId))
            assertTrue(stores.repositories.keyValues.keys(toolId).isEmpty())
        } finally {
            dependencies.runtimeSessions.releaseTool(toolId)
            packages.deleteTool(toolId)
            stores.close()
            application.deleteDatabase(databaseName)
        }
    }

    private fun hasKey(toolId: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256").digest(toolId.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .containsAlias("toolbox.runtime.secure.v1.$digest")
    }

    private fun fixture(toolId: String, version: Int): PackageInput {
        val manifest = """{"schemaVersion":1,"id":"$toolId","name":"Upgrade fixture","version":"1.0.$version","versionCode":$version,"entry":"index.html","apiVersion":"1.0","minHostVersion":"0.3.3","permissions":[{"name":"storage","reason":"Fixture settings"},{"name":"storage.secure","reason":"Synthetic credentials"},{"name":"network","reason":"Keep disabled choice"}],"securityProfile":"strict"}"""
        val bytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                for ((path, content) in mapOf("manifest.json" to manifest, "index.html" to "<!doctype html><html><body>Upgrade fixture</body></html>")) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }
        return object : PackageInput {
            override val displayName = "fixture-$version.tbx"
            override fun openStream() = ByteArrayInputStream(bytes)
        }
    }
}
