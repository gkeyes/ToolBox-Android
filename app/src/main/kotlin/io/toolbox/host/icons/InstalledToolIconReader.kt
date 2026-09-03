package io.toolbox.host.icons

import io.toolbox.core.data.InstalledTool
import io.toolbox.tool.packagekit.InstalledManifestVerification
import io.toolbox.tool.packagekit.InstalledManifestVerifier
import io.toolbox.tool.runtime.RuntimeIdentity
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal data class ToolIconSource(val bytes: ByteArray, val isSvg: Boolean)

internal class InstalledToolIconReader(private val privateFilesRoot: () -> Path) {
    fun read(tool: InstalledTool): ToolIconSource? = try {
        readInstalled(tool)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun readInstalled(tool: InstalledTool): ToolIconSource? {
        val version = tool.currentVersion
        if (version.toolId != tool.metadata.id) return null
        val locator = RuntimeIdentity.expectedBundleLocator(version.toolId, version.versionCode)
        if (version.bundleLocator.value != locator) return null
        val root = privateFilesRoot().toAbsolutePath().normalize()
        val bundle = safePath(root, locator, directory = true) ?: return null
        val manifestFile = safePath(bundle, "manifest.json") ?: return null
        val manifestBytes = readBounded(manifestFile, MAX_MANIFEST_BYTES) ?: return null
        val verified = InstalledManifestVerifier.verify(
            manifestBytes, tool.metadata.id, version.versionCode, tool.metadata.securityProfile,
        ) as? InstalledManifestVerification.Verified ?: return null
        val icon = verified.manifest.icon ?: return null
        val file = safePath(bundle, icon) ?: return null
        val isSvg = icon.substringAfterLast('.', "").equals("svg", ignoreCase = true)
        val bytes = readBounded(file, if (isSvg) MAX_SVG_BYTES else MAX_ICON_BYTES) ?: return null
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

    private fun readBounded(path: Path, limit: Int): ByteArray? {
        if (Files.size(path) !in 1L..limit.toLong()) return null
        return Files.newInputStream(path, NOFOLLOW_LINKS).use { stream ->
            stream.readNBytes(limit + 1).takeIf { it.isNotEmpty() && it.size <= limit }
        }
    }

    companion object {
        const val MAX_ICON_BYTES = 4 * 1024 * 1024
        const val MAX_SVG_BYTES = 256 * 1024
        private const val MAX_MANIFEST_BYTES = 128 * 1024
    }
}
