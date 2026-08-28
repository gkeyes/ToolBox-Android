package io.toolbox.tool.packagekit

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal class DefaultPackageInspector(
    sessionRoot: Path,
    private val limits: PackageLimits = PackageLimits(),
    private val keyResolver: PublisherKeyResolver = PublisherKeyResolver.NONE,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    terminalCleanupHook: TerminalCleanupHook = TerminalCleanupHook.NONE,
    private val receiptDirectorySync: ReceiptDirectorySync = PlatformReceiptDirectorySync,
    recoveryScanHook: RecoveryScanHook = RecoveryScanHook.NONE,
) : ToolPackageInspector, InspectionSessionConsumer, ResumableInspectionRecoveryConsumer {
    private val sessionStore = InspectionSessionStore(sessionRoot, terminalCleanupHook, recoveryScanHook)
    private val resumableCoordinator = ResumableInspectionCoordinator(sessionStore, limits, keyResolver)

    override suspend fun inspect(input: PackageInput): InspectionResult = withContext(ioDispatcher) {
        val sessionId = UUID.randomUUID().toString()
        val sessionDirectory = sessionStore.directoryFor(sessionId)
        try {
            runInterruptible { inspectBlocking(input, sessionId, sessionDirectory) }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + ioDispatcher) {
                sessionStore.cleanup(sessionDirectory)?.let { failure ->
                    cancelled.addSuppressed(IllegalStateException(failure.detail))
                }
            }
            throw cancelled
        }
    }

    override suspend fun discard(sessionId: String): DiscardResult = withContext(ioDispatcher) {
        runInterruptible { sessionStore.discard(sessionId) }
    }

    override suspend fun resume(sessionId: String): ResumeInspectionResult = withContext(ioDispatcher) {
        runInterruptible { resumableCoordinator.resume(sessionId) }
    }

    override suspend fun recoverResumableAfterLifecycle(): ResumableInspectionRecovery = withContext(ioDispatcher) {
        runInterruptible { resumableCoordinator.recover() }
    }

    override suspend fun claimInspectionSession(sessionId: String): InspectionSessionClaimResult =
        withContext(ioDispatcher) {
            runInterruptible { claimAndVerifyBlocking(sessionId) }
        }

    private fun claimAndVerifyBlocking(sessionId: String): InspectionSessionClaimResult =
        when (val result = sessionStore.claim(sessionId)) {
            is StoredClaimResult.Claimed -> {
                val receipt = try {
                    VerifiedInspectionReceipts.loadAndVerify(
                        sessionId = result.sessionId,
                        sessionDirectory = result.directory,
                        limits = limits,
                        keyResolver = keyResolver,
                    )
                } catch (error: InterruptedException) {
                    result.ownership.release()
                    throw error
                } catch (error: InspectionRejected) {
                    return terminalClaimFailure(result, error.rejection)
                } catch (error: Exception) {
                    if (
                        error is java.io.InterruptedIOException ||
                        error is java.nio.channels.ClosedByInterruptException ||
                        Thread.currentThread().isInterrupted
                    ) {
                        result.ownership.release()
                        throw InterruptedException("Verified receipt reconstruction was interrupted").apply {
                            initCause(error)
                        }
                    }
                    return terminalClaimFailure(
                        result,
                        PackageRejection(
                            PackageRejectionCode.RECEIPT_INVALID,
                            "Verified inspection receipt could not be reconstructed",
                        ),
                    )
                }
                InspectionSessionClaimResult.Claimed(
                    ClaimedInspectionSession(
                        sessionId = result.sessionId,
                        bundleDirectory = result.bundleDirectory,
                        receipt = receipt,
                        ioDispatcher = ioDispatcher,
                        discardAction = {
                            sessionStore.discardClaimed(result.sessionId, result.directory, result.ownership)
                        },
                        yieldAction = {
                            sessionStore.requeueClaimed(result.sessionId, result.directory, result.ownership)
                        },
                    ),
                )
            }
            StoredClaimResult.NotFound -> InspectionSessionClaimResult.NotFound
            StoredClaimResult.AlreadyClaimed -> InspectionSessionClaimResult.AlreadyClaimed
            StoredClaimResult.Consumed -> InspectionSessionClaimResult.Consumed
            is StoredClaimResult.Failed -> InspectionSessionClaimResult.Failed(result.rejection)
        }

    private fun terminalClaimFailure(
        claim: StoredClaimResult.Claimed,
        rejection: PackageRejection,
    ): InspectionSessionClaimResult.Failed {
        val cleanup = sessionStore.discardClaimed(claim.sessionId, claim.directory, claim.ownership)
        return InspectionSessionClaimResult.Failed((cleanup as? DiscardResult.Failed)?.rejection ?: rejection)
    }

    private fun inspectBlocking(input: PackageInput, sessionId: String, sessionDirectory: Path): InspectionResult {
        val archivePath = sessionDirectory.resolve("source.tbx")
        var session: InspectionSession? = null
        return try {
            session = sessionStore.create(sessionId)
            copyBounded(input, archivePath)
            val checked = ZipStructureReader.read(archivePath, limits)
            validateMetadataBounds(checked)
            val extracted = ZipArchiveReader.extract(archivePath, checked, sessionDirectory, limits)
            val manifestBytes = extracted.metadata["manifest.json"]
                ?: reject(PackageRejectionCode.MANIFEST_MISSING, "manifest.json is required at the archive root")
            val manifest = try {
                ManifestValidator.parse(manifestBytes, limits)
            } catch (error: JsonFormatException) {
                reject(PackageRejectionCode.MANIFEST_INVALID, error.message ?: "manifest.json is invalid")
            }
            BundleEntryValidator.validate(manifest, extracted.bundleDirectory, extracted.hashes)
            val verification = IntegrityVerifier.verify(
                metadata = extracted.metadata,
                actualHashes = extracted.hashes,
                manifest = manifest,
                limits = limits,
                keyResolver = keyResolver,
            )
            val summary = ArchiveSummary(
                compressedBytes = checked.compressedBytes,
                extractedBytes = checked.extractedBytes,
                fileCount = checked.entries.count { !it.path.directory },
                files = checked.entries.filterNot { it.path.directory }.map { it.path.normalized }.sorted(),
            )
            val risks = StaticRiskScanner.scan(extracted.bundleDirectory, extracted.hashes.keys, manifest.securityProfile)
            Files.deleteIfExists(archivePath)
            val inspection = ImportInspection(
                sourceName = input.displayName,
                sessionId = sessionId,
                manifest = manifest,
                archive = summary,
                signature = verification.evidence,
                riskFindings = risks,
                blockers = verification.blockers,
            )
            if (verification.blockers.isNotEmpty()) {
                terminalCleanup(session, sessionDirectory)?.let { return InspectionResult.Rejected(it) }
                return InspectionResult.Inspected(inspection)
            }
            VerifiedInspectionReceipts.persist(
                sessionDirectory,
                inspection,
                extracted.hashes,
                limits,
                receiptDirectorySync,
            )
            sessionStore.publish(session)?.let { releaseFailure ->
                terminalCleanup(session, sessionDirectory)?.let { return InspectionResult.Rejected(it) }
                return InspectionResult.Rejected(releaseFailure)
            }
            InspectionResult.Inspected(inspection)
        } catch (error: InspectionRejected) {
            terminalCleanup(session, sessionDirectory)?.let(InspectionResult::Rejected)
                ?: InspectionResult.Rejected(error.rejection)
        } catch (error: Exception) {
            error.inspectionInterruption("Package inspection was interrupted")?.let { interrupted ->
                terminalCleanup(session, sessionDirectory)?.let { cleanupFailure ->
                    interrupted.addSuppressed(IllegalStateException(cleanupFailure.detail))
                }
                throw interrupted
            }
            terminalCleanup(session, sessionDirectory)?.let(InspectionResult::Rejected)
                ?: InspectionResult.Rejected(
                    PackageRejection(
                        PackageRejectionCode.SESSION_IO_FAILED,
                        "Package inspection failed closed: ${error.javaClass.simpleName}",
                    ),
                )
        }
    }

    private fun terminalCleanup(session: InspectionSession?, sessionDirectory: Path): PackageRejection? =
        if (session == null) {
            sessionStore.cleanup(sessionDirectory)
        } else {
            (sessionStore.abort(session) as? DiscardResult.Failed)?.rejection
        }

    private fun copyBounded(input: PackageInput, archivePath: Path) {
        try {
            input.openStream().use { source ->
                Files.newOutputStream(archivePath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { target ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > limits.maxCompressedBytes) {
                            reject(
                                PackageRejectionCode.COMPRESSED_SIZE_LIMIT,
                                "Compressed package exceeds ${limits.maxCompressedBytes} bytes",
                            )
                        }
                        target.write(buffer, 0, count)
                    }
                }
            }
        } catch (error: InspectionRejected) {
            throw error
        } catch (error: Exception) {
            error.rethrowIfInspectionInterrupted("Selected package read was interrupted")
            reject(PackageRejectionCode.SOURCE_READ_FAILED, "Unable to read the selected package")
        }
    }

    private fun validateMetadataBounds(archive: CheckedArchive) {
        val manifest = archive.entries.singleOrNull { !it.path.directory && it.path.normalized == "manifest.json" }
            ?: reject(PackageRejectionCode.MANIFEST_MISSING, "Exactly one root manifest.json is required")
        if (manifest.extractedBytes > limits.maxManifestBytes) {
            reject(PackageRejectionCode.MANIFEST_TOO_LARGE, "manifest.json exceeds ${limits.maxManifestBytes} bytes")
        }
        archive.entries.singleOrNull { !it.path.directory && it.path.normalized == "integrity.json" }?.let {
            if (it.extractedBytes > MAX_INTEGRITY_BYTES) {
                reject(PackageRejectionCode.INTEGRITY_MALFORMED, "integrity.json exceeds $MAX_INTEGRITY_BYTES bytes")
            }
        }
        archive.entries.singleOrNull { !it.path.directory && it.path.normalized == "signature.json" }?.let {
            if (it.extractedBytes > MAX_SIGNATURE_BYTES) {
                reject(PackageRejectionCode.SIGNATURE_MALFORMED, "signature.json exceeds $MAX_SIGNATURE_BYTES bytes")
            }
        }
    }

    private companion object {
        const val MAX_INTEGRITY_BYTES = 1024L * 1024
        const val MAX_SIGNATURE_BYTES = 64L * 1024
    }
}
