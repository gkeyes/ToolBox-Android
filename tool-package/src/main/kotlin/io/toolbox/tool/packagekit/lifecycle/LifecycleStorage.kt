package io.toolbox.tool.packagekit.lifecycle

import io.toolbox.tool.packagekit.PreparedPackage
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal class LifecycleStorage(private val filesRoot: Path) {
    private val miniappsRoot = filesRoot.resolve("miniapps")
    private val lifecycleRoot = miniappsRoot.resolve(".lifecycle")
    private val stagingRoot = miniappsRoot.resolve(".staging")
    private val importsRoot = miniappsRoot.resolve(".imports")
    private val replacementCleanupRoot = lifecycleRoot.resolve("replacement-cleanup")
    private val uninstallCleanupRoot = lifecycleRoot.resolve("uninstall-cleanup")

    fun acquireMutationLock(): MutationLock? {
        Files.createDirectories(lifecycleRoot)
        val channel = FileChannel.open(
            lifecycleRoot.resolve("mutation.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        val lock = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (error: Exception) {
            channel.close()
            throw error
        }
        if (lock == null) {
            channel.close()
            return null
        }
        return MutationLock(channel, lock)
    }

    fun stage(transactionId: String, prepared: PreparedPackage) {
        val stageRoot = stageRoot(transactionId)
        deleteTree(stageRoot)
        val stageBundle = stageRoot.resolve("bundle")
        Files.createDirectories(stageBundle)
        var totalBytes = 0L
        verifyExactTree(prepared.bundleDirectory, prepared.fileHashes.keys)
        prepared.fileHashes.toSortedMap().forEach { (relative, expectedHash) ->
            val source = resolveRelative(prepared.bundleDirectory, relative)
            val target = resolveRelative(stageBundle, relative)
            Files.createDirectories(target.parent)
            val attributes = Files.readAttributes(source, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!attributes.isRegularFile || attributes.isSymbolicLink) throw IntegrityMismatch("Package tree changed")
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                FileChannel.open(
                    target,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { output ->
                    val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("Package copy interrupted")
                        val count = input.read(bytes)
                        if (count < 0) break
                        if (count == 0) continue
                        digest.update(bytes, 0, count)
                        totalBytes += count
                        if (totalBytes > prepared.archive.extractedBytes) throw IntegrityMismatch("Package size changed")
                        var buffer = java.nio.ByteBuffer.wrap(bytes, 0, count)
                        while (buffer.hasRemaining()) output.write(buffer)
                    }
                    output.force(true)
                }
            }
            if (!MessageDigest.isEqual(digest.digest().toHex().toByteArray(), expectedHash.toByteArray())) {
                throw IntegrityMismatch("Package hash changed: $relative")
            }
        }
        if (totalBytes != prepared.archive.extractedBytes) throw IntegrityMismatch("Package byte count changed")
        writeForced(stageRoot.resolve(OWNER_FILE), transactionId.toByteArray())
        forceTree(stageRoot)
    }

    fun publish(transactionId: String, toolId: String, versionCode: Int) {
        val stageRoot = stageRoot(transactionId)
        val owner = stageRoot.resolve(OWNER_FILE)
        if (!Files.isRegularFile(owner, LinkOption.NOFOLLOW_LINKS) || Files.readString(owner) != transactionId) {
            throw IOException("Staged package ownership is invalid")
        }
        val target = versionRoot(toolId, versionCode)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw FileAlreadyExistsException(target.toString())
        Files.createDirectories(target.parent)
        Files.move(stageRoot, target, StandardCopyOption.ATOMIC_MOVE)
        forceDirectory(target.parent)
        forceDirectory(stagingRoot)
    }

    fun finalizeCommitted(toolId: String, versionCode: Int, transactionId: String) {
        val root = versionRoot(toolId, versionCode)
        val owner = root.resolve(OWNER_FILE)
        val hasOwner = Files.exists(owner, LinkOption.NOFOLLOW_LINKS)
        val ownsVersion = Files.isRegularFile(owner, LinkOption.NOFOLLOW_LINKS) && Files.readString(owner) == transactionId
        if (hasOwner && !ownsVersion) throw IOException("Published package ownership is invalid")
        removeOtherVersions(toolId, versionCode)
        if (ownsVersion) {
            Files.delete(owner)
            forceDirectory(root)
        }
    }

    fun removeUncommitted(toolId: String, versionCode: Int, transactionId: String) {
        deleteTree(stageRoot(transactionId))
        val root = versionRoot(toolId, versionCode)
        val owner = root.resolve(OWNER_FILE)
        if (Files.isRegularFile(owner, LinkOption.NOFOLLOW_LINKS) && Files.readString(owner) == transactionId) {
            deleteTree(root)
        }
    }

    fun removeTool(toolId: String) = deleteTree(toolRoot(toolId))

    fun recordReplacementCleanup(
        transactionId: String,
        toolId: String,
        previousVersionCode: Int,
        nextVersionCode: Int,
    ) {
        require(previousVersionCode > 0 && nextVersionCode > previousVersionCode)
        val marker = replacementCleanupRoot.resolve(requireTransactionId(transactionId))
        Files.createDirectories(replacementCleanupRoot)
        writeForced(
            marker,
            listOf(requireToolId(toolId), previousVersionCode.toString(), nextVersionCode.toString())
                .joinToString("\n", postfix = "\n")
                .toByteArray(StandardCharsets.UTF_8),
        )
        forceDirectory(replacementCleanupRoot)
    }

    fun listReplacementCleanups(): List<ReplacementCleanup> = listRegularFiles(replacementCleanupRoot).map { marker ->
        val transactionId = marker.fileName.toString().also(::requireTransactionId)
        val parts = readMarker(marker).lineSequence().filter(String::isNotEmpty).toList()
        if (parts.size != 3) throw IOException("Replacement cleanup marker is malformed")
        val toolId = requireToolId(parts[0])
        val previousVersionCode = parts[1].toIntOrNull() ?: throw IOException("Replacement cleanup marker is malformed")
        val nextVersionCode = parts[2].toIntOrNull() ?: throw IOException("Replacement cleanup marker is malformed")
        if (previousVersionCode < 1 || nextVersionCode <= previousVersionCode) {
            throw IOException("Replacement cleanup marker is malformed")
        }
        ReplacementCleanup(transactionId, toolId, previousVersionCode, nextVersionCode)
    }

    fun completeReplacementCleanup(transactionId: String) {
        Files.deleteIfExists(replacementCleanupRoot.resolve(requireTransactionId(transactionId)))
        forceDirectory(replacementCleanupRoot)
    }

    fun beginUninstall(toolId: String) {
        val safeToolId = requireToolId(toolId)
        val marker = uninstallCleanupRoot.resolve(safeToolId)
        Files.createDirectories(uninstallCleanupRoot)
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || readMarker(marker) != safeToolId) {
                throw IOException("Uninstall cleanup marker is invalid")
            }
            return
        }
        writeForced(marker, safeToolId.toByteArray(StandardCharsets.UTF_8))
        forceDirectory(uninstallCleanupRoot)
    }

    fun listUninstalls(): List<String> = listRegularFiles(uninstallCleanupRoot).map { marker ->
        val toolId = requireToolId(readMarker(marker))
        if (marker.fileName.toString() != toolId) throw IOException("Uninstall cleanup marker is invalid")
        toolId
    }

    fun completeUninstall(toolId: String) {
        Files.deleteIfExists(uninstallCleanupRoot.resolve(requireToolId(toolId)))
        forceDirectory(uninstallCleanupRoot)
    }

    fun listToolIds(): List<String> {
        if (!Files.isDirectory(miniappsRoot, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val toolIds = mutableListOf<String>()
        Files.list(miniappsRoot).use { entries ->
            entries.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .map { it.fileName.toString() }
                .filter { !it.startsWith('.') && TOOL_ID.matches(it) }
                .forEach(toolIds::add)
        }
        return toolIds
    }

    fun removeInactiveVersions(toolId: String, activeVersionCode: Int) {
        removeOtherVersions(toolId, activeVersionCode)
    }

    fun removeAllImports() = deleteChildren(importsRoot)

    fun bundleLocator(toolId: String, versionCode: Int): String =
        "miniapps/${requireToolId(toolId)}/versions/$versionCode/bundle"

    fun listOwnedPublishedVersions(): List<OwnedVersion> {
        if (!Files.isDirectory(miniappsRoot, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val result = mutableListOf<OwnedVersion>()
        Files.list(miniappsRoot).use { tools ->
            tools.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && !it.fileName.toString().startsWith('.') }
                .forEach { toolRoot ->
                    val versions = toolRoot.resolve("versions")
                    if (!Files.isDirectory(versions, LinkOption.NOFOLLOW_LINKS)) return@forEach
                    Files.list(versions).use { roots ->
                        roots.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { root ->
                            val versionCode = root.fileName.toString().toIntOrNull() ?: return@forEach
                            val owner = root.resolve(OWNER_FILE)
                            if (Files.isRegularFile(owner, LinkOption.NOFOLLOW_LINKS)) {
                                result += OwnedVersion(
                                    toolId = toolRoot.fileName.toString(),
                                    versionCode = versionCode,
                                    transactionId = Files.readString(owner),
                                )
                            }
                        }
                    }
                }
        }
        return result
    }

    fun removeAllStaging() = deleteChildren(stagingRoot)

    private fun removeOtherVersions(toolId: String, activeVersionCode: Int) {
        val versions = toolRoot(toolId).resolve("versions")
        if (!Files.isDirectory(versions, LinkOption.NOFOLLOW_LINKS)) return
        Files.list(versions).use { paths ->
            paths.filter { it.fileName.toString() != activeVersionCode.toString() }.forEach(::deleteTree)
        }
        forceDirectory(versions)
    }

    private fun verifyExactTree(root: Path, expectedFiles: Set<String>) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw IntegrityMismatch("Package tree is missing")
        val actual = mutableSetOf<String>()
        Files.walk(root).use { paths ->
            paths.forEach { path ->
                if (path == root) return@forEach
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (attributes.isSymbolicLink || (!attributes.isRegularFile && !attributes.isDirectory)) {
                    throw IntegrityMismatch("Package tree contains a link or special file")
                }
                if (attributes.isRegularFile) actual += root.relativize(path).joinToString("/") { it.toString() }
            }
        }
        if (actual != expectedFiles) throw IntegrityMismatch("Package file set changed")
    }

    private fun resolveRelative(root: Path, relative: String): Path {
        val target = root.resolve(relative).normalize()
        if (!target.startsWith(root) || relative.startsWith('/') || '\\' in relative) {
            throw IntegrityMismatch("Package path is not normalized")
        }
        return target
    }

    private fun writeForced(path: Path, bytes: ByteArray) {
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use {
            var buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) it.write(buffer)
            it.force(true)
        }
    }

    private fun forceTree(root: Path) {
        Files.walk(root).use { paths ->
            paths.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .sorted(Comparator.reverseOrder())
                .forEach(::forceDirectory)
        }
    }

    private fun forceDirectory(path: Path) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private fun deleteChildren(path: Path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.list(path).use { children -> children.forEach(::deleteTree) }
    }

    private fun listRegularFiles(path: Path): List<Path> {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val files = mutableListOf<Path>()
        Files.list(path).use { entries ->
            entries.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach(files::add)
        }
        return files
    }

    private fun readMarker(path: Path): String {
        if (Files.size(path) > MAX_MARKER_BYTES) throw IOException("Lifecycle marker is too large")
        return Files.readString(path, StandardCharsets.UTF_8)
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun stageRoot(transactionId: String) = stagingRoot.resolve(requireTransactionId(transactionId))
    private fun toolRoot(toolId: String) = miniappsRoot.resolve(requireToolId(toolId))
    private fun versionRoot(toolId: String, versionCode: Int) = toolRoot(toolId).resolve("versions/$versionCode")

    private fun requireToolId(toolId: String): String = require(TOOL_ID.matches(toolId)) { "Invalid tool id" }.let { toolId }
    private fun requireTransactionId(value: String): String = require(TRANSACTION_ID.matches(value)) { "Invalid transaction id" }.let { value }
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val OWNER_FILE = ".install-owner"
        const val MAX_MARKER_BYTES = 512L
        val TOOL_ID = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*){2,}$")
        val TRANSACTION_ID = Regex("^[0-9a-f-]{36}$")
    }
}

internal data class OwnedVersion(val toolId: String, val versionCode: Int, val transactionId: String)
internal data class ReplacementCleanup(
    val transactionId: String,
    val toolId: String,
    val previousVersionCode: Int,
    val nextVersionCode: Int,
)

internal class MutationLock(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    override fun close() {
        runCatching { if (lock.isValid) lock.release() }
        runCatching { if (channel.isOpen) channel.close() }
    }
}

internal class IntegrityMismatch(message: String) : IOException(message)
