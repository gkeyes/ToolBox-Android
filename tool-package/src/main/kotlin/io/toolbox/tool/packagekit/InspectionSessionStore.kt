package io.toolbox.tool.packagekit

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.UUID

internal data class InspectionSession(
    val id: String,
    val directory: Path,
)

internal fun interface TerminalCleanupHook {
    fun beforeCleanup(sessionId: String)

    companion object {
        val NONE = TerminalCleanupHook { }
    }
}

internal class InspectionSessionStore(
    private val root: Path,
    private val terminalCleanupHook: TerminalCleanupHook = TerminalCleanupHook.NONE,
) {
    private val lock = Any()
    private val consumed = mutableSetOf<String>()

    fun create(sessionId: String): InspectionSession {
        check(isOpaqueSessionId(sessionId))
        Files.createDirectories(root)
        val directory = root.resolve(sessionId)
        Files.createDirectory(directory)
        return InspectionSession(sessionId, directory)
    }

    fun directoryFor(sessionId: String): Path {
        check(isOpaqueSessionId(sessionId))
        return root.resolve(sessionId)
    }

    fun discard(sessionId: String): DiscardResult = synchronized(lock) {
        if (!isOpaqueSessionId(sessionId)) return@synchronized DiscardResult.NotFound
        when (val recovery = recoverDisposing(sessionId)) {
            DisposingRecovery.Absent -> Unit
            DisposingRecovery.Recovered -> return@synchronized DiscardResult.Discarded
            DisposingRecovery.Busy -> return@synchronized DiscardResult.Failed(cleanupInProgress())
            is DisposingRecovery.Failed -> return@synchronized DiscardResult.Failed(recovery.rejection)
        }
        val directory = root.resolve(sessionId)
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return@synchronized DiscardResult.NotFound
        cleanup(directory)?.let { DiscardResult.Failed(it) } ?: DiscardResult.Discarded
    }

    fun claim(sessionId: String): StoredClaimResult = synchronized(lock) {
        if (!isOpaqueSessionId(sessionId)) return@synchronized StoredClaimResult.NotFound
        if (sessionId in consumed) return@synchronized StoredClaimResult.Consumed
        when (val recovery = recoverDisposing(sessionId)) {
            DisposingRecovery.Absent -> Unit
            DisposingRecovery.Recovered -> return@synchronized StoredClaimResult.Consumed
            DisposingRecovery.Busy -> return@synchronized StoredClaimResult.AlreadyClaimed
            is DisposingRecovery.Failed -> return@synchronized StoredClaimResult.Failed(recovery.rejection)
        }
        val pending = root.resolve(sessionId)
        val claimedRoot = root.resolve(CLAIMED_DIRECTORY)
        val claimed = claimedRoot.resolve(sessionId)
        if (Files.exists(claimed, LinkOption.NOFOLLOW_LINKS)) {
            return@synchronized claimExisting(sessionId, claimed)
        }
        if (!Files.isDirectory(pending, LinkOption.NOFOLLOW_LINKS)) {
            return@synchronized if (Files.exists(claimed, LinkOption.NOFOLLOW_LINKS)) {
                claimExisting(sessionId, claimed)
            } else {
                StoredClaimResult.NotFound
            }
        }
        when (val ownership = acquireOwnership(pending)) {
            OwnershipAttempt.Busy -> StoredClaimResult.AlreadyClaimed
            is OwnershipAttempt.Failed -> StoredClaimResult.Failed(ownership.rejection)
            is OwnershipAttempt.Acquired -> {
                try {
                    Files.createDirectories(claimedRoot)
                    Files.move(pending, claimed, StandardCopyOption.ATOMIC_MOVE)
                    finishClaim(sessionId, claimed, ownership.ownership)
                } catch (error: Exception) {
                    ownership.ownership.release()
                    when {
                        Files.exists(claimed, LinkOption.NOFOLLOW_LINKS) -> StoredClaimResult.AlreadyClaimed
                        else -> {
                            removeEmptyStateRoot(claimedRoot)
                            StoredClaimResult.Failed(sessionFailure("Inspection session could not be claimed atomically"))
                        }
                    }
                }
            }
        }
    }

    fun discardClaimed(
        sessionId: String,
        claimedDirectory: Path,
        ownership: ClaimOwnership,
    ): DiscardResult = synchronized(lock) {
        if (!isOpaqueSessionId(sessionId) || claimedDirectory != root.resolve(CLAIMED_DIRECTORY).resolve(sessionId)) {
            ownership.release()
            return@synchronized DiscardResult.NotFound
        }
        if (sessionId in consumed) return@synchronized DiscardResult.Discarded
        if (!Files.isDirectory(claimedDirectory, LinkOption.NOFOLLOW_LINKS)) {
            ownership.release()
            return@synchronized DiscardResult.NotFound
        }
        val disposingRoot = root.resolve(DISPOSING_DIRECTORY)
        val disposing = disposingRoot.resolve(sessionId)
        try {
            Files.createDirectories(disposingRoot)
            Files.move(claimedDirectory, disposing, StandardCopyOption.ATOMIC_MOVE)
        } catch (error: Exception) {
            ownership.release()
            removeEmptyStateRoot(disposingRoot)
            return@synchronized DiscardResult.Failed(
                PackageRejection(
                    PackageRejectionCode.CLEANUP_FAILED,
                    "Claimed inspection could not enter terminal cleanup state",
                ),
            )
        }
        val cleanupFailure = cleanupDisposingWithOwnership(sessionId, disposing, ownership)
        removeEmptyStateRoot(root.resolve(CLAIMED_DIRECTORY))
        removeEmptyStateRoot(disposingRoot)
        if (cleanupFailure != null) return@synchronized DiscardResult.Failed(cleanupFailure)
        consumed += sessionId
        DiscardResult.Discarded
    }

    fun cleanup(directory: Path): PackageRejection? = synchronized(lock) {
        try {
            deleteTree(directory)
            null
        } catch (error: Exception) {
            PackageRejection(
                PackageRejectionCode.CLEANUP_FAILED,
                "Inspection session cleanup failed; installation remains blocked",
            )
        }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun claimExisting(sessionId: String, claimed: Path): StoredClaimResult =
        when (val ownership = acquireOwnership(claimed)) {
            OwnershipAttempt.Busy -> StoredClaimResult.AlreadyClaimed
            is OwnershipAttempt.Failed -> StoredClaimResult.Failed(ownership.rejection)
            is OwnershipAttempt.Acquired -> finishClaim(sessionId, claimed, ownership.ownership)
        }

    private fun finishClaim(
        sessionId: String,
        claimed: Path,
        ownership: ClaimOwnership,
    ): StoredClaimResult {
        val bundle = claimed.resolve("bundle")
        if (Files.isDirectory(bundle, LinkOption.NOFOLLOW_LINKS)) {
            return StoredClaimResult.Claimed(sessionId, claimed, bundle, ownership)
        }
        val cleanup = discardClaimed(sessionId, claimed, ownership)
        return StoredClaimResult.Failed(
            (cleanup as? DiscardResult.Failed)?.rejection
                ?: sessionFailure("Claimed inspection has no bundle and was removed"),
        )
    }

    private fun acquireOwnership(directory: Path): OwnershipAttempt {
        val channel = try {
            FileChannel.open(
                directory.resolve(LOCK_FILE),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (error: Exception) {
            return OwnershipAttempt.Failed(sessionFailure("Inspection claim lock could not be opened"))
        }
        val fileLock = try {
            channel.tryLock()
        } catch (error: OverlappingFileLockException) {
            null
        } catch (error: Exception) {
            runCatching { channel.close() }
            return OwnershipAttempt.Failed(sessionFailure("Inspection claim lock could not be acquired"))
        }
        if (fileLock == null) {
            runCatching { channel.close() }
            return OwnershipAttempt.Busy
        }
        return OwnershipAttempt.Acquired(ClaimOwnership(channel, fileLock))
    }

    private fun recoverDisposing(sessionId: String): DisposingRecovery {
        val disposingRoot = root.resolve(DISPOSING_DIRECTORY)
        val disposing = disposingRoot.resolve(sessionId)
        if (!Files.exists(disposing, LinkOption.NOFOLLOW_LINKS)) return DisposingRecovery.Absent
        if (!Files.isDirectory(disposing, LinkOption.NOFOLLOW_LINKS)) {
            val failure = cleanup(disposing)
            removeEmptyStateRoot(disposingRoot)
            return if (failure == null) {
                consumed += sessionId
                DisposingRecovery.Recovered
            } else {
                DisposingRecovery.Failed(failure)
            }
        }
        return when (val ownership = acquireOwnership(disposing)) {
            OwnershipAttempt.Busy -> DisposingRecovery.Busy
            is OwnershipAttempt.Failed -> {
                if (!Files.exists(disposing, LinkOption.NOFOLLOW_LINKS)) {
                    consumed += sessionId
                    DisposingRecovery.Recovered
                } else {
                    DisposingRecovery.Failed(
                        PackageRejection(
                            PackageRejectionCode.CLEANUP_FAILED,
                            "Terminal inspection residue could not be locked for cleanup",
                        ),
                    )
                }
            }
            is OwnershipAttempt.Acquired -> {
                val cleanupFailure = cleanupDisposingWithOwnership(
                    sessionId,
                    disposing,
                    ownership.ownership,
                )
                removeEmptyStateRoot(disposingRoot)
                when {
                    cleanupFailure != null -> DisposingRecovery.Failed(cleanupFailure)
                    else -> {
                        consumed += sessionId
                        DisposingRecovery.Recovered
                    }
                }
            }
        }
    }

    private fun cleanupDisposingWithOwnership(
        sessionId: String,
        disposing: Path,
        ownership: ClaimOwnership,
    ): PackageRejection? {
        var failure: PackageRejection? = null
        try {
            terminalCleanupHook.beforeCleanup(sessionId)
            failure = cleanup(disposing)
        } catch (error: Exception) {
            failure = PackageRejection(
                PackageRejectionCode.CLEANUP_FAILED,
                "Terminal inspection cleanup was interrupted before deletion",
            )
        } finally {
            val releaseFailure = ownership.release()
            if (failure == null) failure = releaseFailure
        }
        return failure
    }

    private fun removeEmptyStateRoot(stateRoot: Path) {
        runCatching { Files.deleteIfExists(stateRoot) }
    }

    private fun sessionFailure(detail: String) = PackageRejection(
        PackageRejectionCode.SESSION_IO_FAILED,
        detail,
    )

    private fun cleanupInProgress() = PackageRejection(
        PackageRejectionCode.CLEANUP_FAILED,
        "Terminal cleanup is in progress; retry discard after it completes",
    )

    private fun isOpaqueSessionId(value: String): Boolean =
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private companion object {
        const val CLAIMED_DIRECTORY = ".claimed"
        const val DISPOSING_DIRECTORY = ".disposing"
        const val LOCK_FILE = ".claim.lock"
    }
}

internal sealed interface StoredClaimResult {
    data class Claimed(
        val sessionId: String,
        val directory: Path,
        val bundleDirectory: Path,
        val ownership: ClaimOwnership,
    ) : StoredClaimResult
    data object NotFound : StoredClaimResult
    data object AlreadyClaimed : StoredClaimResult
    data object Consumed : StoredClaimResult
    data class Failed(val rejection: PackageRejection) : StoredClaimResult
}

internal class ClaimOwnership(
    private val channel: FileChannel,
    private val fileLock: FileLock,
) {
    fun release(): PackageRejection? = synchronized(this) {
        var failure: Exception? = null
        if (fileLock.isValid) {
            runCatching { fileLock.release() }.onFailure { failure = it as? Exception }
        }
        if (channel.isOpen) {
            runCatching { channel.close() }.onFailure { if (failure == null) failure = it as? Exception }
        }
        failure?.let {
            PackageRejection(
                PackageRejectionCode.CLEANUP_FAILED,
                "Inspection claim lock could not be released",
            )
        }
    }
}

private sealed interface OwnershipAttempt {
    data class Acquired(val ownership: ClaimOwnership) : OwnershipAttempt
    data object Busy : OwnershipAttempt
    data class Failed(val rejection: PackageRejection) : OwnershipAttempt
}

private sealed interface DisposingRecovery {
    data object Absent : DisposingRecovery
    data object Recovered : DisposingRecovery
    data object Busy : DisposingRecovery
    data class Failed(val rejection: PackageRejection) : DisposingRecovery
}
