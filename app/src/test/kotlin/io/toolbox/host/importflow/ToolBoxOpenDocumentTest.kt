package io.toolbox.host.importflow

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolBoxOpenDocumentTest {
    @Test
    fun directTbxImportRemainsUnchanged() {
        val payload = byteArrayOf(1, 2, 3, 4)
        withTempDirectory { cache ->
            val source = selectedPackageSource("sample.tbx", cache) { ByteArrayInputStream(payload) }
            assertTrue(source is SelectedPackageSource.Ready)
            val input = (source as SelectedPackageSource.Ready).input
            assertEquals("sample.tbx", input.displayName)
            assertArrayEquals(payload, input.openStream().use { it.readBytes() })
        }
    }

    @Test
    fun zipWithSingleNestedTbxImportsTheEmbeddedPackage() {
        val payload = byteArrayOf(9, 8, 7, 6)
        val archive = zipOf(
            "artifact/readme.txt" to "metadata".encodeToByteArray(),
            "artifact/nextflux.tbx" to payload,
        )
        withTempDirectory { cache ->
            val source = selectedPackageSource("github-artifact.zip", cache) { ByteArrayInputStream(archive) }
            assertTrue(source is SelectedPackageSource.Ready)
            val input = (source as SelectedPackageSource.Ready).input
            assertEquals("nextflux.tbx", input.displayName)
            assertArrayEquals(payload, input.openStream().use { it.readBytes() })
        }
    }

    @Test
    fun zipWithoutTbxIsRejected() {
        val archive = zipOf("README.md" to byteArrayOf(1))
        withTempDirectory { cache ->
            val source = selectedPackageSource("artifact.zip", cache) { ByteArrayInputStream(archive) }
            assertTrue(source is SelectedPackageSource.Rejected)
            assertTrue((source as SelectedPackageSource.Rejected).message.contains("没有找到 TBX"))
        }
    }

    @Test
    fun zipWithMultipleTbxFilesDoesNotGuessWhichOneToInstall() {
        val archive = zipOf(
            "a.tbx" to byteArrayOf(1),
            "b.tbx" to byteArrayOf(2),
        )
        withTempDirectory { cache ->
            val source = selectedPackageSource("artifact.zip", cache) { ByteArrayInputStream(archive) }
            assertTrue(source is SelectedPackageSource.Rejected)
            assertTrue((source as SelectedPackageSource.Rejected).message.contains("2 个 TBX"))
        }
    }

    @Test
    fun unsafeEmbeddedTbxPathIsRejected() {
        val archive = zipOf("../evil.tbx" to byteArrayOf(1))
        withTempDirectory { cache ->
            val source = selectedPackageSource("artifact.zip", cache) { ByteArrayInputStream(archive) }
            assertTrue(source is SelectedPackageSource.Rejected)
            assertTrue((source as SelectedPackageSource.Rejected).message.contains("路径不安全"))
        }
    }

    @Test
    fun uppercaseZipAndTbxExtensionsAreAccepted() {
        val payload = byteArrayOf(3, 2, 1)
        val archive = zipOf("TOOLS/SAMPLE.TBX" to payload)
        withTempDirectory { cache ->
            val source = selectedPackageSource("ARTIFACT.ZIP", cache) { ByteArrayInputStream(archive) }
            assertTrue(source is SelectedPackageSource.Ready)
            assertArrayEquals(payload, (source as SelectedPackageSource.Ready).input.openStream().use { it.readBytes() })
        }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.forEach { (name, payload) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(payload)
                    zip.closeEntry()
                }
            }
            bytes.toByteArray()
        }

    private inline fun withTempDirectory(block: (File) -> Unit) {
        val directory = kotlin.io.path.createTempDirectory("toolbox-import-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
