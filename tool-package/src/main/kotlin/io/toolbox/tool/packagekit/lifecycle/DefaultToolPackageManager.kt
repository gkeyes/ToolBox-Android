package io.toolbox.tool.packagekit.lifecycle

import io.toolbox.core.data.BundleLocator
import io.toolbox.core.data.CatalogInstallAttempt
import io.toolbox.core.data.CatalogLifecycleRepository
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.CommitInstallOutcome
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.DeleteToolCatalogOutcome
import io.toolbox.core.data.InstallTransaction
import io.toolbox.core.data.InstallTransactionRepository
import io.toolbox.core.data.InstallTransactionState
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.SecurityProfile as DataSecurityProfile
import io.toolbox.core.data.ToolMetadata
import io.toolbox.core.data.ToolVersion
import io.toolbox.tool.packagekit.DefaultPackageInspector
import io.toolbox.tool.packagekit.PackageInput
import io.toolbox.tool.packagekit.PackageLimits
import io.toolbox.tool.packagekit.PackageRejection
import io.toolbox.tool.packagekit.PackageRejectionCode
import io.toolbox.tool.packagekit.PreparationResult
import io.toolbox.tool.packagekit.PreparedPackage
import io.toolbox.tool.packagekit.SecurityProfile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal class DefaultToolPackageManager(
    filesRoot: Path,
    private val catalog: CatalogRepository,
    private val lifecycle: CatalogLifecycleRepository,
    private val transactions: InstallTransactionRepository,
    limits: PackageLimits,
    private val supportedCapabilities: Set<String>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : ToolPackageManager {
    private val storage = LifecycleStorage(filesRoot)
    private val inspector = DefaultPackageInspector(filesRoot.resolve("miniapps/.imports"), limits, ioDispatcher)

    override suspend fun recoverPendingMutations(
        cleanup: ToolStateCleanup,
    ): PackageRecoveryResult = withContext(ioDispatcher) {
        val lock = acquireLock() ?: return@withContext PackageRecoveryResult.Failed(
            failure(PackageOperationFailureCode.BUSY, "Another package change is running"),
        )
        lock.use {
            recoverInterrupted(cleanup)?.let(PackageRecoveryResult::Failed) ?: PackageRecoveryResult.Recovered
        }
    }

    override suspend fun importAndInstall(
        input: PackageInput,
        cleanup: ToolStateCleanup,
    ): PackageInstallResult = withContext(ioDispatcher) {
        val lock = acquireLock() ?: return@withContext failed(PackageOperationFailureCode.BUSY, "Another package change is running")
        lock.use {
            recoverInterrupted(cleanup)?.let { return@withContext PackageInstallResult.Failed(it) }
            when (val preparation = inspector.prepare(input)) {
                is PreparationResult.Rejected -> PackageInstallResult.Rejected(preparation.rejection)
                is PreparationResult.Prepared -> installPrepared(preparation.value, cleanup)
            }
        }
    }

    override suspend fun uninstall(
        toolId: String,
        cleanup: ToolStateCleanup,
    ): PackageUninstallResult = withContext(ioDispatcher) {
        val lock = acquireLock() ?: return@withContext PackageUninstallResult.Failed(
            failure(PackageOperationFailureCode.BUSY, "Another package change is running"),
        )
        lock.use {
            recoverInterrupted(cleanup)?.let { return@withContext PackageUninstallResult.Failed(it) }
            try {
                runInterruptible { storage.beginUninstall(toolId) }
            } catch (_: Exception) {
                return@withContext PackageUninstallResult.Failed(
                    failure(PackageOperationFailureCode.STORAGE_FAILURE, "Tool deletion could not be prepared safely"),
                )
            }
            when (val result = lifecycle.deleteToolCatalog(toolId)) {
                is DataResult.Failure -> {
                    runCatching { runInterruptible { storage.completeUninstall(toolId) } }
                    PackageUninstallResult.Failed(dataFailure(result))
                }
                is DataResult.Success -> {
                    completeCommittedUninstall(toolId, cleanup)
                    if (result.value == DeleteToolCatalogOutcome.AlreadyAbsent) {
                        PackageUninstallResult.AlreadyAbsent(toolId)
                    } else {
                        PackageUninstallResult.Uninstalled(toolId)
                    }
                }
            }
        }
    }

    private suspend fun installPrepared(
        prepared: PreparedPackage,
        cleanup: ToolStateCleanup,
    ): PackageInstallResult {
        val manifest = prepared.manifest
        manifest.permissions.firstOrNull { it.required && it.name !in supportedCapabilities }?.let { unsupported ->
            discardPrepared(prepared)?.let { return PackageInstallResult.Failed(it) }
            return failed(
                PackageOperationFailureCode.UNSUPPORTED_REQUIRED_CAPABILITY,
                "Required capability is unavailable: ${unsupported.name}",
            )
        }
        val previous = catalog.observeTool(manifest.id).first()
        if (previous != null && manifest.versionCode <= previous.currentVersion.versionCode) {
            discardPrepared(prepared)?.let { return PackageInstallResult.Failed(it) }
            return failed(
                PackageOperationFailureCode.VERSION_NOT_NEWER,
                "Package version must be higher than ${previous.currentVersion.versionCode}",
            )
        }
        val transactionId = UUID.randomUUID().toString()
        val startedAt = now()
        val transaction = InstallTransaction(
            id = transactionId,
            toolId = manifest.id,
            versionCode = manifest.versionCode,
            state = InstallTransactionState.PREPARING,
            startedAt = startedAt,
            updatedAt = startedAt,
        )
        when (val begin = transactions.begin(transaction)) {
            is DataResult.Failure -> {
                discardPrepared(prepared)?.let { return PackageInstallResult.Failed(it) }
                return PackageInstallResult.Failed(dataFailure(begin))
            }
            is DataResult.Success -> Unit
        }
        if (previous != null) {
            try {
                runInterruptible {
                    storage.recordReplacementCleanup(
                        transactionId = transactionId,
                        toolId = manifest.id,
                        previousVersionCode = previous.currentVersion.versionCode,
                        nextVersionCode = manifest.versionCode,
                    )
                }
            } catch (cancelled: CancellationException) {
                failAndClean(transaction, prepared, "CANCELLED")
                throw cancelled
            } catch (_: Exception) {
                failAndClean(transaction, prepared, "REPLACEMENT_MARKER_FAILED")
                return failed(PackageOperationFailureCode.STORAGE_FAILURE, "Package update could not be prepared safely")
            }
        }
        try {
            runInterruptible { storage.stage(transactionId, prepared) }
        } catch (cancelled: CancellationException) {
            failAndClean(transaction, prepared, "CANCELLED")
            throw cancelled
        } catch (_: Exception) {
            failAndClean(transaction, prepared, "STAGE_FAILED")
            return failed(PackageOperationFailureCode.STORAGE_FAILURE, "Package could not be staged safely")
        }
        discardPrepared(prepared)?.let {
            failAndClean(transaction, prepared, "TEMP_CLEANUP_FAILED")
            return PackageInstallResult.Failed(it)
        }
        try {
            runInterruptible { storage.publish(transactionId, manifest.id, manifest.versionCode) }
        } catch (_: FileAlreadyExistsException) {
            failAndClean(transaction, null, "FILE_COLLISION")
            return failed(PackageOperationFailureCode.STORAGE_FAILURE, "Package target already exists")
        } catch (cancelled: CancellationException) {
            failAndClean(transaction, null, "CANCELLED")
            throw cancelled
        } catch (_: Exception) {
            failAndClean(transaction, null, "PUBLISH_FAILED")
            return failed(PackageOperationFailureCode.STORAGE_FAILURE, "Package could not be published atomically")
        }
        when (val marking = transactions.markCommitting(transactionId, now())) {
            is DataResult.Failure -> {
                failAndClean(transaction, null, "TRANSACTION_FAILED")
                return PackageInstallResult.Failed(dataFailure(marking))
            }
            is DataResult.Success -> Unit
        }
        val installedAt = now()
        val attempt = CatalogInstallAttempt(
            transactionId = transactionId,
            metadata = ToolMetadata(
                id = manifest.id,
                name = manifest.name,
                securityProfile = when (manifest.securityProfile) {
                    SecurityProfile.STRICT -> DataSecurityProfile.STRICT
                    SecurityProfile.COMPAT -> DataSecurityProfile.COMPAT
                },
                installedAt = installedAt,
                categoryId = manifest.categories.firstOrNull(),
            ),
            version = ToolVersion(
                toolId = manifest.id,
                versionCode = manifest.versionCode,
                version = manifest.version,
                bundleLocator = BundleLocator(storage.bundleLocator(manifest.id, manifest.versionCode)),
                bundleBytes = prepared.archive.extractedBytes,
                integrityHash = aggregateHash(prepared.fileHashes),
                installedAt = installedAt,
            ),
            initialGrants = manifest.permissions.map { permission ->
                PermissionGrant(
                    toolId = manifest.id,
                    capability = permission.name,
                    granted = permission.name in DEFAULT_GRANTED_CAPABILITIES,
                    updatedAt = installedAt,
                )
            },
        )
        return when (val commit = lifecycle.commitInstall(attempt)) {
            is DataResult.Failure -> {
                failAndClean(transaction, null, "CATALOG_REJECTED")
                PackageInstallResult.Failed(dataFailure(commit))
            }
            is DataResult.Success -> {
                completeCommittedInstall(transaction, previous?.currentVersion?.versionCode, cleanup)
                PackageInstallResult.Installed(
                    toolId = manifest.id,
                    versionCode = manifest.versionCode,
                    updated = previous != null,
                )
            }
        }
    }

    private suspend fun completeCommittedInstall(
        transaction: InstallTransaction,
        previousVersionCode: Int?,
        cleanup: ToolStateCleanup,
    ) = withContext(NonCancellable + ioDispatcher) {
        runCatching {
            runInterruptible {
                storage.finalizeCommitted(transaction.toolId, transaction.versionCode, transaction.id)
            }
        }
        if (previousVersionCode != null) {
            completeReplacementCleanup(
                ReplacementCleanup(
                    transactionId = transaction.id,
                    toolId = transaction.toolId,
                    previousVersionCode = previousVersionCode,
                    nextVersionCode = transaction.versionCode,
                ),
                cleanup,
            )
        }
    }

    private suspend fun completeReplacementCleanup(
        replacement: ReplacementCleanup,
        cleanup: ToolStateCleanup,
    ) = withContext(NonCancellable + ioDispatcher) {
        val cleaned = try {
            cleanup.afterVersionReplacement(
                replacement.toolId,
                replacement.previousVersionCode,
                replacement.nextVersionCode,
            )
            true
        } catch (_: Exception) {
            false
        }
        if (cleaned) {
            runCatching { runInterruptible { storage.completeReplacementCleanup(replacement.transactionId) } }
        }
    }

    private suspend fun completeCommittedUninstall(
        toolId: String,
        cleanup: ToolStateCleanup,
    ) = withContext(NonCancellable + ioDispatcher) {
        val filesRemoved = runCatching { runInterruptible { storage.removeTool(toolId) } }.isSuccess
        val stateRemoved = try {
            cleanup.afterUninstall(toolId)
            true
        } catch (_: Exception) {
            false
        }
        if (filesRemoved && stateRemoved) {
            runCatching { runInterruptible { storage.completeUninstall(toolId) } }
        }
    }

    private suspend fun recoverInterrupted(cleanup: ToolStateCleanup): PackageOperationFailure? {
        return try {
            runInterruptible {
                storage.removeAllStaging()
                storage.removeAllImports()
            }
            for (owned in storage.listOwnedPublishedVersions()) {
                when (val committed = lifecycle.findCommittedInstall(owned.transactionId)) {
                    is DataResult.Failure -> return dataFailure(committed)
                    is DataResult.Success -> runInterruptible {
                        val installed = committed.value
                        if (installed?.toolId == owned.toolId && installed.versionCode == owned.versionCode) {
                            storage.finalizeCommitted(owned.toolId, owned.versionCode, owned.transactionId)
                        } else {
                            storage.removeUncommitted(owned.toolId, owned.versionCode, owned.transactionId)
                        }
                    }
                }
            }
            for (transaction in transactions.observeIncomplete().first()) {
                when (val failed = transactions.fail(transaction.id, now(), "INTERRUPTED")) {
                    is DataResult.Failure -> return dataFailure(failed)
                    is DataResult.Success -> runInterruptible {
                        storage.removeUncommitted(transaction.toolId, transaction.versionCode, transaction.id)
                        storage.completeReplacementCleanup(transaction.id)
                    }
                }
            }
            for (replacement in storage.listReplacementCleanups()) {
                when (val committed = lifecycle.findCommittedInstall(replacement.transactionId)) {
                    is DataResult.Failure -> return dataFailure(committed)
                    is DataResult.Success -> {
                        val installed = committed.value
                        if (installed?.toolId == replacement.toolId && installed.versionCode == replacement.nextVersionCode) {
                            completeReplacementCleanup(replacement, cleanup)
                        } else {
                            runInterruptible { storage.completeReplacementCleanup(replacement.transactionId) }
                        }
                    }
                }
            }
            for (toolId in storage.listUninstalls()) {
                if (catalog.observeTool(toolId).first() == null) {
                    completeCommittedUninstall(toolId, cleanup)
                } else {
                    runInterruptible { storage.completeUninstall(toolId) }
                }
            }
            val activeVersions = catalog.observeTools().first().associate { tool ->
                tool.metadata.id to tool.currentVersion.versionCode
            }
            for (toolId in storage.listToolIds()) {
                val activeVersion = activeVersions[toolId]
                if (activeVersion == null) {
                    runInterruptible { storage.removeTool(toolId) }
                } else {
                    runInterruptible { storage.removeInactiveVersions(toolId, activeVersion) }
                }
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failure(PackageOperationFailureCode.STORAGE_FAILURE, "Interrupted package state could not be cleaned")
        }
    }

    private suspend fun failAndClean(
        transaction: InstallTransaction,
        prepared: PreparedPackage?,
        failureCode: String,
    ) = withContext(NonCancellable + ioDispatcher) {
        runCatching { transactions.fail(transaction.id, now(), failureCode) }
        runCatching {
            runInterruptible {
                storage.removeUncommitted(transaction.toolId, transaction.versionCode, transaction.id)
                storage.completeReplacementCleanup(transaction.id)
            }
        }
        if (prepared != null) runCatching { inspector.cleanup(prepared) }
    }

    private suspend fun discardPrepared(prepared: PreparedPackage): PackageOperationFailure? =
        withContext(NonCancellable + ioDispatcher) {
            inspector.cleanup(prepared)?.let { failure(PackageOperationFailureCode.CLEANUP_FAILURE, it.detail) }
        }

    private fun acquireLock(): MutationLock? = try {
        storage.acquireMutationLock()
    } catch (_: Exception) {
        null
    }

    private fun aggregateHash(hashes: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        hashes.toSortedMap().forEach { (path, hash) ->
            val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(pathBytes.size).array())
            digest.update(pathBytes)
            digest.update(hash.lowercase().toByteArray(StandardCharsets.US_ASCII))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun dataFailure(result: DataResult.Failure): PackageOperationFailure = when (result) {
        is DataResult.Failure.DuplicateVersion,
        is DataResult.Failure.NonMonotonicVersion,
        -> failure(PackageOperationFailureCode.VERSION_NOT_NEWER, "Package version is not newer")
        else -> failure(PackageOperationFailureCode.DATA_FAILURE, "Package catalog operation failed")
    }

    private fun failed(code: PackageOperationFailureCode, message: String) =
        PackageInstallResult.Failed(failure(code, message))

    private fun failure(code: PackageOperationFailureCode, message: String) = PackageOperationFailure(code, message)

    private companion object {
        val DEFAULT_GRANTED_CAPABILITIES = setOf(
            "storage",
            "storage.secure",
            "device.basic",
            "clipboard.write",
            "haptics",
        )
    }
}
