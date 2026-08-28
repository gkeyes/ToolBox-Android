package io.toolbox.tool.packagekit

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal class InspectionSessionDisposal(
    private val root: Path,
    private val ownershipLocks: SessionOwnershipLocks,
    private val terminalCleanupHook: TerminalCleanupHook,
    private val onConsumed: (String) -> Unit,
) {
    fun disposeOwned(
        sessionId: String,
        ownedDirectory: Path,
        ownership: ClaimOwnership,
    ): DiscardResult {
        val disposingRoot = root.resolve(DISPOSING_DIRECTORY)
        val disposing = disposingRoot.resolve(sessionId)
        try {
            Files.createDirectories(disposingRoot)
            Files.move(ownedDirectory, disposing, StandardCopyOption.ATOMIC_MOVE)
        } catch (error: Exception) {
            ownership.release()
            removeEmptyStateRoot(disposingRoot)
            error.rethrowIfInspectionInterrupted("Inspection terminal cleanup transition was interrupted")
            return DiscardResult.Failed(
                PackageRejection(
                    PackageRejectionCode.CLEANUP_FAILED,
                    "Inspection session could not enter terminal cleanup state",
                ),
            )
        }
        val cleanupFailure = cleanupDisposingWithOwnership(sessionId, disposing, ownership)
        removeEmptyStateRoot(root.resolve(CLAIMED_DIRECTORY))
        removeEmptyStateRoot(disposingRoot)
        if (cleanupFailure != null) return DiscardResult.Failed(cleanupFailure)
        onConsumed(sessionId)
        return DiscardResult.Discarded
    }

    fun cleanup(directory: Path): PackageRejection? {
        try {
            deleteTree(directory)
            return null
        } catch (error: Exception) {
            error.rethrowIfInspectionInterrupted("Inspection session cleanup was interrupted")
            return PackageRejection(
                PackageRejectionCode.CLEANUP_FAILED,
                "Inspection session cleanup failed; installation remains blocked",
            )
        }
    }

    fun recoverDisposing(sessionId: String): DisposingRecovery {
        val disposingRoot = root.resolve(DISPOSING_DIRECTORY)
        val disposing = disposingRoot.resolve(sessionId)
        if (!Files.exists(disposing, LinkOption.NOFOLLOW_LINKS)) return DisposingRecovery.Absent
        if (!Files.isDirectory(disposing, LinkOption.NOFOLLOW_LINKS)) {
            val failure = cleanup(disposing)
            removeEmptyStateRoot(disposingRoot)
            return if (failure == null) {
                onConsumed(sessionId)
                DisposingRecovery.Recovered
            } else {
                DisposingRecovery.Failed(failure)
            }
        }
        return when (val ownership = ownershipLocks.acquire(disposing)) {
            OwnershipAttempt.Busy -> DisposingRecovery.Busy
            is OwnershipAttempt.Failed -> {
                if (!Files.exists(disposing, LinkOption.NOFOLLOW_LINKS)) {
                    onConsumed(sessionId)
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
                if (cleanupFailure != null) {
                    DisposingRecovery.Failed(cleanupFailure)
                } else {
                    onConsumed(sessionId)
                    DisposingRecovery.Recovered
                }
            }
        }
    }

    fun removeEmptyStateRoot(stateRoot: Path) {
        runCatching { Files.deleteIfExists(stateRoot) }
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
            error.rethrowIfInspectionInterrupted("Terminal inspection cleanup was interrupted")
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

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private companion object {
        const val CLAIMED_DIRECTORY = ".claimed"
        const val DISPOSING_DIRECTORY = ".disposing"
    }
}

internal sealed interface DisposingRecovery {
    data object Absent : DisposingRecovery
    data object Recovered : DisposingRecovery
    data object Busy : DisposingRecovery
    data class Failed(val rejection: PackageRejection) : DisposingRecovery
}
