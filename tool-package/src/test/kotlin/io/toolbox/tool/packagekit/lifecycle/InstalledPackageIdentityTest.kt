package io.toolbox.tool.packagekit.lifecycle

import io.toolbox.tool.packagekit.PackageInput
import io.toolbox.tool.packagekit.PackageResourceProbe
import io.toolbox.tool.packagekit.PackageResourceSnapshot
import io.toolbox.tool.packagekit.PackageValidationResult
import io.toolbox.tool.packagekit.ToolPackageInspectors
import io.toolbox.tool.packagekit.fixtures.InMemoryCoreData
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A backup-restore preview compares a backed-up package with the installed one through these values. */
class InstalledPackageIdentityTest {
    private val probe = PackageResourceProbe { PackageResourceSnapshot(Long.MAX_VALUE, false) }

    @Test
    fun validationReportsTheInstalledDigestAndUnsignedIdentity() = runBlocking {
        val root = Files.createTempDirectory("installed-identity")
        try {
            val data = InMemoryCoreData.create()
            val manager = ToolPackageManagers.create(
                privateFilesDirectory = root.toFile(),
                catalog = data.catalog,
                lifecycle = data.lifecycle,
                transactions = data.installs,
                resourceProbe = probe,
            )
            assertEquals(PackageInstallResult.Installed(ID, 1, false), manager.importAndInstall(input(1)))
            val installed = checkNotNull(data.catalog.observeTool(ID).first()).currentVersion
            assertNull(InstalledPackageIdentity.signingKey(root.toFile(), installed, probe))

            val inspector = ToolPackageInspectors.create(root.resolve("inspection").toFile(), probe)
            val same = inspector.validate(input(1)) as PackageValidationResult.Valid
            assertNull(same.signingKeyId)
            assertEquals(installed.integrityHash, same.integrityHash)

            val other = inspector.validate(input(1, body = "changed")) as PackageValidationResult.Valid
            assertNotEquals(installed.integrityHash, other.integrityHash)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun input(version: Int, body: String = "fixture") = object : PackageInput {
        override val displayName = "fixture.tbx"
        override fun openStream() = ByteArrayInputStream(packageBytes(version, body))
    }

    private fun packageBytes(version: Int, body: String): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            mapOf(
                "manifest.json" to """{"schemaVersion":1,"id":"$ID","name":"Fixture","version":"1.0.$version","versionCode":$version,"entry":"index.html","apiVersion":"1.0","minHostVersion":"0.2.0","permissions":[],"securityProfile":"strict"}""",
                "index.html" to "<!doctype html><html><body>$body</body></html>",
            ).forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    private companion object {
        const val ID = "io.toolbox.identity"
    }
}
