package io.toolbox.tool.packagekit.lifecycle

import io.toolbox.core.data.BundleLocator
import io.toolbox.core.data.CatalogInstallAttempt
import io.toolbox.core.data.CatalogLifecycleRepository
import io.toolbox.core.data.CatalogLifecycleSnapshot
import io.toolbox.core.data.CommitInstallOutcome
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.DeleteToolCatalogOutcome
import io.toolbox.core.data.LaunchState
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.SecurityProfile as CatalogSecurityProfile
import io.toolbox.core.data.SignatureState as CatalogSignatureState
import io.toolbox.core.data.ToolMetadata
import io.toolbox.core.data.ToolVersion
import io.toolbox.core.data.ToolVersionIdentity
import io.toolbox.tool.packagekit.ClaimYieldResult
import io.toolbox.tool.packagekit.ClaimedInspectionSession
import io.toolbox.tool.packagekit.DiscardResult
import io.toolbox.tool.packagekit.InspectionSessionClaimResult
import io.toolbox.tool.packagekit.SecurityProfile
import io.toolbox.tool.packagekit.SignatureState
import io.toolbox.tool.packagekit.ToolPackageInspector
import io.toolbox.tool.packagekit.claimInspectionSession
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal sealed interface LifecycleFaultPoint {
    data object AfterVersionPublish : LifecycleFaultPoint
    data object AfterInstallCommit : LifecycleFaultPoint
    data object AfterRollbackCommit : LifecycleFaultPoint
    data object AfterUninstallCommit : LifecycleFaultPoint
    data object BeforeCommittedReplayCleanup : LifecycleFaultPoint
    data object BeforeActivePointerWrite : LifecycleFaultPoint
}

internal fun interface LifecycleFaultHook {
    fun reach(point: LifecycleFaultPoint)

    companion object {
        val NONE = LifecycleFaultHook { }
    }
}

