package io.toolbox.host.icons

import io.toolbox.core.data.BundleLocator
import io.toolbox.core.data.InstalledTool
import io.toolbox.core.data.SecurityProfile
import io.toolbox.core.data.ToolMetadata
import io.toolbox.core.data.ToolVersion
import io.toolbox.tool.runtime.RuntimeIdentity
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstalledToolIconReaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root: Path get() = temporary.root.toPath()
    private val toolId = "io.example.icon"
    private val reader get() = InstalledToolIconReader { root }

    @Test
    fun readsDeclaredNestedIconOfTheActiveVersionWithoutDependingOnAHostRelease() {
        val first = install(1, "art/icon.png", byteArrayOf(1, 2))
        val second = install(2, "art/icon.svg", byteArrayOf(3, 4))
        assertArrayEquals(byteArrayOf(1, 2), reader.read(first)?.bytes)
        assertFalse(requireNotNull(reader.read(first)).isSvg)
        assertArrayEquals(byteArrayOf(3, 4), reader.read(second)?.bytes)
        assertTrue(requireNotNull(reader.read(second)).isSvg)
    }

    @Test
    fun missingOrOversizedArtworkFallsBackWithoutReadingAnotherFile() {
        val tool = install(1, "icon.png", byteArrayOf(1))
        val file = bundle(1).resolve("icon.png")
        Files.delete(file)
        assertNull(reader.read(tool))
        Files.write(file, ByteArray(InstalledToolIconReader.MAX_ICON_BYTES + 1))
        assertNull(reader.read(tool))
        Files.writeString(bundle(1).resolve("manifest.json"), manifest(1, null))
        assertNull(reader.read(tool))
    }

    @Test
    fun rejectsIdentityMismatchAndPathsOutsideTheActiveBundle() {
        val tool = install(1, "icon.png", byteArrayOf(1))
        listOf("../icon.png", "/icon.png", "https://example.com/icon.png").forEach { icon ->
            Files.writeString(bundle(1).resolve("manifest.json"), manifest(1, icon))
            assertNull(reader.read(tool))
        }
        Files.writeString(bundle(1).resolve("manifest.json"), manifest(2, "icon.png"))
        assertNull(reader.read(tool))
        val wrongLocator = tool.currentVersion.copy(bundleLocator = BundleLocator("other/bundle"))
        assertNull(reader.read(tool.copy(currentVersion = wrongLocator)))
    }

    @Test
    fun rejectsIconAndParentSymlinks() {
        val tool = install(1, "art/icon.png", byteArrayOf(1))
        val outside = Files.createDirectories(root.resolve("outside"))
        Files.write(outside.resolve("icon.png"), byteArrayOf(9))
        val art = bundle(1).resolve("art")
        Files.delete(art.resolve("icon.png"))
        Files.createSymbolicLink(art.resolve("icon.png"), outside.resolve("icon.png"))
        assertNull(reader.read(tool))
        Files.delete(art.resolve("icon.png"))
        Files.delete(art)
        Files.createSymbolicLink(art, outside)
        assertNull(reader.read(tool))
    }

    private fun install(version: Int, icon: String, bytes: ByteArray): InstalledTool {
        val directory = Files.createDirectories(bundle(version))
        Files.writeString(directory.resolve("manifest.json"), manifest(version, icon))
        val image = directory.resolve(icon)
        Files.createDirectories(image.parent)
        Files.write(image, bytes)
        return InstalledTool(
            ToolMetadata(toolId, "图标测试", SecurityProfile.STRICT, version.toLong()),
            ToolVersion(
                toolId, version, "1.0.$version", BundleLocator(RuntimeIdentity.expectedBundleLocator(toolId, version)),
                bytes.size.toLong(), "a".repeat(64), version.toLong(),
            ),
            null,
        )
    }

    private fun bundle(version: Int) = root.resolve(RuntimeIdentity.expectedBundleLocator(toolId, version))

    private fun manifest(version: Int, icon: String?) = """
        {"schemaVersion":1,"id":"$toolId","name":"图标测试","version":"1.0.$version",
         "versionCode":$version,"apiVersion":"1.0","minHostVersion":"0.3.2","entry":"index.html",
         ${icon?.let { "\"icon\":\"$it\"," } ?: ""}
         "securityProfile":"strict","permissions":[]}
    """.trimIndent()
}
