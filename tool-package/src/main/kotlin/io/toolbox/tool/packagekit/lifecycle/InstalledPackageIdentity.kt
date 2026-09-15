package io.toolbox.tool.packagekit.lifecycle

import io.toolbox.core.data.ToolVersion
import io.toolbox.tool.packagekit.IntegrityVerifier
import io.toolbox.tool.packagekit.FileSystemPackageResourceProbe
import io.toolbox.tool.packagekit.PackageResourceGuard
import io.toolbox.tool.packagekit.PackageResourceProbe
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Derive continuity from the hash-pinned installed bundle, not from an unverified keyId or trust DB. */
internal fun installedSigningKey(
    filesRoot: Path,
    version: ToolVersion,
    resourceProbe: PackageResourceProbe = FileSystemPackageResourceProbe,
): String? {
    val locator = "miniapps/${version.toolId}/versions/${version.versionCode}/bundle"
    require(version.bundleLocator.value == locator) { "Installed locator changed" }
    val root = filesRoot.toAbsolutePath().normalize()
    val bundle = root.resolve(locator).normalize()
    require(bundle.startsWith(root))
    var cursor = root
    for (part in root.relativize(bundle)) {
        cursor = cursor.resolve(part)
        require(Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) { "Installed path is not a directory" }
    }
    val hashes = linkedMapOf<String, String>()
    val metadata = linkedMapOf<String, Path>()
    val resources = PackageResourceGuard(root, resourceProbe)
    var totalBytes = 0L
    Files.walk(bundle).use { paths ->
        paths.forEach { path ->
            resources.check()
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(!attributes.isSymbolicLink && (attributes.isDirectory || attributes.isRegularFile))
            if (attributes.isRegularFile) {
                val relative = bundle.relativize(path).joinToString("/") { it.toString() }
                val digest = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                    val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        resources.check()
                        val count = input.read(bytes)
                        if (count < 0) break
                        if (count == 0) continue
                        totalBytes = Math.addExact(totalBytes, count.toLong())
                        digest.update(bytes, 0, count)
                    }
                }
                hashes[relative] = digest.digest().hex()
                if (relative in setOf("integrity.json", "signature.json")) metadata[relative] = path
            }
        }
    }
    require(totalBytes == version.bundleBytes && aggregatePackageHash(hashes) == version.integrityHash) {
        "Installed bundle differs from the committed package"
    }
    return IntegrityVerifier.verify(metadata, hashes, resources)
}

/** Keep the existing catalog digest format byte-for-byte unchanged. */
internal fun aggregatePackageHash(hashes: Map<String, String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    hashes.toSortedMap().forEach { (path, hash) ->
        val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(pathBytes.size).array())
        digest.update(pathBytes)
        digest.update(hash.lowercase().toByteArray(StandardCharsets.US_ASCII))
    }
    return digest.digest().hex()
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
