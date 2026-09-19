package io.toolbox.host.icons

import io.toolbox.core.data.InstalledTool
import io.toolbox.tool.packagekit.InstalledManifestVerification
import io.toolbox.tool.packagekit.InstalledManifestVerifier
import io.toolbox.tool.runtime.RuntimeIdentity
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal data class ToolIconSource(val bytes: ByteArray, val isSvg: Boolean)

internal class IconResourcesUnavailable : Exception("Icon exceeds currently available memory")

internal class InstalledToolIconReader(
    private val privateFilesRoot: () -> Path,
    private val availableHeapBytes: () -> Long = io.toolbox.core.data.ResourceCapacity::availableHeapBytes,
) {
    fun read(tool: InstalledTool): ToolIconSource? = readInstalled(tool)

    private fun readInstalled(tool: InstalledTool): ToolIconSource? {
        val version = tool.currentVersion
        if (version.toolId != tool.metadata.id) return null
        val locator = RuntimeIdentity.expectedBundleLocator(version.toolId, version.versionCode)
        if (version.bundleLocator.value != locator) return null
        val root = privateFilesRoot().toAbsolutePath().normalize()
        val bundle = safePath(root, locator, directory = true) ?: return null
        val manifestFile = safePath(bundle, "manifest.json") ?: return null
        val manifestBytes = readBounded(manifestFile) ?: return null
        val verified = InstalledManifestVerifier.verify(
            manifestBytes, tool.metadata.id, version.versionCode, tool.metadata.securityProfile,
        ) as? InstalledManifestVerification.Verified ?: return null
        val icon = verified.manifest.icon ?: return null
        val file = safePath(bundle, icon) ?: return null
        val isSvg = icon.substringAfterLast('.', "").equals("svg", ignoreCase = true)
        val bytes = readBounded(file) ?: return null
        return ToolIconSource(bytes, isSvg)
    }

    private fun safePath(root: Path, relative: String, directory: Boolean = false): Path? {
        val parts = relative.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." || '\\' in it || ':' in it }) return null
        if (!Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isDirectory) return null
        var current = root
        parts.forEachIndexed { index, part ->
            current = current.resolve(part)
            val attributes = Files.readAttributes(current, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (attributes.isSymbolicLink) return null
            if (index < parts.lastIndex || directory) {
                if (!attributes.isDirectory) return null
            } else if (!attributes.isRegularFile) return null
        }
        return current.takeIf { it.normalize().startsWith(root) }
    }

    private fun readBounded(path: Path): ByteArray? {
        val size = Files.size(path)
        if (size == 0L) return null
        // Capacity changes between loads. Never turn a resource-dependent refusal into a
        // permanent "missing icon" result cached for this installed version.
        val limit = (availableHeapBytes() / 2).coerceIn(0, Int.MAX_VALUE.toLong())
        if (size > limit) throw IconResourcesUnavailable()
        return Files.newInputStream(path, NOFOLLOW_LINKS).use { stream ->
            val bytes = stream.readNBytes(size.toInt())
            if (bytes.size.toLong() != size || stream.read() != -1) {
                throw java.io.IOException("Icon file changed while reading")
            }
            bytes
        }
    }
}
