package io.toolbox.tool.packagekit.lifecycle

import io.toolbox.core.data.ResourceCapacity
import io.toolbox.core.data.ToolVersion
import io.toolbox.tool.packagekit.backup.BackupArchive
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Repacks an installed bundle into a .tbx that a normal import accepts unchanged (backups and tool export). */
object InstalledPackageExport {
    /**
     * Writes [version]'s installed files, including integrity.json and signature.json when present, to [output].
     * The caller holds the package lock so the bundle cannot be replaced while it is read.
     */
    suspend fun write(privateFilesDirectory: File, version: ToolVersion, output: File) {
        val bundle = File(privateFilesDirectory, version.bundleLocator.value)
        check(bundle.canonicalFile.toPath().startsWith(File(privateFilesDirectory, "miniapps/${version.toolId}/versions").canonicalFile.toPath()))
        val parent = requireNotNull(output.absoluteFile.parentFile)
        val destination = object : FilterOutputStream(FileOutputStream(output)) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                ResourceCapacity.requireStorageBytes(parent, length.toLong())
                out.write(bytes, offset, length)
            }
            override fun write(value: Int) {
                ResourceCapacity.requireStorageBytes(parent, 1)
                out.write(value)
            }
        }
        ZipOutputStream(destination).use { zip ->
            BackupArchive.regularFiles(bundle).forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(bundle).invariantSeparatorsPath))
                file.inputStream().use { BackupArchive.copy(it, zip) }
                zip.closeEntry()
            }
        }
    }
}
