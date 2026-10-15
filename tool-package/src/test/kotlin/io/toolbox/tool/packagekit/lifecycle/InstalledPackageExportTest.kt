package io.toolbox.tool.packagekit.lifecycle

import io.toolbox.core.data.CoreDataRepositories
import io.toolbox.tool.packagekit.PackageInput
import io.toolbox.tool.packagekit.PackageResourceProbe
import io.toolbox.tool.packagekit.PackageResourceSnapshot
import io.toolbox.tool.packagekit.fixtures.InMemoryCoreData
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** An exported tool must install on another ToolBox exactly as the original package did. */
class InstalledPackageExportTest {
    private val probe = PackageResourceProbe { PackageResourceSnapshot(Long.MAX_VALUE, false) }

    @Test
    fun exportedPackageReimportsWithTheSameContent() = runBlocking {
        val root = Files.createTempDirectory("installed-export").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            val sourceData = InMemoryCoreData.create()
            assertEquals(PackageInstallResult.Installed(ID, 3, false), manager(source, sourceData).importAndInstall(input(packageBytes())))
            val installed = checkNotNull(sourceData.catalog.observeTool(ID).first()).currentVersion

            val exported = File(root, "exported.tbx")
            InstalledPackageExport.write(source, installed, exported)
            ZipFile(exported).use { zip ->
                assertEquals(FILES.keys, zip.entries().asSequence().map { it.name }.toSet())
                FILES.forEach { (path, content) -> assertEquals(content, zip.getInputStream(zip.getEntry(path)).readBytes().decodeToString()) }
            }

            val target = File(root, "target").apply { mkdirs() }
            val targetData = InMemoryCoreData.create()
            assertEquals(PackageInstallResult.Installed(ID, 3, false), manager(target, targetData).importAndInstall(input(exported.readBytes())))
            val reinstalled = checkNotNull(targetData.catalog.observeTool(ID).first()).currentVersion
            assertEquals(installed.integrityHash, reinstalled.integrityHash)
            assertEquals(installed.bundleBytes, reinstalled.bundleBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun manager(root: File, data: CoreDataRepositories) = ToolPackageManagers.create(
        privateFilesDirectory = root,
        catalog = data.catalog,
        lifecycle = data.lifecycle,
        transactions = data.installs,
        resourceProbe = probe,
    )

    private fun input(bytes: ByteArray) = object : PackageInput {
        override val displayName = "fixture.tbx"
        override fun openStream() = ByteArrayInputStream(bytes)
    }

    private fun packageBytes(): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            FILES.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    private companion object {
        const val ID = "io.toolbox.export"
        val FILES = mapOf(
            "manifest.json" to """{"schemaVersion":1,"id":"$ID","name":"Export","version":"1.0.3","versionCode":3,"entry":"index.html","apiVersion":"1.0","minHostVersion":"0.2.0","permissions":[],"securityProfile":"strict"}""",
            "index.html" to "<!doctype html><html><body><script src=\"js/app.js\"></script></body></html>",
            "js/app.js" to "document.body.append('exported')",
        )
    }
}
