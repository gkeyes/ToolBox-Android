package io.toolbox.tool.packagekit

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal data class InspectionSession(
    val id: String,
    val directory: Path,
    val ownership: ClaimOwnership,
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
    recoveryScanHook: RecoveryScanHook = RecoveryScanHook.NONE,
) {
    private val lock = Any()
    private val consumed = mutableSetOf<String>()
    private val ownershipLocks = SessionOwnershipLocks()
    private val disposal = InspectionSessionDisposal(root, ownershipLocks, terminalCleanupHook) { consumed += it }
    private val recoveryStore = InspectionSessionRecoveryStore(root, ownershipLocks, ::cleanup, recoveryScanHook)

    fun create(sessionId: String): InspectionSession {
        check(isOpaqueSessionId(sessionId))
        Files.createDirectories(root)
        val directory = root.resolve(sessionId)
        Files.createDirectory(directory)
        return when (val ownership = ownershipLocks.acquire(directory)) {
            is OwnershipAttempt.Acquired -> InspectionSession(sessionId, directory, ownership.ownership)
            OwnershipAttempt.Busy -> throw IllegalStateException("New inspection session is unexpectedly busy")
            is OwnershipAttempt.Failed -> {
                runCatching { disposal.cleanup(directory) }
                throw IllegalStateException(ownership.rejection.detail)
            }
        }
    }

    fun directoryFor(sessionId: String): Path {
        check(isOpaqueSessionId(sessionId))
        return root.resolve(sessionId)
    }

    fun publish(session: InspectionSession): PackageRejection? = synchronized(lock) {
        if (!isOwnedPendingSession(session)) return@synchronized sessionFailure("Inspection session ownership is invalid")
        session.ownership.release()
    }

    fun abort(session: InspectionSession): DiscardResult = synchronized(lock) {
        if (!isOwnedPendingSession(session)) {
            session.ownership.release()
            return@synchronized DiscardResult.NotFound
        }
        disposal.disposeOwned(session.id, session.directory, session.ownership)
    }

    fun discard(sessionId: String): DiscardResult = synchronized(lock) {
        if (!isOpaqueSessionId(sessionId)) return@synchronized DiscardResult.NotFound
        when (val recovery = disposal.recoverDisposing(sessionId)) {
            DisposingRecovery.Absent -> Unit
            DisposingRecovery.Recovered -> return@synchronized DiscardResult.Discarded
            DisposingRecovery.Busy -> return@synchronized DiscardResult.Failed(cleanupInProgress())
            is DisposingRecovery.Failed -> return@synchronized DiscardResult.Failed(recovery.rejection)
        }
        val directory = root.resolve(sessionId)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return@synchronized DiscardResult.NotFound
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return@synchronized disposal.cleanup(directory)?.let { DiscardResult.Failed(it) } ?: DiscardResult.Discarded
        }
        when (val ownership = ownershipLocks.acquire(directory)) {
            OwnershipAttempt.Busy -> DiscardResult.Failed(cleanupInProgress())
            is OwnershipAttempt.Failed -> DiscardResult.Failed(ownership.rejection)
            is OwnershipAttempt.Acquired -> disposal.disposeOwned(sessionId, directory, ownership.ownership)
        }
    }

    fun acquireResumable(sessionId: String): StoredResumeResult = synchronized(lock) {
        if (!isOpaqueSessionId(sessionId)) return@synchronized StoredResumeResult.NotFound
        recoveryStore.acquirePending(sessionId)
    }

    fun discoverResumable(
        maxCandidates: Int = MAX_RESUMABLE_SESSIONS,
        excludedSessionIds: Set<String> = emptySet(),
    ): StoredResumableDiscovery = synchronized(lock) {
        recoveryStore.discoverPending(maxCandidates, excludedSessionIds)
    }

    fun discoverClaimedResumable(maxCandidates: Int = MAX_RESUMABLE_SESSIONS): StoredResumableDiscovery =
        synchronized(lock) { recoveryStore.discoverClaimed(maxCandidates) }

    fun acquireClaimedResumable(sessionId: String): StoredClaimedRecoveryResult = synchronized(lock) {
        if (!isOpaqueSessionId(sessionId)) return@synchronized StoredClaimedRecoveryResult.NotFound
        recoveryStore.acquireClaimed(sessionId)
    }

    fun discardResumable(
        sessionId: String,
        directory: Path,
        ownership: ClaimOwnership,
    ): DiscardResult = synchronized(lock) {
        if (directory != root.resolve(sessionId)) {
            ownership.release()
            return@synchronized DiscardResult.NotFound
        }
        disposal.disposeOwned(sessionId, directory, ownership)
    }

    fun releaseResumable(ownership: ClaimOwnership): PackageRejection? = ownership.release()

    fun claim(sessionId: String): StoredClaimResult = synchronized(lock) {
        if (!isOpaqueSessionId(sessionId)) return@synchronized StoredClaimResult.NotFound
        if (sessionId in consumed) return@synchronized StoredClaimResult.Consumed
        when (val recovery = disposal.recoverDisposing(sessionId)) {
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
        when (val ownership = ownershipLocks.acquire(pending)) {
            OwnershipAttempt.Busy -> StoredClaimResult.AlreadyClaimed
            is OwnershipAttempt.Failed -> StoredClaimResult.Failed(ownership.rejection)
            is OwnershipAttempt.Acquired -> {
                try {
                    Files.createDirectories(claimedRoot)
                    Files.move(pending, claimed, StandardCopyOption.ATOMIC_MOVE)
                    finishClaim(sessionId, claimed, ownership.ownership)
                } catch (error: Exception) {
                    ownership.ownership.release()
                    error.rethrowIfInspectionInterrupted("Inspection session claim was interrupted")
                    when {
                        Files.exists(claimed, LinkOption.NOFOLLOW_LINKS) -> StoredClaimResult.AlreadyClaimed
                        else -> {
                            disposal.removeEmptyStateRoot(claimedRoot)
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
        if (sessionId in consumed) {
            ownership.release()
            return@synchronized DiscardResult.Discarded
        }
        if (!Files.isDirectory(claimedDirectory, LinkOption.NOFOLLOW_LINKS)) {
            ownership.release()
            return@synchronized DiscardResult.NotFound
        }
        disposal.disposeOwned(sessionId, claimedDirectory, ownership)
    }

    fun requeueClaimed(
        sessionId: String,
        claimedDirectory: Path,
        ownership: ClaimOwnership,
    ): PackageRejection? = synchronized(lock) {
        val expected = root.resolve(CLAIMED_DIRECTORY).resolve(sessionId)
        val pending = root.resolve(sessionId)
        if (!isOpaqueSessionId(sessionId) || claimedDirectory != expected) {
            return@synchronized failedRequeue(ownership, "Claimed inspection ownership is invalid")
        }
        if (!Files.isDirectory(claimedDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return@synchronized failedRequeue(ownership, "Claimed inspection no longer exists")
        }
        if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
            return@synchronized failedRequeue(ownership, "Inspection session cannot be requeued over existing state")
        }
        try {
            Files.move(claimedDirectory, pending, StandardCopyOption.ATOMIC_MOVE)
            disposal.removeEmptyStateRoot(root.resolve(CLAIMED_DIRECTORY))
        } catch (error: Exception) {
            val releaseFailure = ownership.release()
            error.rethrowIfInspectionInterrupted("Claimed inspection requeue was interrupted")
            return@synchronized releaseFailure
                ?: sessionFailure("Claimed inspection could not be requeued atomically")
        }
        return@synchronized ownership.release()
    }

    fun cleanup(directory: Path): PackageRejection? = synchronized(lock) { disposal.cleanup(directory) }

    private fun claimExisting(sessionId: String, claimed: Path): StoredClaimResult =
        when (val ownership = ownershipLocks.acquire(claimed)) {
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

    private fun isOwnedPendingSession(session: InspectionSession): Boolean =
        isOpaqueSessionId(session.id) && session.directory == root.resolve(session.id)

    private fun sessionFailure(detail: String) = PackageRejection(
        PackageRejectionCode.SESSION_IO_FAILED,
        detail,
    )

    private fun cleanupInProgress() = PackageRejection(
        PackageRejectionCode.CLEANUP_FAILED,
        "Terminal cleanup is in progress; retry discard after it completes",
    )

    private fun isOpaqueSessionId(value: String): Boolean = isOpaqueInspectionSessionId(value)

    private fun failedRequeue(ownership: ClaimOwnership, detail: String): PackageRejection =
        ownership.release() ?: sessionFailure(detail)

    private companion object {
        const val CLAIMED_DIRECTORY = ".claimed"
        const val MAX_RESUMABLE_SESSIONS = 32
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
