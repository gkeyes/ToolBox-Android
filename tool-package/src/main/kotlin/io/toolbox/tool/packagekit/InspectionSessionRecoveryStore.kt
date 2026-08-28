package io.toolbox.tool.packagekit

import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

internal fun interface RecoveryScanHook {
    fun beforeEntry()

    companion object {
        val NONE = RecoveryScanHook { }
    }
}

internal class InspectionSessionRecoveryStore(
    private val root: Path,
    private val ownershipLocks: SessionOwnershipLocks,
    private val cleanup: (Path) -> PackageRejection?,
    private val scanHook: RecoveryScanHook = RecoveryScanHook.NONE,
) {
    fun acquirePending(sessionId: String): StoredResumeResult {
        val directory = root.resolve(sessionId)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return StoredResumeResult.NotFound
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return StoredResumeResult.InvalidResidue(cleanup(directory))
        }
        return when (val ownership = ownershipLocks.acquire(directory)) {
            OwnershipAttempt.Busy -> StoredResumeResult.Busy
            is OwnershipAttempt.Failed -> StoredResumeResult.Failed(ownership.rejection)
            is OwnershipAttempt.Acquired -> StoredResumeResult.Acquired(sessionId, directory, ownership.ownership)
        }
    }

    fun acquireClaimed(sessionId: String): StoredClaimedRecoveryResult {
        val directory = root.resolve(CLAIMED_DIRECTORY).resolve(sessionId)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return StoredClaimedRecoveryResult.NotFound
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return StoredClaimedRecoveryResult.InvalidResidue(cleanup(directory))
        }
        return when (val ownership = ownershipLocks.acquire(directory)) {
            OwnershipAttempt.Busy -> StoredClaimedRecoveryResult.Busy
            is OwnershipAttempt.Failed -> StoredClaimedRecoveryResult.Failed(ownership.rejection)
            is OwnershipAttempt.Acquired -> StoredClaimedRecoveryResult.Acquired(
                sessionId,
                directory,
                ownership.ownership,
            )
        }
    }

    fun discoverPending(
        maxCandidates: Int,
        excludedSessionIds: Set<String> = emptySet(),
    ): StoredResumableDiscovery = discover(
        stateRoot = root,
        maxCandidates = maxCandidates,
        excludedSessionIds = excludedSessionIds,
        failureDetail = "Inspection recovery root could not be scanned",
    )

    fun discoverClaimed(maxCandidates: Int): StoredResumableDiscovery = discover(
        stateRoot = root.resolve(CLAIMED_DIRECTORY),
        maxCandidates = maxCandidates,
        excludedSessionIds = emptySet(),
        failureDetail = "Claimed inspection recovery root could not be scanned",
    )

    private fun discover(
        stateRoot: Path,
        maxCandidates: Int,
        excludedSessionIds: Set<String>,
        failureDetail: String,
    ): StoredResumableDiscovery {
        if (!Files.isDirectory(stateRoot, LinkOption.NOFOLLOW_LINKS)) {
            return StoredResumableDiscovery(emptyList(), truncated = false)
        }
        val sessionIds = mutableListOf<String>()
        var visited = 0
        return try {
            Files.newDirectoryStream(stateRoot).use { entries ->
                val iterator = entries.iterator()
                while (iterator.hasNext()) {
                    if (visited >= MAX_DISCOVERY_ENTRIES) {
                        return@use
                    }
                    scanHook.beforeEntry()
                    val entry = iterator.next()
                    visited += 1
                    val name = entry.fileName.toString()
                    if (!isOpaqueSessionId(name) || name in excludedSessionIds) continue
                    if (sessionIds.size >= maxCandidates) return@use
                    sessionIds += name
                }
            }
            val truncated = visited >= MAX_DISCOVERY_ENTRIES || hasAdditionalCandidate(
                stateRoot,
                sessionIds,
                excludedSessionIds,
                maxCandidates,
            )
            StoredResumableDiscovery(sessionIds, truncated)
        } catch (error: Exception) {
            error.rethrowIfInspectionInterrupted("Inspection recovery scan was interrupted")
            StoredResumableDiscovery(emptyList(), false, sessionFailure(failureDetail))
        }
    }

    private fun hasAdditionalCandidate(
        stateRoot: Path,
        selected: List<String>,
        excluded: Set<String>,
        maxCandidates: Int,
    ): Boolean {
        if (selected.size < maxCandidates && maxCandidates > 0) return false
        return try {
            Files.newDirectoryStream(stateRoot).use { entries ->
                entries.asSequence()
                    .take(MAX_DISCOVERY_ENTRIES + 1)
                    .map { it.fileName.toString() }
                    .any { isOpaqueSessionId(it) && it !in excluded && it !in selected }
            }
        } catch (error: Exception) {
            error.rethrowIfInspectionInterrupted("Inspection recovery scan was interrupted")
            true
        }
    }

    private fun isOpaqueSessionId(value: String): Boolean = isOpaqueInspectionSessionId(value)

    private fun sessionFailure(detail: String) = PackageRejection(PackageRejectionCode.SESSION_IO_FAILED, detail)

    private companion object {
        const val CLAIMED_DIRECTORY = ".claimed"
        const val MAX_DISCOVERY_ENTRIES = 64
    }
}

