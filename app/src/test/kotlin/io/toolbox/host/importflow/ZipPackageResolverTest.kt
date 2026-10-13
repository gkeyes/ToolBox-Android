package io.toolbox.host.importflow

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipPackageResolverTest {
    @Test
    fun finderArchiveWithMacOsMetadataResolvesTheSinglePackage() {
        val result = resolve("tool.tbx", "__MACOSX/._tool.tbx", "__MACOSX/.DS_Store")
        assertTrue(result.toString(), result is SelectedPackageSource.Ready)
        val input = (result as SelectedPackageSource.Ready).input
        assertEquals("tool.tbx", input.displayName)
        assertEquals("package:tool.tbx", input.openStream().use { String(it.readBytes()) })
    }

    @Test
    fun appleDoubleFileOutsideMacOsFolderIsIgnored() {
        assertTrue(resolve("dir/tool.tbx", "dir/._tool.tbx") is SelectedPackageSource.Ready)
    }

    @Test
    fun twoRealPackagesAreStillRejected() {
        assertTrue(resolve("a.tbx", "b.tbx") is SelectedPackageSource.Rejected)
    }

    private fun resolve(vararg names: String): SelectedPackageSource {
        val bytes = ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { zip ->
                names.forEach { name ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write("package:${name.substringAfterLast('/')}".toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val cache = Files.createTempDirectory("zip-resolver").toFile()
        return ZipPackageResolver.resolve(cache) { ByteArrayInputStream(bytes) }
    }
}
