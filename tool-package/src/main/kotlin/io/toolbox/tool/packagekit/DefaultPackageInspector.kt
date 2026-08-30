package io.toolbox.tool.packagekit

import java.nio.file.Files
import java.nio.file.LinkOption
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
    private val temporaryRoot: Path,
    private val limits: PackageLimits = PackageLimits(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ToolPackageInspector {
    override suspend fun validate(input: PackageInput): PackageValidationResult = withContext(ioDispatcher) {
        when (val result = prepare(input)) {
            is PreparationResult.Rejected -> PackageValidationResult.Rejected(result.rejection)
            is PreparationResult.Prepared -> {
                val prepared = result.value
                try {
                    PackageValidationResult.Valid(prepared.manifest, prepared.archive)
                } finally {
                    deleteTree(prepared.temporaryDirectory)
                }
            }
        }
    }

    suspend fun prepare(input: PackageInput): PreparationResult = withContext(ioDispatcher) {
        val temporaryDirectory = temporaryRoot.resolve(UUID.randomUUID().toString())
        try {
            runInterruptible { prepareBlocking(input, temporaryDirectory) }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + ioDispatcher) { runCatching { deleteTree(temporaryDirectory) } }
            throw cancelled
        }
    }

    fun cleanup(prepared: PreparedPackage): PackageRejection? = try {
        deleteTree(prepared.temporaryDirectory)
        null
    } catch (_: Exception) {
        PackageRejection(PackageRejectionCode.CLEANUP_FAILED, "Temporary package files could not be removed")
    }

    private fun prepareBlocking(input: PackageInput, temporaryDirectory: Path): PreparationResult {
        val archivePath = temporaryDirectory.resolve("source.tbx")
        return try {
            Files.createDirectories(temporaryDirectory)
            copyBounded(input, archivePath)
            val checked = ZipStructureReader.read(archivePath, limits)
            validateMetadataBounds(checked)
            val extracted = ZipArchiveReader.extract(archivePath, checked, temporaryDirectory, limits)
            val manifestBytes = extracted.metadata["manifest.json"]
                ?: reject(PackageRejectionCode.MANIFEST_MISSING, "manifest.json is required at the archive root")
            val manifest = try {
                ManifestValidator.parse(manifestBytes, limits)
            } catch (error: JsonFormatException) {
                reject(PackageRejectionCode.MANIFEST_INVALID, error.message ?: "manifest.json is invalid")
            }
            BundleEntryValidator.validate(manifest, extracted.bundleDirectory, extracted.hashes)
            IntegrityVerifier.verify(extracted.metadata, extracted.hashes, limits)
            Files.deleteIfExists(archivePath)
            PreparationResult.Prepared(
                PreparedPackage(
                    manifest = manifest,
                    archive = ArchiveSummary(
                        compressedBytes = checked.compressedBytes,
                        extractedBytes = checked.extractedBytes,
                        fileCount = checked.entries.count { !it.path.directory },
                        files = checked.entries.filterNot { it.path.directory }.map { it.path.normalized }.sorted(),
                    ),
                    bundleDirectory = extracted.bundleDirectory,
                    fileHashes = extracted.hashes,
                    temporaryDirectory = temporaryDirectory,
                ),
            )
        } catch (error: InspectionRejected) {
            val cleanup = runCatching { deleteTree(temporaryDirectory) }.exceptionOrNull()
            if (cleanup == null) PreparationResult.Rejected(error.rejection) else PreparationResult.Rejected(
                PackageRejection(PackageRejectionCode.CLEANUP_FAILED, "Rejected package residue could not be removed"),
            )
        } catch (error: Exception) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Package validation was interrupted")
            val cleanup = runCatching { deleteTree(temporaryDirectory) }.exceptionOrNull()
            PreparationResult.Rejected(
                if (cleanup == null) {
                    PackageRejection(
                        PackageRejectionCode.TEMPORARY_IO_FAILED,
                        "Package validation failed closed: ${error.javaClass.simpleName}",
                    )
                } else {
                    PackageRejection(PackageRejectionCode.CLEANUP_FAILED, "Package validation residue could not be removed")
                },
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
        } catch (_: Exception) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Selected package read was interrupted")
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

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private companion object {
        const val MAX_INTEGRITY_BYTES = 1024L * 1024
        const val MAX_SIGNATURE_BYTES = 64L * 1024
    }
}