internal class SessionOwnershipLocks {
    fun acquire(directory: Path): OwnershipAttempt {
        val channel = try {
            FileChannel.open(
                directory.resolve(LOCK_FILE),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (error: Exception) {
            error.rethrowIfInspectionInterrupted("Inspection claim lock open was interrupted")
            return OwnershipAttempt.Failed(sessionFailure("Inspection claim lock could not be opened"))
        }
        val fileLock = try {
            channel.tryLock()
        } catch (error: OverlappingFileLockException) {
            null
        } catch (error: Exception) {
            runCatching { channel.close() }
            error.rethrowIfInspectionInterrupted("Inspection claim lock acquisition was interrupted")
            return OwnershipAttempt.Failed(sessionFailure("Inspection claim lock could not be acquired"))
        }
        if (fileLock == null) {
            runCatching { channel.close() }
            return OwnershipAttempt.Busy
        }
        return OwnershipAttempt.Acquired(ClaimOwnership(channel, fileLock))
    }

    private fun sessionFailure(detail: String) = PackageRejection(PackageRejectionCode.SESSION_IO_FAILED, detail)

    private companion object {
        const val LOCK_FILE = ".claim.lock"
    }
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

internal sealed interface OwnershipAttempt {
    data class Acquired(val ownership: ClaimOwnership) : OwnershipAttempt
    data object Busy : OwnershipAttempt
    data class Failed(val rejection: PackageRejection) : OwnershipAttempt
}

internal sealed interface StoredResumeResult {
    data class Acquired(
        val sessionId: String,
        val directory: Path,
        val ownership: ClaimOwnership,
    ) : StoredResumeResult
    data object NotFound : StoredResumeResult
    data object Busy : StoredResumeResult
    data class InvalidResidue(val cleanupFailure: PackageRejection?) : StoredResumeResult
    data class Failed(val rejection: PackageRejection) : StoredResumeResult
}

internal sealed interface StoredClaimedRecoveryResult {
    data class Acquired(
        val sessionId: String,
        val directory: Path,
        val ownership: ClaimOwnership,
    ) : StoredClaimedRecoveryResult
    data object NotFound : StoredClaimedRecoveryResult
    data object Busy : StoredClaimedRecoveryResult
    data class InvalidResidue(val cleanupFailure: PackageRejection?) : StoredClaimedRecoveryResult
    data class Failed(val rejection: PackageRejection) : StoredClaimedRecoveryResult
}

internal data class StoredResumableDiscovery(
    val sessionIds: List<String>,
    val truncated: Boolean,
    val failure: PackageRejection? = null,
)

internal fun Exception.rethrowIfInspectionInterrupted(message: String) {
    inspectionInterruption(message)?.let { throw it }
}

internal fun Exception.inspectionInterruption(message: String): InterruptedException? = when {
    this is InterruptedException -> this
    this is InterruptedIOException || this is ClosedByInterruptException || Thread.currentThread().isInterrupted ->
        InterruptedException(message).apply { initCause(this@inspectionInterruption) }
    else -> null
}

internal fun isOpaqueInspectionSessionId(value: String): Boolean =
    runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
