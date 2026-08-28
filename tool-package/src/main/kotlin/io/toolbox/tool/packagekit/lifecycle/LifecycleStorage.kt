package io.toolbox.tool.packagekit.lifecycle

import java.io.IOException
import java.nio.charset.StandardCharsets
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
import java.security.MessageDigest
import java.util.UUID

internal enum class JournalKind { INSTALL, ROLLBACK, UNINSTALL }
internal enum class JournalPhase { PREPARED, FINALIZED, COMMITTED }

internal data class LifecycleJournal(
    val operationId: String,
    val kind: JournalKind,
    val phase: JournalPhase = JournalPhase.PREPARED,
    val toolId: String,
    val versionCode: Int?,
    val priorVersionCode: Int? = null,
    val sessionId: String?,
)

internal class LifecycleStorage(private val filesRoot: Path) {
    private val miniappsRoot = filesRoot.resolve("miniapps")
    private val stateRoot = miniappsRoot.resolve(".lifecycle")
    private val journalsRoot = stateRoot.resolve("journals")
    private val stagingRoot = miniappsRoot.resolve(".staging")

    fun acquireMutationLock(): MutationLock? {
        Files.createDirectories(stateRoot)
        val channel = FileChannel.open(
            stateRoot.resolve("mutation.lock"),
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

    fun newJournal(
        kind: JournalKind,
        toolId: String,
        versionCode: Int? = null,
        priorVersionCode: Int? = null,
        sessionId: String? = null,
    ): LifecycleJournal = LifecycleJournal(
        operationId = UUID.randomUUID().toString(),
        kind = kind,
        phase = JournalPhase.PREPARED,
        toolId = requireToolId(toolId),
        versionCode = versionCode,
        priorVersionCode = priorVersionCode,
        sessionId = sessionId,
    )

    fun persist(journal: LifecycleJournal) {
        Files.createDirectories(journalsRoot)
        val bytes = buildString {
            append("schema=1\n")
            append("operationId=").append(journal.operationId).append('\n')
            append("kind=").append(journal.kind.name).append('\n')
            append("phase=").append(journal.phase.name).append('\n')
            append("toolId=").append(journal.toolId).append('\n')
            append("versionCode=").append(journal.versionCode ?: "").append('\n')
            append("priorVersionCode=").append(journal.priorVersionCode ?: "").append('\n')
            append("sessionId=").append(journal.sessionId ?: "").append('\n')
        }.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_JOURNAL_BYTES)
        val temporary = journalsRoot.resolve(".${journal.operationId}.tmp")
        val target = journalPath(journal)
        writeForced(temporary, bytes)
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        forceDirectory(journalsRoot)
    }

    fun readJournals(): List<LifecycleJournal> {
        if (!Files.isDirectory(journalsRoot, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val journals = mutableListOf<Path>()
        Files.list(journalsRoot).use { paths ->
            paths.forEach { path ->
                if (TEMP_JOURNAL.matches(path.fileName.toString())) {
                    Files.deleteIfExists(path)
                } else {
                    journals.add(path)
                }
            }
        }
        forceDirectory(journalsRoot)
        return journals.sorted().map(::parseJournal)
    }

    fun removeJournal(journal: LifecycleJournal) {
        Files.deleteIfExists(journalPath(journal))
        forceDirectory(journalsRoot)
    }

    fun copyVerifiedBundle(
        journal: LifecycleJournal,
        sourceBundle: Path,
        expectedHashes: Map<String, String>,
        maxTotalBytes: Long,
    ): Long {
        verifyExactSourceTree(sourceBundle, expectedHashes.keys)
        val stageVersionRoot = stageVersionRoot(journal)
        val stageBundle = stageBundle(journal)
        if (Files.exists(stageRoot(journal), LinkOption.NOFOLLOW_LINKS)) deleteTree(stageRoot(journal))
        Files.createDirectories(stageBundle)
        writeForced(
            stageVersionRoot.resolve(OWNER_FILE),
            journal.operationId.toByteArray(StandardCharsets.UTF_8),
        )
        forceDirectory(stageVersionRoot)
        forceDirectory(stageRoot(journal))
        forceDirectory(stagingRoot)
        var bytes = 0L
        for ((relative, expectedHash) in expectedHashes.toSortedMap()) {
            val source = resolveRelative(sourceBundle, relative)
            verifySourceParents(sourceBundle, relative)
            val attributes = Files.readAttributes(
                source,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            if (!attributes.isRegularFile || attributes.isSymbolicLink) {
                throw IntegrityMismatch("Verified source is no longer a regular file: $relative")
            }
            val target = resolveRelative(stageBundle, relative)
            Files.createDirectories(target.parent)
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            Files.newInputStream(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                FileChannel.open(
                    target,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { output ->
                    val buffer = java.nio.ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("Bundle copy interrupted")
                        buffer.clear()
                        val count = input.read(buffer.array())
                        if (count < 0) break
                        if (count == 0) continue
                        digest.update(buffer.array(), 0, count)
                        copied += count
                        if (bytes + copied > maxTotalBytes) {
                            throw IntegrityMismatch("Verified bundle exceeds its inspected byte count")
                        }
                        val outputBuffer = java.nio.ByteBuffer.wrap(buffer.array(), 0, count)
                        while (outputBuffer.hasRemaining()) output.write(outputBuffer)
                    }
                    output.force(true)
                }
            }
            val actual = digest.digest().toHex()
            if (!MessageDigest.isEqual(actual.toByteArray(), expectedHash.lowercase().toByteArray())) {
                throw IntegrityMismatch("Verified source hash changed during copy: $relative")
            }
            bytes += copied
        }
        forceTreeDirectories(stageVersionRoot)
        return bytes
    }

    fun publishInstall(journal: LifecycleJournal) {
        val versionCode = requireNotNull(journal.versionCode)
        val versionRoot = versionRoot(journal.toolId, versionCode)
        if (Files.exists(versionRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw FileAlreadyExistsException(versionRoot.toString())
        }
        val stagedVersion = stageVersionRoot(journal)
        val stagedOwner = stagedVersion.resolve(OWNER_FILE)
        if (!Files.isRegularFile(stagedOwner, LinkOption.NOFOLLOW_LINKS) || readOwner(stagedOwner) != journal.operationId) {
            throw IOException("Staged version ownership marker is missing or invalid")
        }
        Files.createDirectories(versionRoot.parent)
        Files.move(stagedVersion, versionRoot, StandardCopyOption.ATOMIC_MOVE)
        Files.deleteIfExists(stageRoot(journal))
        forceDirectory(versionRoot.parent)
        forceDirectory(stagingRoot)
    }

    fun markInstallFinalized(journal: LifecycleJournal): LifecycleJournal =
        journal.copy(phase = JournalPhase.FINALIZED).also(::persist)

    fun bundleExists(toolId: String, versionCode: Int): Boolean =
        Files.isDirectory(versionRoot(toolId, versionCode).resolve("bundle"), LinkOption.NOFOLLOW_LINKS)

    fun cleanupUncommittedInstall(journal: LifecycleJournal) {
        deleteTree(stageRoot(journal))
        deleteOwnedVersion(journal)
    }

    fun markInstallCommitted(journal: LifecycleJournal): LifecycleJournal {
        val committed = journal.copy(phase = JournalPhase.COMMITTED)
        persist(committed)
        clearInstallOwner(journal)
        return committed
    }

    fun clearInstallOwner(journal: LifecycleJournal) {
        val owner = versionRoot(journal.toolId, requireNotNull(journal.versionCode)).resolve(OWNER_FILE)
        if (!Files.isRegularFile(owner, LinkOption.NOFOLLOW_LINKS)) return
        if (readOwner(owner) != journal.operationId) throw IOException("Install ownership marker mismatches journal")
        Files.delete(owner)
        forceDirectory(owner.parent)
    }

    fun markCommitted(journal: LifecycleJournal): LifecycleJournal =
        journal.copy(phase = JournalPhase.COMMITTED).also(::persist)

    fun cleanupStaging(journal: LifecycleJournal) = deleteTree(stageRoot(journal))

    fun removeInstalledPackages(toolId: String) {
        val toolRoot = toolRoot(toolId)
        deleteTree(toolRoot.resolve("versions"))
        Files.deleteIfExists(toolRoot.resolve("active.json"))
        Files.deleteIfExists(toolRoot.resolve(".active.tmp"))
        runCatching { Files.deleteIfExists(toolRoot) }
    }

    fun writeActive(toolId: String, versionCode: Int) {
        val root = toolRoot(toolId)
        Files.createDirectories(root)
        val bytes = "{\"schemaVersion\":1,\"versionCode\":$versionCode}\n"
            .toByteArray(StandardCharsets.UTF_8)
        val temporary = root.resolve(".active.tmp")
        writeForced(temporary, bytes)
        Files.move(temporary, root.resolve("active.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        forceDirectory(root)
    }

    fun removeActive(toolId: String) {
        val root = toolRoot(toolId)
        Files.deleteIfExists(root.resolve("active.json"))
        Files.deleteIfExists(root.resolve(".active.tmp"))
    }

    fun bundleLocator(toolId: String, versionCode: Int): String =
        "miniapps/${requireToolId(toolId)}/versions/$versionCode/bundle"

    private fun parseJournal(path: Path): LifecycleJournal {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IOException("Lifecycle journal is not regular")
        val bytes = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use {
            it.readNBytes(MAX_JOURNAL_BYTES + 1)
        }
        if (bytes.size > MAX_JOURNAL_BYTES) throw IOException("Lifecycle journal exceeds its bound")
        val entries = linkedMapOf<String, String>()
        bytes.toString(StandardCharsets.UTF_8).lineSequence()
            .filter(String::isNotEmpty)
            .forEach { line ->
                val index = line.indexOf('=')
                if (index <= 0) throw IOException("Lifecycle journal is malformed")
                val key = line.substring(0, index)
                if (entries.put(key, line.substring(index + 1)) != null) {
                    throw IOException("Lifecycle journal contains duplicate keys")
                }
            }
        if (entries.keys != JOURNAL_KEYS || entries["schema"] != "1") throw IOException("Lifecycle journal schema is invalid")
        val operationId = entries.getValue("operationId")
        if (runCatching { UUID.fromString(operationId).toString() == operationId }.getOrDefault(false).not()) {
            throw IOException("Lifecycle operation id is invalid")
        }
        if (path.fileName.toString() != "$operationId.journal") throw IOException("Lifecycle journal name mismatches content")
        val kind = runCatching { JournalKind.valueOf(entries.getValue("kind")) }
            .getOrElse { throw IOException("Lifecycle journal kind is invalid") }
        val phase = runCatching { JournalPhase.valueOf(entries.getValue("phase")) }
            .getOrElse { throw IOException("Lifecycle journal phase is invalid") }
        val toolId = requireToolId(entries.getValue("toolId"))
        val versionCode = entries.getValue("versionCode").takeIf(String::isNotEmpty)?.toIntOrNull()
        val priorVersionCode = entries.getValue("priorVersionCode").takeIf(String::isNotEmpty)?.toIntOrNull()
        val sessionId = entries.getValue("sessionId").takeIf(String::isNotEmpty)
        if (sessionId != null && runCatching { UUID.fromString(sessionId).toString() == sessionId }.getOrDefault(false).not()) {
            throw IOException("Inspection session id is invalid")
        }
        if (kind == JournalKind.INSTALL && (versionCode == null || versionCode < 1 || sessionId == null)) {
            throw IOException("Install journal facts are incomplete")
        }
        if (kind == JournalKind.INSTALL && priorVersionCode != null) {
            throw IOException("Install journal contains rollback facts")
        }
        if (kind == JournalKind.ROLLBACK && (versionCode == null || priorVersionCode == null || sessionId != null)) {
            throw IOException("Rollback journal facts are incomplete")
        }
        if (kind == JournalKind.UNINSTALL && (versionCode != null || priorVersionCode != null || sessionId != null)) {
            throw IOException("Uninstall journal contains unrelated facts")
        }
        if (kind != JournalKind.INSTALL && phase == JournalPhase.FINALIZED) {
            throw IOException("Lifecycle journal phase is invalid for its operation")
        }
        return LifecycleJournal(operationId, kind, phase, toolId, versionCode, priorVersionCode, sessionId)
    }

    private fun journalPath(journal: LifecycleJournal) = journalsRoot.resolve("${journal.operationId}.journal")
    private fun stageRoot(journal: LifecycleJournal) = stagingRoot.resolve(journal.operationId)
    private fun stageVersionRoot(journal: LifecycleJournal) = stageRoot(journal).resolve("version")
    private fun stageBundle(journal: LifecycleJournal) = stageVersionRoot(journal).resolve("bundle")
    private fun toolRoot(toolId: String) = miniappsRoot.resolve(requireToolId(toolId))
    private fun versionRoot(toolId: String, versionCode: Int) = toolRoot(toolId).resolve("versions").resolve(versionCode.toString())

    private fun resolveRelative(root: Path, relative: String): Path {
        val path = root.resolve(relative).normalize()
        if (!path.startsWith(root) || relative.startsWith('/') || '\\' in relative) {
            throw IntegrityMismatch("Bundle path is not normalized: $relative")
        }
        return path
    }

    private fun verifyExactSourceTree(sourceRoot: Path, expectedFiles: Set<String>) {
        if (!Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw IntegrityMismatch("Verified source bundle is no longer a directory")
        }
        val expectedDirectories = expectedFiles.flatMapTo(mutableSetOf()) { relative ->
            val segments = relative.split('/')
            (1 until segments.size).map { count -> segments.take(count).joinToString("/") }
        }
        val actualFiles = mutableSetOf<String>()
        Files.walk(sourceRoot).use { paths ->
            paths.forEach { path ->
                if (path == sourceRoot) return@forEach
                val relative = sourceRoot.relativize(path).joinToString("/") { it.toString() }
                val attributes = Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                when {
                    attributes.isSymbolicLink || (!attributes.isDirectory && !attributes.isRegularFile) ->
                        throw IntegrityMismatch("Verified source contains a link or special file: $relative")
                    attributes.isDirectory && relative !in expectedDirectories ->
                        throw IntegrityMismatch("Verified source contains an added directory: $relative")
                    attributes.isRegularFile -> actualFiles.add(relative)
                }
            }
        }
        if (actualFiles != expectedFiles) {
            throw IntegrityMismatch("Verified source file set changed before installation")
        }
    }

    private fun verifySourceParents(sourceRoot: Path, relative: String) {
        var current = sourceRoot
        relative.split('/').dropLast(1).forEach { segment ->
            current = current.resolve(segment)
            val attributes = Files.readAttributes(
                current,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            if (!attributes.isDirectory || attributes.isSymbolicLink) {
                throw IntegrityMismatch("Verified source parent changed before installation")
            }
        }
    }

    private fun requireToolId(toolId: String): String {
        require(TOOL_ID.matches(toolId)) { "Invalid tool id" }
        return toolId
    }

    private fun writeForced(path: Path, bytes: ByteArray) {
        FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            var buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun deleteOwnedVersion(journal: LifecycleJournal) {
        val versionCode = journal.versionCode ?: return
        val root = versionRoot(journal.toolId, versionCode)
        val owner = root.resolve(OWNER_FILE)
        if (!Files.isRegularFile(owner, LinkOption.NOFOLLOW_LINKS)) return
        val value = readOwner(owner)
        if (value != journal.operationId) return
        deleteTree(root)
        forceDirectory(root.parent)
    }

    private fun forceDirectory(path: Path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return
        FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    }

    private fun forceTreeDirectories(root: Path) {
        Files.walk(root).use { paths ->
            paths.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .sorted(Comparator.reverseOrder())
                .forEach(::forceDirectory)
        }
    }

    private fun readOwner(owner: Path): String =
        Files.newInputStream(owner, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            input.readNBytes(OWNER_ID_MAX_BYTES).toString(StandardCharsets.UTF_8)
        }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_JOURNAL_BYTES = 8192
        const val OWNER_FILE = ".install-owner"
        const val OWNER_ID_MAX_BYTES = 64
        val TEMP_JOURNAL = Regex("^\\.[0-9a-f-]{36}\\.tmp$")
        val TOOL_ID = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*){2,}$")
        val JOURNAL_KEYS = setOf(
            "schema",
            "operationId",
            "kind",
            "phase",
            "toolId",
            "versionCode",
            "priorVersionCode",
            "sessionId",
        )
    }
}

internal class MutationLock(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        runCatching { if (lock.isValid) lock.release() }
        runCatching { if (channel.isOpen) channel.close() }
    }
}

internal class IntegrityMismatch(message: String) : IOException(message)
