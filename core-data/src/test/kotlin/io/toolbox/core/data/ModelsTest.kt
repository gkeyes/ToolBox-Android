package io.toolbox.core.data

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelsTest {
    @Test
    fun bundleLocatorAcceptsNormalizedRelativePaths() {
        for (value in listOf("bundle", "tools/io.example/7", "a/b-c_d.e/f")) {
            assertEquals(value, BundleLocator(value).value)
        }
    }

    @Test
    fun bundleLocatorRejectsPathsThatCouldEscapeTheBundleRoot() {
        val rejected = listOf(
            "", " ", "/tools/a", "tools\\a", "..", "tools/../a", "tools/./a", "./tools", "tools//a", "tools/a/", "tools/ /a",
        )
        for (value in rejected) {
            assertThrows("accepted \"$value\"", IllegalArgumentException::class.java) { BundleLocator(value) }
        }
    }

    @Test
    fun keyValueBytesCountUtf8NotChars() {
        assertEquals(2, ToolKvValue("k", "\"\"", 0).bytes)
        assertEquals(5, ToolKvValue("k", "\"值\"", 0).bytes)
        assertEquals(6, ToolKvValue("k", "\"😀\"", 0).bytes)
    }

    @Test
    fun capacityChecksRejectNegativeAndUnavailableSizesWithStableCodes() {
        val heap = assertThrows(IllegalArgumentException::class.java) { ResourceCapacity.requireHeapBytes(-1) }
        assertEquals("INSUFFICIENT_MEMORY", heap.message)
        ResourceCapacity.requireHeapBytes(0)
        assertThrows(IllegalArgumentException::class.java) { ResourceCapacity.requireHeapBytes(Long.MAX_VALUE) }

        val directory = Files.createTempDirectory("capacity").toFile()
        try {
            ResourceCapacity.requireStorageBytes(directory, 0)
            val storage = assertThrows(IllegalArgumentException::class.java) {
                ResourceCapacity.requireStorageBytes(directory, Long.MAX_VALUE)
            }
            assertEquals("INSUFFICIENT_STORAGE", storage.message)
            assertThrows(IllegalArgumentException::class.java) { ResourceCapacity.requireStorageBytes(directory, -1) }
        } finally {
            directory.deleteRecursively()
        }
    }
}
