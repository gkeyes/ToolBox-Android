package io.toolbox.tool.packagekit

import java.io.InputStream
import java.nio.file.Path

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
)

data class ManifestPermission(val name: String, val reason: String, val required: Boolean)
enum class SecurityProfile { STRICT, COMPAT }
data class ManifestNetwork(
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
    val signingKeyId: String?,
)

internal sealed interface PreparationResult {
    data class Prepared(val value: PreparedPackage) : PreparationResult
    data class Rejected(val rejection: PackageRejection) : PreparationResult
}

/** Preflight uses the exact same inspector as installation, without publishing a bundle. */
object ToolPackageInspectors {
    fun create(temporaryDirectory: java.io.File): ToolPackageInspector =
        DefaultPackageInspector(temporaryDirectory.toPath())
}
