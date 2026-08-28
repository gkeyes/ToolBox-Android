package io.toolbox.tool.packagekit

internal class ResumableInspectionCoordinator(
    private val sessionStore: InspectionSessionStore,
    private val limits: PackageLimits,
    private val keyResolver: PublisherKeyResolver,
) {
    fun resume(sessionId: String): ResumeInspectionResult = resumeVerified(sessionId).toPublicResult()

    fun recover(): ResumableInspectionRecovery {
        val inspections = mutableListOf<ImportInspection>()
        val issues = mutableListOf<ResumableInspectionIssue>()
        val requeuedSessionIds = mutableSetOf<String>()
        var busy = 0
        var cleaned = 0

        val claimedDiscovery = sessionStore.discoverClaimedResumable(MAX_RECOVERY_SESSIONS)
        for (sessionId in claimedDiscovery.sessionIds) {
            when (val result = recoverClaimed(sessionId)) {
                is VerifiedResumeResult.Resumed -> {
                    requeuedSessionIds += sessionId
                    inspections += result.inspection
                }
                VerifiedResumeResult.NotFound -> Unit
                VerifiedResumeResult.Busy -> busy += 1
                is VerifiedResumeResult.Rejected -> {
                    if (result.residueRemoved) cleaned += 1
                    issues += ResumableInspectionIssue(sessionId, result.rejection, result.residueRemoved)
                }
            }
        }

        val remaining = (MAX_RECOVERY_SESSIONS - claimedDiscovery.sessionIds.size).coerceAtLeast(0)
        val pendingDiscovery = sessionStore.discoverResumable(remaining, requeuedSessionIds)
        for (sessionId in pendingDiscovery.sessionIds) {
            when (val result = resumeVerified(sessionId)) {
                is VerifiedResumeResult.Resumed -> inspections += result.inspection
                VerifiedResumeResult.NotFound -> Unit
                VerifiedResumeResult.Busy -> busy += 1
                is VerifiedResumeResult.Rejected -> {
                    if (result.residueRemoved) cleaned += 1
                    issues += ResumableInspectionIssue(sessionId, result.rejection, result.residueRemoved)
                }
            }
        }

        return ResumableInspectionRecovery(
            inspections = inspections,
            busySessionCount = busy,
            cleanedResidueCount = cleaned,
            issues = issues,
            truncated = claimedDiscovery.truncated || pendingDiscovery.truncated,
            recoveryFailure = claimedDiscovery.failure ?: pendingDiscovery.failure,
        )
    }

    private fun recoverClaimed(sessionId: String): VerifiedResumeResult =
        when (val claimed = sessionStore.acquireClaimedResumable(sessionId)) {
            is StoredClaimedRecoveryResult.Acquired -> recoverOwnedClaimed(claimed)
            StoredClaimedRecoveryResult.NotFound -> VerifiedResumeResult.NotFound
            StoredClaimedRecoveryResult.Busy -> VerifiedResumeResult.Busy
            is StoredClaimedRecoveryResult.InvalidResidue -> VerifiedResumeResult.Rejected(
                claimed.cleanupFailure ?: receiptInvalid("Invalid claimed inspection residue was removed"),
                residueRemoved = claimed.cleanupFailure == null,
            )
            is StoredClaimedRecoveryResult.Failed -> VerifiedResumeResult.Rejected(
                claimed.rejection,
                residueRemoved = false,
            )
        }

    private fun recoverOwnedClaimed(claimed: StoredClaimedRecoveryResult.Acquired): VerifiedResumeResult {
        try {
            VerifiedInspectionReceipts.loadAndVerify(
                sessionId = claimed.sessionId,
                sessionDirectory = claimed.directory,
                limits = limits,
                keyResolver = keyResolver,
            )
        } catch (error: InterruptedException) {
            claimed.ownership.release()
            throw error
        } catch (error: InspectionRejected) {
            return terminalClaimedFailure(claimed, error.rejection)
        } catch (error: Exception) {
            try {
                error.rethrowIfInspectionInterrupted("Claimed inspection recovery was interrupted")
            } catch (interrupted: InterruptedException) {
                claimed.ownership.release()
                throw interrupted
            }
            return terminalClaimedFailure(
                claimed,
                receiptInvalid("Claimed inspection receipt could not be reconstructed"),
            )
        }
        val requeueFailure = sessionStore.requeueClaimed(
            claimed.sessionId,
            claimed.directory,
            claimed.ownership,
        )
        if (requeueFailure != null) {
            return VerifiedResumeResult.Rejected(requeueFailure, residueRemoved = false)
        }
        return resumeVerified(claimed.sessionId)
    }

    private fun terminalClaimedFailure(
        claimed: StoredClaimedRecoveryResult.Acquired,
        rejection: PackageRejection,
    ): VerifiedResumeResult.Rejected {
        val cleanup = sessionStore.discardClaimed(claimed.sessionId, claimed.directory, claimed.ownership)
        val cleanupFailure = (cleanup as? DiscardResult.Failed)?.rejection
        return VerifiedResumeResult.Rejected(cleanupFailure ?: rejection, residueRemoved = cleanupFailure == null)
    }

    private fun resumeVerified(sessionId: String): VerifiedResumeResult =
        when (val result = sessionStore.acquireResumable(sessionId)) {
            is StoredResumeResult.Acquired -> verifyPending(result)
            StoredResumeResult.NotFound -> VerifiedResumeResult.NotFound
            StoredResumeResult.Busy -> VerifiedResumeResult.Busy
            is StoredResumeResult.InvalidResidue -> VerifiedResumeResult.Rejected(
                result.cleanupFailure ?: receiptInvalid("Invalid inspection residue was removed"),
                residueRemoved = result.cleanupFailure == null,
            )
            is StoredResumeResult.Failed -> VerifiedResumeResult.Rejected(result.rejection, residueRemoved = false)
        }

    private fun verifyPending(resumable: StoredResumeResult.Acquired): VerifiedResumeResult {
        try {
            val receipt = VerifiedInspectionReceipts.loadAndVerify(
                sessionId = resumable.sessionId,
                sessionDirectory = resumable.directory,
                limits = limits,
                keyResolver = keyResolver,
            )
            val releaseFailure = sessionStore.releaseResumable(resumable.ownership)
            return if (releaseFailure == null) {
                VerifiedResumeResult.Resumed(receipt.inspection)
            } else {
                VerifiedResumeResult.Rejected(releaseFailure, residueRemoved = false)
            }
        } catch (error: InterruptedException) {
            resumable.ownership.release()
            throw error
        } catch (error: InspectionRejected) {
            return terminalPendingFailure(resumable, error.rejection)
        } catch (error: Exception) {
            try {
                error.rethrowIfInspectionInterrupted("Inspection resume was interrupted")
            } catch (interrupted: InterruptedException) {
                resumable.ownership.release()
                throw interrupted
            }
            return terminalPendingFailure(
                resumable,
                receiptInvalid("Verified inspection could not be resumed"),
            )
        }
    }

    private fun terminalPendingFailure(
        resumable: StoredResumeResult.Acquired,
        rejection: PackageRejection,
    ): VerifiedResumeResult.Rejected {
        val cleanup = sessionStore.discardResumable(
            resumable.sessionId,
            resumable.directory,
            resumable.ownership,
        )
        val cleanupFailure = (cleanup as? DiscardResult.Failed)?.rejection
        return VerifiedResumeResult.Rejected(cleanupFailure ?: rejection, residueRemoved = cleanupFailure == null)
    }

    private fun receiptInvalid(detail: String) = PackageRejection(PackageRejectionCode.RECEIPT_INVALID, detail)

    private companion object {
        const val MAX_RECOVERY_SESSIONS = 32
    }
}

private sealed interface VerifiedResumeResult {
    data class Resumed(val inspection: ImportInspection) : VerifiedResumeResult
    data object NotFound : VerifiedResumeResult
    data object Busy : VerifiedResumeResult
    data class Rejected(
        val rejection: PackageRejection,
        val residueRemoved: Boolean,
    ) : VerifiedResumeResult
}

private fun VerifiedResumeResult.toPublicResult(): ResumeInspectionResult = when (this) {
    is VerifiedResumeResult.Resumed -> ResumeInspectionResult.Resumed(inspection)
    VerifiedResumeResult.NotFound -> ResumeInspectionResult.NotFound
    VerifiedResumeResult.Busy -> ResumeInspectionResult.Busy
    is VerifiedResumeResult.Rejected -> ResumeInspectionResult.Rejected(rejection)
}
