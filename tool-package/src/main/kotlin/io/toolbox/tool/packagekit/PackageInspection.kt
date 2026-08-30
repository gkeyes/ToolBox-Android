package io.toolbox.tool.packagekit

import java.io.InputStream
import java.nio.file.Path

data class PackageLimits(
    val maxCompressedBytes: Long = 20L * 1024 * 1024,
    val maxExtractedBytes: Long = 80L * 1024 * 1024,
    val maxEntries: Int = 512,
    val maxEntryBytes: Long = 20L * 1024 * 1024,
    val maxPathCharacters: Int = 180,
    val maxCompressionRatio: Double = 100.0,
    val maxManifestBytes: Long = 128L * 1024,
) {
    init {
        require(maxCompressedBytes in 1..HARD_MAX_COMPRESSED_BYTES)
        require(maxExtractedBytes in 1..HARD_MAX_EXTRACTED_BYTES)
        require(maxEntries in 1..HARD_MAX_ENTRIES)
        require(maxEntryBytes in 1..HARD_MAX_ENTRY_BYTES)
        require(maxPathCharacters in 1..HARD_MAX_PATH_CHARACTERS)
        require(maxCompressionRatio in 1.0..HARD_MAX_COMPRESSION_RATIO)
        require(maxManifestBytes in 1..HARD_MAX_MANIFEST_BYTES)
    }

    companion object {
        const val HARD_MAX_COMPRESSED_BYTES = 20L * 1024 * 1024
        const val HARD_MAX_EXTRACTED_BYTES = 80L * 1024 * 1024
        const val HARD_MAX_ENTRIES = 512
        const val HARD_MAX_ENTRY_BYTES = 20L * 1024 * 1024
        const val HARD_MAX_PATH_CHARACTERS = 180
        const val HARD_MAX_COMPRESSION_RATIO = 100.0
        const val HARD_MAX_MANIFEST_BYTES = 128L * 1024
    }
}

interface PackageInput {
    val displayName: String
    fun openStream(): InputStream
}

sealed interface PackageValidationResult {
    data class Valid(val manifest: ToolManifest, val archive: ArchiveSummary) : PackageValidationResult
    data class Rejected(val rejection: PackageRejection) : PackageValidationResult
}

interface ToolPackageInspector {
    suspend fun validate(input: PackageInput): PackageValidationResult
}

object ToolPackageInspectors {
    fun create(privateTemporaryDirectory: Path, limits: PackageLimits = PackageLimits()): ToolPackageInspector =
        DefaultPackageInspector(privateTemporaryDirectory, limits)
}

data class ArchiveSummary(
    val compressedBytes: Long,
    val extractedBytes: Long,
    val fileCount: Int,
    val files: List<String>,
)

data class ToolManifest(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val shortName: String?,
    val description: String?,
    val version: String,
    val versionCode: Int,
    val entry: String,
    val icon: String?,
    val apiVersion: String,
    val minHostVersion: String,
    val categories: List<String>,
    val permissions: List<ManifestPermission>,
    val securityProfile: SecurityProfile,
    val network: ManifestNetwork?,
    val ui: ManifestUi,
    val limits: ManifestLimits,
)

data class ManifestPermission(val name: String, val reason: String, val required: Boolean)
enum class SecurityProfile { STRICT, COMPAT }
data class ManifestNetwork(
    val allowDomains: List<String>,
    val allowRedirects: Boolean,
    val maxResponseBytes: Int,
    val timeoutMs: Int,
)
data class ManifestUi(
    val orientation: ManifestOrientation?,
    val allowFullscreen: Boolean,
    val statusBarStyle: ManifestStatusBarStyle,
    val showHostToolbar: Boolean,
)
enum class ManifestOrientation { UNSPECIFIED, PORTRAIT, LANDSCAPE }
enum class ManifestStatusBarStyle { AUTO, LIGHT, DARK }
data class ManifestLimits(val storageBytes: Int, val maxBridgePayloadBytes: Int)

data class PackageRejection(val code: PackageRejectionCode, val detail: String)

enum class PackageRejectionCode {
    SOURCE_READ_FAILED,
    TEMPORARY_IO_FAILED,
    CLEANUP_FAILED,
    COMPRESSED_SIZE_LIMIT,
    MALFORMED_ARCHIVE,
    UNSUPPORTED_ZIP_FEATURE,
    ENTRY_COUNT_LIMIT,
    ENTRY_SIZE_LIMIT,
    TOTAL_SIZE_LIMIT,
    COMPRESSION_RATIO_LIMIT,
    PATH_INVALID,
    PATH_TOO_LONG,
    PATH_COLLISION,
    SYMLINK,
    SPECIAL_FILE,
    NESTED_ARCHIVE,
    NATIVE_OR_DYNAMIC_CODE,
    EXTRACTION_FAILED,
    MANIFEST_MISSING,
    MANIFEST_TOO_LARGE,
    MANIFEST_INVALID,
    ENTRY_MISSING,
    ENTRY_MIME_INVALID,
    INTEGRITY_MALFORMED,
    INTEGRITY_FILE_SET_MISMATCH,
    INTEGRITY_HASH_MISMATCH,
    SIGNATURE_MALFORMED,
    SIGNATURE_KEY_ID_MISMATCH,
    SIGNATURE_INVALID,
}

internal data class PreparedPackage(
    val manifest: ToolManifest,
    val archive: ArchiveSummary,
    val bundleDirectory: Path,
    val fileHashes: Map<String, String>,
    val temporaryDirectory: Path,
)

internal sealed interface PreparationResult {
    data class Prepared(val value: PreparedPackage) : PreparationResult
    data class Rejected(val rejection: PackageRejection) : PreparationResult
}