internal class DefaultToolPackageLifecycle(
    filesRoot: Path,
    private val inspector: ToolPackageInspector,
    private val catalog: CatalogLifecycleRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val faultHook: LifecycleFaultHook = LifecycleFaultHook.NONE,
) : ToolPackageLifecycle {
    private val storage = LifecycleStorage(filesRoot)

    override suspend fun install(
        inspectionSessionId: String,
        initialGrants: List<PermissionGrant>,
    ): InstallLifecycleResult = withContext(ioDispatcher) {
        val mutationLock = try {
            storage.acquireMutationLock()
        } catch (_: Exception) {
            return@withContext InstallLifecycleResult.Failed(storageFailure("Package mutation lock could not be opened"))
        } ?: return@withContext InstallLifecycleResult.Failed(busyFailure())
        mutationLock.use {
            when (val replay = recoverRequestedInstall(inspectionSessionId)) {
                is RequestedInstallRecovery.Failed -> return@withContext InstallLifecycleResult.Failed(replay.reason)
                is RequestedInstallRecovery.Committed -> return@withContext InstallLifecycleResult.AlreadyCommitted(
                    replay.toolId,
                    replay.versionCode,
                )
                RequestedInstallRecovery.None -> Unit
            }
            recoverLocked()?.let { return@withContext InstallLifecycleResult.Failed(it) }
            findCommittedInstall(inspectionSessionId)?.let { return@withContext it }
            when (val claim = inspector.claimInspectionSession(inspectionSessionId)) {
                InspectionSessionClaimResult.NotFound,
                InspectionSessionClaimResult.Consumed,
                -> return@withContext InstallLifecycleResult.InspectionNotFound
                InspectionSessionClaimResult.AlreadyClaimed -> return@withContext InstallLifecycleResult.InspectionBusy
                is InspectionSessionClaimResult.Failed -> return@withContext InstallLifecycleResult.Failed(
                    LifecycleFailure(LifecycleFailureCode.INSPECTION_REJECTED, claim.rejection.detail),
                )
                is InspectionSessionClaimResult.Claimed -> installClaimedCancellationSafe(
                    claim.lease,
                    initialGrants,
                )
            }
        }
    }

    override suspend fun rollback(toolId: String): RollbackLifecycleResult = withContext(ioDispatcher) {
        val mutationLock = try {
            storage.acquireMutationLock()
        } catch (_: Exception) {
            return@withContext RollbackLifecycleResult.Failed(storageFailure("Package mutation lock could not be opened"))
        } ?: return@withContext RollbackLifecycleResult.Failed(busyFailure())
        mutationLock.use {
            recoverLocked()?.let { return@withContext RollbackLifecycleResult.Failed(it) }
            val before = when (val result = catalog.snapshot(toolId)) {
                is DataResult.Success -> result.value
                is DataResult.Failure -> return@withContext RollbackLifecycleResult.Failed(catalogFailure(result))
            }
            val priorVersionCode = before.tool?.activeVersionCode
                ?: return@withContext RollbackLifecycleResult.Failed(
                    LifecycleFailure(LifecycleFailureCode.CATALOG_REJECTED, "Active package version was not found"),
                )
            val targetVersionCode = before.versions
                .filter { it.versionCode < priorVersionCode && it.launchState == LaunchState.STABLE }
                .maxOfOrNull(ToolVersion::versionCode)
                ?: return@withContext RollbackLifecycleResult.Failed(
                    LifecycleFailure(LifecycleFailureCode.CATALOG_REJECTED, "No lower stable package version is available"),
                )
            val journal = try {
                storage.newJournal(
                    JournalKind.ROLLBACK,
                    toolId,
                    versionCode = targetVersionCode,
                    priorVersionCode = priorVersionCode,
                ).also(storage::persist)
            } catch (_: Exception) {
                return@withContext RollbackLifecycleResult.Failed(storageFailure("Rollback journal could not be persisted"))
            }
            when (val result = catalog.rollbackToPreviousStable(toolId)) {
                is DataResult.Failure -> {
                    runCatching { storage.removeJournal(journal) }
                    RollbackLifecycleResult.Failed(catalogFailure(result))
                }
                is DataResult.Success -> {
                    val versionCode = result.value.activeVersionCode
                    try {
                        check(versionCode == targetVersionCode) { "Catalog selected an unexpected rollback target" }
                        storage.markCommitted(journal)
                        faultHook.reach(LifecycleFaultPoint.AfterRollbackCommit)
                        if (!storage.bundleExists(toolId, versionCode)) {
                            throw IOException("Rollback bundle is missing")
                        }
                        storage.writeActive(toolId, versionCode)
                        storage.removeJournal(journal)
                        RollbackLifecycleResult.RolledBack(toolId, versionCode)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        RollbackLifecycleResult.CommittedRecoveryPending(
                            toolId,
                            versionCode,
                            recoveryFailure("Rollback committed; active cache repair remains pending"),
                        )
                    }
                }
            }
        }
    }

    override suspend fun uninstall(toolId: String): UninstallLifecycleResult = withContext(ioDispatcher) {
        val mutationLock = try {
            storage.acquireMutationLock()
        } catch (_: Exception) {
            return@withContext UninstallLifecycleResult.Failed(storageFailure("Package mutation lock could not be opened"))
        } ?: return@withContext UninstallLifecycleResult.Failed(busyFailure())
        mutationLock.use {
            recoverLocked()?.let { return@withContext UninstallLifecycleResult.Failed(it) }
            val journal = try {
                storage.newJournal(JournalKind.UNINSTALL, toolId).also(storage::persist)
            } catch (_: Exception) {
                return@withContext UninstallLifecycleResult.Failed(storageFailure("Uninstall journal could not be persisted"))
            }
            when (val result = catalog.deleteToolCatalog(toolId)) {
                is DataResult.Failure -> {
                    runCatching { storage.removeJournal(journal) }
                    UninstallLifecycleResult.Failed(catalogFailure(result))
                }
                is DataResult.Success -> {
                    val absentBefore = result.value == DeleteToolCatalogOutcome.AlreadyAbsent
                    try {
                        storage.markCommitted(journal)
                        faultHook.reach(LifecycleFaultPoint.AfterUninstallCommit)
                        storage.removeInstalledPackages(toolId)
                        storage.removeJournal(journal)
                        if (absentBefore) {
                            UninstallLifecycleResult.AlreadyAbsent(toolId)
                        } else {
                            UninstallLifecycleResult.Uninstalled(toolId)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        UninstallLifecycleResult.CommittedRecoveryPending(
                            toolId,
                            recoveryFailure("Catalog deletion committed; unreachable package cleanup remains pending"),
                        )
                    }
                }
            }
        }
    }

    override suspend fun recover(): RecoveryLifecycleResult = withContext(ioDispatcher) {
        val mutationLock = try {
            storage.acquireMutationLock()
        } catch (_: Exception) {
            return@withContext RecoveryLifecycleResult.Pending(storageFailure("Package mutation lock could not be opened"))
        } ?: return@withContext RecoveryLifecycleResult.Pending(busyFailure())
        mutationLock.use {
            recoverLocked()?.let(RecoveryLifecycleResult::Pending) ?: RecoveryLifecycleResult.Recovered
        }
    }

    private suspend fun installClaimedCancellationSafe(
        lease: ClaimedInspectionSession,
        initialGrants: List<PermissionGrant>,
    ): InstallLifecycleResult = try {
        installClaimed(lease, initialGrants)
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) {
            try {
                when (val yielded = lease.yieldOwnership()) {
                    ClaimYieldResult.Yielded, ClaimYieldResult.AlreadyTerminal -> Unit
                    is ClaimYieldResult.Failed -> cancelled.addSuppressed(
                        IOException("Inspection ownership recovery failed: ${yielded.rejection.detail}"),
                    )
                }
            } catch (handoffFailure: Exception) {
                cancelled.addSuppressed(handoffFailure)
            }
        }
        throw cancelled
    }

    private suspend fun installClaimed(
        lease: ClaimedInspectionSession,
        initialGrants: List<PermissionGrant>,
    ): InstallLifecycleResult {
        val receipt = lease.receipt
        val inspection = receipt.inspection
        if (!inspection.installable) {
            return when (val cleanup = lease.cleanup()) {
                DiscardResult.Discarded, DiscardResult.NotFound -> InstallLifecycleResult.Failed(
                    LifecycleFailure(LifecycleFailureCode.INSPECTION_REJECTED, "Inspection contains an installation blocker"),
                )
                is DiscardResult.Failed -> InstallLifecycleResult.Failed(recoveryFailure(cleanup.rejection.detail))
            }
        }
        validateGrantPlan(inspection.manifest.id, inspection.manifest.permissions.map { it.name }.toSet(), initialGrants)
            ?.let { failure ->
                return yieldForRetry(lease, failure)
            }
        val toolId = inspection.manifest.id
        val versionCode = inspection.manifest.versionCode
        val snapshot = when (val result = catalog.snapshot(toolId)) {
            is DataResult.Success -> result.value
            is DataResult.Failure -> {
                return yieldForRetry(lease, catalogFailure(result))
            }
        }
        snapshot.versions.firstOrNull { it.sourceSessionId == lease.sessionId }?.let { existing ->
            if (existing.versionCode != versionCode || existing.integrityHash != aggregateHash(receipt.fileHashes)) {
                return yieldForRetry(
                    lease,
                    LifecycleFailure(LifecycleFailureCode.CATALOG_REJECTED, "Inspection session is already bound to different package facts"),
                )
            }
            val activeCode = snapshot.tool?.activeVersionCode
            return try {
                if (activeCode != null) storage.writeActive(toolId, activeCode)
                faultHook.reach(LifecycleFaultPoint.BeforeCommittedReplayCleanup)
                when (val cleanup = lease.cleanup()) {
                    DiscardResult.Discarded, DiscardResult.NotFound ->
                        InstallLifecycleResult.AlreadyCommitted(toolId, versionCode)
                    is DiscardResult.Failed -> InstallLifecycleResult.CommittedRecoveryPending(
                        toolId,
                        versionCode,
                        recoveryFailure(cleanup.rejection.detail),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                InstallLifecycleResult.CommittedRecoveryPending(
                    toolId,
                    versionCode,
                    recoveryFailure("Committed package cache or inspection cleanup remains pending"),
                )
            }
        }

        val installedAt = now()
        val attempt = inspection.toAttempt(
            sourceSessionId = lease.sessionId,
            bundleLocator = storage.bundleLocator(toolId, versionCode),
            bundleBytes = inspection.archive.extractedBytes,
            integrityHash = aggregateHash(receipt.fileHashes),
            installedAt = installedAt,
            initialGrants = initialGrants,
        )
        var journal = storage.newJournal(JournalKind.INSTALL, toolId, versionCode, sessionId = lease.sessionId)
        var committed = false
        try {
            storage.persist(journal)
            val copiedBytes = runInterruptible {
                storage.copyVerifiedBundle(
                    journal,
                    lease.bundleDirectory,
                    receipt.fileHashes,
                    inspection.archive.extractedBytes,
                )
            }
            if (copiedBytes != inspection.archive.extractedBytes) {
                throw IntegrityMismatch("Verified bundle byte count changed during installation")
            }
            storage.publishInstall(journal)
            faultHook.reach(LifecycleFaultPoint.AfterVersionPublish)
            journal = storage.markInstallFinalized(journal)
            when (val result = catalog.commitInstall(attempt)) {
                is DataResult.Failure -> {
                    return failBeforeCommit(
                        journal,
                        lease,
                        catalogFailure(result),
                        cleanup = { storage.cleanupUncommittedInstall(journal) },
                    )
                }
                is DataResult.Success -> {
                    committed = true
                    journal = storage.markInstallCommitted(journal)
                    faultHook.reach(LifecycleFaultPoint.AfterInstallCommit)
                    faultHook.reach(LifecycleFaultPoint.BeforeActivePointerWrite)
                    storage.writeActive(toolId, versionCode)
                    storage.cleanupStaging(journal)
                    when (val cleanup = lease.cleanup()) {
                        DiscardResult.Discarded, DiscardResult.NotFound -> {
                            storage.removeJournal(journal)
                            return if (result.value == CommitInstallOutcome.AlreadyCommitted) {
                                InstallLifecycleResult.AlreadyCommitted(toolId, versionCode)
                            } else {
                                InstallLifecycleResult.Committed(toolId, versionCode)
                            }
                        }
                        is DiscardResult.Failed -> return InstallLifecycleResult.CommittedRecoveryPending(
                            toolId,
                            versionCode,
                            recoveryFailure(cleanup.rejection.detail),
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: FileAlreadyExistsException) {
            if (!committed) {
                return failBeforeCommit(
                    journal,
                    lease,
                    LifecycleFailure(LifecycleFailureCode.FILE_COLLISION, "Target package version already exists"),
                    cleanup = { storage.cleanupStaging(journal) },
                )
            }
        } catch (_: IntegrityMismatch) {
            if (!committed) {
                return failBeforeCommit(
                    journal,
                    lease,
                    LifecycleFailure(LifecycleFailureCode.FILE_INTEGRITY_MISMATCH, "Verified package changed while it was copied"),
                    cleanup = { storage.cleanupUncommittedInstall(journal) },
                )
            }
        } catch (_: Exception) {
            if (!committed) {
                return failBeforeCommit(
                    journal,
                    lease,
                    storageFailure("Package installation failed before catalog commit"),
                    cleanup = { storage.cleanupUncommittedInstall(journal) },
                )
            }
        }
        runCatching { lease.yieldOwnership() }
        return InstallLifecycleResult.CommittedRecoveryPending(
            toolId,
            versionCode,
            recoveryFailure("Catalog commit succeeded; package finalization remains pending"),
        )
    }

    private suspend fun failBeforeCommit(
        journal: LifecycleJournal,
        lease: ClaimedInspectionSession,
        original: LifecycleFailure,
        cleanup: () -> Unit,
    ): InstallLifecycleResult.Failed {
        val failure = try {
            cleanup()
            storage.removeJournal(journal)
            original
        } catch (_: Exception) {
            recoveryFailure("Pre-commit package residue could not be cleaned; recovery remains pending")
        }
        return yieldForRetry(lease, failure)
    }

    private suspend fun yieldForRetry(
        lease: ClaimedInspectionSession,
        original: LifecycleFailure,
    ): InstallLifecycleResult.Failed = when (val yielded = lease.yieldOwnership()) {
        ClaimYieldResult.Yielded -> InstallLifecycleResult.Failed(original)
        ClaimYieldResult.AlreadyTerminal -> InstallLifecycleResult.Failed(
            recoveryFailure("Inspection session entered a terminal state before retry handoff"),
        )
        is ClaimYieldResult.Failed -> InstallLifecycleResult.Failed(
            recoveryFailure(yielded.rejection.detail),
        )
    }

    private suspend fun recoverLocked(): LifecycleFailure? {
        val journals = try {
            storage.readJournals()
        } catch (_: Exception) {
            return recoveryFailure("Lifecycle journal is invalid or unreadable")
        }
        for (journal in journals) {
            val snapshot = when (val result = catalog.snapshot(journal.toolId)) {
                is DataResult.Success -> result.value
                is DataResult.Failure -> return catalogFailure(result)
            }
            try {
                when (journal.kind) {
                    JournalKind.INSTALL -> recoverInstall(journal, snapshot)
                    JournalKind.ROLLBACK -> recoverActiveCache(journal, snapshot)
                    JournalKind.UNINSTALL -> recoverUninstall(journal, snapshot)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return recoveryFailure("A committed lifecycle operation still requires recovery")
            }
        }
        return null
    }

    private suspend fun recoverRequestedInstall(sessionId: String): RequestedInstallRecovery {
        val journal = try {
            storage.readJournals().singleOrNull { it.kind == JournalKind.INSTALL && it.sessionId == sessionId }
        } catch (_: Exception) {
            return RequestedInstallRecovery.Failed(recoveryFailure("Lifecycle journal is invalid or unreadable"))
        } ?: return RequestedInstallRecovery.None
        val snapshot = when (val result = catalog.snapshot(journal.toolId)) {
            is DataResult.Success -> result.value
            is DataResult.Failure -> return RequestedInstallRecovery.Failed(catalogFailure(result))
        }
        val committed = snapshot.versions.firstOrNull { it.sourceSessionId == sessionId }
        return try {
            recoverInstall(journal, snapshot)
            if (committed == null) {
                RequestedInstallRecovery.None
            } else {
                RequestedInstallRecovery.Committed(journal.toolId, committed.versionCode)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RequestedInstallRecovery.Failed(
                recoveryFailure("The interrupted installation still requires recovery"),
            )
        }
    }

    private suspend fun findCommittedInstall(sessionId: String): InstallLifecycleResult? {
        val committed = when (val result = catalog.findCommittedInstall(sessionId)) {
            is DataResult.Success -> result.value ?: return null
            is DataResult.Failure -> return InstallLifecycleResult.Failed(catalogFailure(result))
        }
        val snapshot = when (val result = catalog.snapshot(committed.toolId)) {
            is DataResult.Success -> result.value
            is DataResult.Failure -> return InstallLifecycleResult.Failed(catalogFailure(result))
        }
        val version = snapshot.versions.singleOrNull {
            it.sourceSessionId == sessionId && it.versionCode == committed.versionCode
        } ?: return InstallLifecycleResult.Failed(
            LifecycleFailure(
                LifecycleFailureCode.CATALOG_REJECTED,
                "Committed inspection lookup disagrees with the package catalog",
            ),
        )
        val activeVersionCode = snapshot.tool?.activeVersionCode
            ?: return InstallLifecycleResult.Failed(
                LifecycleFailure(
                    LifecycleFailureCode.CATALOG_REJECTED,
                    "Committed inspection has no active package catalog entry",
                ),
            )
        return try {
            if (!storage.bundleExists(committed.toolId, activeVersionCode)) {
                throw IOException("Active package bundle is missing")
            }
            faultHook.reach(LifecycleFaultPoint.BeforeCommittedReplayCleanup)
            storage.writeActive(committed.toolId, activeVersionCode)
            InstallLifecycleResult.AlreadyCommitted(version.toolId, version.versionCode)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            InstallLifecycleResult.CommittedRecoveryPending(
                version.toolId,
                version.versionCode,
                recoveryFailure("Committed package active cache repair remains pending"),
            )
        }
    }

    private suspend fun recoverInstall(journal: LifecycleJournal, snapshot: CatalogLifecycleSnapshot) {
        val committedVersion = snapshot.versions.firstOrNull { it.sourceSessionId == journal.sessionId }
        if (committedVersion == null) {
            storage.cleanupUncommittedInstall(journal)
            when (val claim = inspector.claimInspectionSession(requireNotNull(journal.sessionId))) {
                is InspectionSessionClaimResult.Claimed -> when (claim.lease.yieldOwnership()) {
                    ClaimYieldResult.Yielded, ClaimYieldResult.AlreadyTerminal -> Unit
                    is ClaimYieldResult.Failed -> throw IOException("Inspection ownership could not be yielded")
                }
                InspectionSessionClaimResult.NotFound,
                InspectionSessionClaimResult.Consumed,
                -> Unit
                InspectionSessionClaimResult.AlreadyClaimed -> throw IOException("Inspection session is still busy")
                is InspectionSessionClaimResult.Failed -> throw IOException("Inspection session recovery failed")
            }
            storage.removeJournal(journal)
            return
        }
        if (committedVersion.versionCode != journal.versionCode || !storage.bundleExists(journal.toolId, committedVersion.versionCode)) {
            throw IOException("Committed catalog and package directory disagree")
        }
        storage.clearInstallOwner(journal)
        recoverActiveCache(journal, snapshot, removeJournal = false)
        when (val claim = inspector.claimInspectionSession(requireNotNull(journal.sessionId))) {
            is InspectionSessionClaimResult.Claimed -> when (claim.lease.cleanup()) {
                DiscardResult.Discarded, DiscardResult.NotFound -> Unit
                is DiscardResult.Failed -> throw IOException("Inspection cleanup remains pending")
            }
            InspectionSessionClaimResult.NotFound,
            InspectionSessionClaimResult.Consumed,
            -> Unit
            InspectionSessionClaimResult.AlreadyClaimed -> throw IOException("Inspection cleanup is busy")
            is InspectionSessionClaimResult.Failed -> throw IOException("Inspection cleanup failed")
        }
        storage.cleanupStaging(journal)
        storage.removeJournal(journal)
    }

    private fun recoverActiveCache(
        journal: LifecycleJournal,
        snapshot: CatalogLifecycleSnapshot,
        removeJournal: Boolean = true,
    ) {
        val activeCode = snapshot.tool?.activeVersionCode
        if (journal.kind == JournalKind.ROLLBACK && activeCode !in setOf(journal.priorVersionCode, journal.versionCode)) {
            throw IOException("Rollback journal does not match the catalog active version")
        }
        if (activeCode == null) {
            storage.removeActive(journal.toolId)
        } else {
            if (!storage.bundleExists(journal.toolId, activeCode)) throw IOException("Active package bundle is missing")
            storage.writeActive(journal.toolId, activeCode)
        }
        if (removeJournal) storage.removeJournal(journal)
    }

    private fun recoverUninstall(journal: LifecycleJournal, snapshot: CatalogLifecycleSnapshot) {
        if (snapshot.tool == null) storage.removeInstalledPackages(journal.toolId)
        storage.removeJournal(journal)
    }

    private fun validateGrantPlan(
        toolId: String,
        declared: Set<String>,
        grants: List<PermissionGrant>,
    ): LifecycleFailure? {
        if (grants.any { it.toolId != toolId || it.permission !in declared } ||
            grants.map(PermissionGrant::permission).toSet().size != grants.size
        ) {
            return LifecycleFailure(LifecycleFailureCode.GRANT_PLAN_INVALID, "Initial grants must be unique and manifest-declared")
        }
        return null
    }

    private fun io.toolbox.tool.packagekit.ImportInspection.toAttempt(
        sourceSessionId: String,
        bundleLocator: String,
        bundleBytes: Long,
        integrityHash: String,
        installedAt: Long,
        initialGrants: List<PermissionGrant>,
    ): CatalogInstallAttempt {
        val identity = ToolVersionIdentity(
            name = manifest.name,
            signatureState = signature.state.toCatalog(),
            publisherKeyId = signature.keyId,
            securityProfile = manifest.securityProfile.toCatalog(),
        )
        return CatalogInstallAttempt(
            metadata = ToolMetadata(
                id = manifest.id,
                name = identity.name,
                signatureState = identity.signatureState,
                publisherKeyId = identity.publisherKeyId,
                securityProfile = identity.securityProfile,
                installedAt = installedAt,
            ),
            version = ToolVersion(
                toolId = manifest.id,
                versionCode = manifest.versionCode,
                version = manifest.version,
                bundleLocator = BundleLocator(bundleLocator),
                bundleBytes = bundleBytes,
                integrityHash = integrityHash,
                installedAt = installedAt,
                launchState = LaunchState.PENDING,
                sourceSessionId = sourceSessionId,
                identity = identity,
            ),
            initialGrants = initialGrants,
        )
    }

    private fun SignatureState.toCatalog(): CatalogSignatureState = CatalogSignatureState.valueOf(name)
    private fun SecurityProfile.toCatalog(): CatalogSecurityProfile = CatalogSecurityProfile.valueOf(name)

    private fun aggregateHash(fileHashes: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fileHashes.toSortedMap().forEach { (path, hash) ->
            listOf(path, hash.lowercase()).forEach { value ->
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                digest.update(bytes)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun catalogFailure(failure: DataResult.Failure) = LifecycleFailure(
        LifecycleFailureCode.CATALOG_REJECTED,
        when (failure) {
            is DataResult.Failure.InvalidInput -> "Catalog rejected field ${failure.field}"
            is DataResult.Failure.DuplicateVersion -> "Package version already exists"
            is DataResult.Failure.DuplicateSourceSession -> "Inspection session is already installed"
            is DataResult.Failure.NonMonotonicVersion -> "Package version must increase monotonically"
            is DataResult.Failure.SignatureContinuityViolation -> "Package publisher signature does not match installed history"
            is DataResult.Failure.UnsignedPersistentGrant ->
                "Unsigned tools cannot persist the ${failure.permission} grant during installation"
            is DataResult.Failure.LifecycleConflict -> "Package catalog lifecycle is inconsistent"
            is DataResult.Failure.NotFound -> "Catalog entry was not found"
            is DataResult.Failure.QuotaExceeded -> "Catalog quota was exceeded"
            is DataResult.Failure.StorageFailure -> "Catalog storage failed during ${failure.operation}"
        },
    )

    private fun busyFailure() = LifecycleFailure(LifecycleFailureCode.BUSY, "Another package mutation is in progress")
    private fun storageFailure(message: String) = LifecycleFailure(LifecycleFailureCode.STORAGE_FAILURE, message)
    private fun recoveryFailure(message: String) = LifecycleFailure(LifecycleFailureCode.RECOVERY_REQUIRED, message)
}

private sealed interface RequestedInstallRecovery {
    data object None : RequestedInstallRecovery
    data class Committed(val toolId: String, val versionCode: Int) : RequestedInstallRecovery
    data class Failed(val reason: LifecycleFailure) : RequestedInstallRecovery
}
