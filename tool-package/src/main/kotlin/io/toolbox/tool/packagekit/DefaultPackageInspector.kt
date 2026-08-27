package io.toolbox.tool.packagekit

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
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
) : ToolPackageInspector, InspectionSessionConsumer {
    private val sessionStore = InspectionSessionStore(sessionRoot, terminalCleanupHook)

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

    override suspend fun claimInspectionSession(sessionId: String): InspectionSessionClaimResult =
        withContext(ioDispatcher) {
            when (val result = runInterruptible { sessionStore.claim(sessionId) }) {
                is StoredClaimResult.Claimed -> InspectionSessionClaimResult.Claimed(
                    ClaimedInspectionSession(
                        sessionId = result.sessionId,
                        bundleDirectory = result.bundleDirectory,
                        ioDispatcher = ioDispatcher,
                        discardAction = {
                            sessionStore.discardClaimed(result.sessionId, result.directory, result.ownership)
                        },
                        yieldAction = { result.ownership.release() },
                    ),
                )
                StoredClaimResult.NotFound -> InspectionSessionClaimResult.NotFound
                StoredClaimResult.AlreadyClaimed -> InspectionSessionClaimResult.AlreadyClaimed
                StoredClaimResult.Consumed -> InspectionSessionClaimResult.Consumed
                is StoredClaimResult.Failed -> InspectionSessionClaimResult.Failed(result.rejection)
            }
        }

    private fun inspectBlocking(input: PackageInput, sessionId: String, sessionDirectory: Path): InspectionResult {
        val archivePath = sessionDirectory.resolve("source.tbx")
        return try {
            sessionStore.create(sessionId)
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
            validateEntry(manifest, extracted)
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
            if (verification.blockers.isNotEmpty()) {
                sessionStore.cleanup(sessionDirectory)?.let { return InspectionResult.Rejected(it) }
            }
            InspectionResult.Inspected(
                ImportInspection(
                    sourceName = input.displayName,
                    sessionId = sessionId,
                    manifest = manifest,
                    archive = summary,
                    signature = verification.evidence,
                    riskFindings = risks,
                    blockers = verification.blockers,
                ),
            )
        } catch (error: InspectionRejected) {
            sessionStore.cleanup(sessionDirectory)?.let(InspectionResult::Rejected)
                ?: InspectionResult.Rejected(error.rejection)
        } catch (error: Exception) {
            if (error is InterruptedException || error is java.io.InterruptedIOException || Thread.currentThread().isInterrupted) {
                throw error
            }
            sessionStore.cleanup(sessionDirectory)?.let(InspectionResult::Rejected)
                ?: InspectionResult.Rejected(
                    PackageRejection(
                        PackageRejectionCode.SESSION_IO_FAILED,
                        "Package inspection failed closed: ${error.javaClass.simpleName}",
                    ),
                )
        }
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

    private fun validateEntry(manifest: ToolManifest, extracted: ExtractedArchive) {
        if (manifest.entry !in extracted.hashes) {
            reject(PackageRejectionCode.ENTRY_MISSING, "Manifest entry does not exist: ${manifest.entry}")
        }
        manifest.icon?.let { icon ->
            if (icon !in extracted.hashes) {
                reject(PackageRejectionCode.ENTRY_MISSING, "Manifest icon does not exist: $icon")
            }
        }
        val prefix = Files.newInputStream(extracted.bundleDirectory.resolve(manifest.entry)).use { input ->
            input.readNBytes(4096)
        }
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(prefix))
                .toString()
        } catch (error: Exception) {
            reject(PackageRejectionCode.ENTRY_MIME_INVALID, "Manifest entry is not UTF-8 HTML")
        }
        val lower = text.trimStart().lowercase()
        if ('\u0000' in text || (!lower.startsWith("<!doctype html") && !lower.startsWith("<html"))) {
            reject(PackageRejectionCode.ENTRY_MIME_INVALID, "Manifest entry does not have an HTML document signature")
        }
    }

    private companion object {
        const val MAX_INTEGRITY_BYTES = 1024L * 1024
        const val MAX_SIGNATURE_BYTES = 64L * 1024
    }
}

private object StaticRiskScanner {
    fun scan(bundle: Path, files: Set<String>, profile: SecurityProfile): List<RiskFinding> {
        val findings = mutableListOf<RiskFinding>()
        for (path in files.sorted()) {
            if (!path.endsWith(".html") && !path.endsWith(".js") && !path.endsWith(".css")) continue
            val file = bundle.resolve(path)
            if (Files.size(file) > MAX_SCAN_BYTES) continue
            val text = runCatching {
                Files.newBufferedReader(file, StandardCharsets.UTF_8).use { it.readText() }
            }.getOrNull() ?: continue
            if (profile == SecurityProfile.STRICT && INLINE_SCRIPT.containsMatchIn(text)) {
                findings += RiskFinding(RiskFindingCode.INLINE_SCRIPT, path, "Inline script may be incompatible with strict CSP")
            }
            if (DYNAMIC_CODE.containsMatchIn(text)) {
                findings += RiskFinding(RiskFindingCode.DYNAMIC_CODE, path, "Dynamic JavaScript construction requires review")
            }
            if (EMBEDDED_FRAME.containsMatchIn(text)) {
                findings += RiskFinding(RiskFindingCode.EMBEDDED_FRAME, path, "Embedded frame markup is blocked by runtime policy")
            }
            if (REMOTE_REFERENCE.containsMatchIn(text)) {
                findings += RiskFinding(RiskFindingCode.REMOTE_REFERENCE, path, "Direct remote references are blocked by runtime policy")
            }
        }
        return findings
    }

    private const val MAX_SCAN_BYTES = 1024L * 1024
    private val INLINE_SCRIPT = Regex("<script\\b(?![^>]*\\bsrc\\s*=)[^>]*>", RegexOption.IGNORE_CASE)
    private val DYNAMIC_CODE = Regex("(?:\\beval\\s*\\(|\\bnew\\s+Function\\s*\\()")
    private val EMBEDDED_FRAME = Regex("<(?:iframe|object|embed)(?:\\s|>)", RegexOption.IGNORE_CASE)
    private val REMOTE_REFERENCE = Regex("(?:https?:)?//[A-Za-z0-9]", RegexOption.IGNORE_CASE)
}
